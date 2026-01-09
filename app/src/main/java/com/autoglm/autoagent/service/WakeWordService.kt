package com.autoglm.autoagent.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.autoglm.autoagent.MainActivity
import com.autoglm.autoagent.R
import com.autoglm.autoagent.data.AgentRepository
import com.autoglm.autoagent.data.AgentState
import com.autoglm.autoagent.data.SettingsRepository
import com.k2fsa.sherpa.onnx.*
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 语音唤醒服务
 * 
 * 使用 Sherpa-ONNX KWS 模型持续监听唤醒词，
 * 检测到唤醒词后触发语音识别流程。
 * 
 * 支持省电模式：仅在屏幕亮起时监听。
 */
@AndroidEntryPoint
class WakeWordService : Service() {

    companion object {
        private const val TAG = "WakeWordService"
        private const val CHANNEL_ID = "wake_word_service"
        private const val NOTIFICATION_ID = 2003
        
        private const val MODEL_DIR = "sherpa-onnx-kws"
        
        const val ACTION_START = "com.autoglm.autoagent.START_WAKE_WORD"
        const val ACTION_STOP = "com.autoglm.autoagent.STOP_WAKE_WORD"
        const val ACTION_PAUSE = "com.autoglm.autoagent.PAUSE_WAKE_WORD"
        const val ACTION_RESUME = "com.autoglm.autoagent.RESUME_WAKE_WORD"
    }

    @Inject
    lateinit var agentRepository: AgentRepository
    
    @Inject
    lateinit var settingsRepository: SettingsRepository

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private var kwsRecognizer: KeywordSpotter? = null
    private var kwsStream: OnlineStream? = null
    private var audioRecord: AudioRecord? = null
    
    private var isListening = false
    private var isPaused = false
    private var listeningJob: Job? = null
    
    // 省电模式：屏幕状态监听
    private var isScreenOn = true
    private var powerSavingMode = true
    
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> {
                    isScreenOn = true
                    if (powerSavingMode && !isPaused) {
                        resumeListening()
                    }
                }
                Intent.ACTION_SCREEN_OFF -> {
                    isScreenOn = false
                    if (powerSavingMode) {
                        pauseListening()
                    }
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification("初始化中..."))
        
        // 注册屏幕状态监听
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        registerReceiver(screenReceiver, filter)
        
        // 监听 Agent 状态，在语音识别时暂停唤醒检测
        observeAgentState()
        
        Log.i(TAG, "WakeWordService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startListening()
            ACTION_STOP -> stopListening()
            ACTION_PAUSE -> pauseListening()
            ACTION_RESUME -> resumeListening()
        }
        return START_STICKY
    }

    private fun startListening() {
        if (isListening) return
        
        serviceScope.launch {
            try {
                initKwsModel()
                if (kwsRecognizer == null) {
                    Log.e(TAG, "Failed to init KWS model")
                    updateNotification("模型加载失败")
                    return@launch
                }
                
                isListening = true
                isPaused = false
                updateNotification("正在监听唤醒词...")
                
                startAudioCapture()
            } catch (e: Exception) {
                Log.e(TAG, "Start listening failed", e)
                updateNotification("启动失败: ${e.message}")
            }
        }
    }

    private fun stopListening() {
        isListening = false
        isPaused = false
        listeningJob?.cancel()
        
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping audio", e)
        }
        audioRecord = null
        
        kwsStream?.release()
        kwsStream = null
        
        updateNotification("已停止")
        Log.i(TAG, "Listening stopped")
    }

    private fun pauseListening() {
        if (!isListening || isPaused) return
        
        isPaused = true
        listeningJob?.cancel()
        
        try {
            audioRecord?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error pausing audio", e)
        }
        
        updateNotification("已暂停 (屏幕关闭)")
        Log.d(TAG, "Listening paused")
    }

    private fun resumeListening() {
        if (!isListening || !isPaused) return
        
        isPaused = false
        updateNotification("正在监听唤醒词...")
        
        serviceScope.launch {
            startAudioCapture()
        }
        Log.d(TAG, "Listening resumed")
    }

    private suspend fun initKwsModel() {
        if (kwsRecognizer != null) return
        
        try {
            val config = createKwsConfig()
            kwsRecognizer = KeywordSpotter(
                assetManager = assets,
                config = config
            )
            Log.i(TAG, "KWS model loaded successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load KWS model", e)
            throw e
        }
    }

    private fun createKwsConfig(): KeywordSpotterConfig {
        val modelConfig = OnlineTransducerModelConfig(
            encoder = "$MODEL_DIR/encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
            decoder = "$MODEL_DIR/decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
            joiner = "$MODEL_DIR/joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
        )
        
        val featConfig = FeatureConfig(
            sampleRate = 16000,
            featureDim = 80
        )
        
        return KeywordSpotterConfig(
            featConfig = featConfig,
            modelConfig = OnlineModelConfig(
                transducer = modelConfig,
                tokens = "$MODEL_DIR/tokens.txt",
                numThreads = 1,
                provider = "cpu",
                debug = false
            ),
            keywordsFile = "$MODEL_DIR/keywords.txt",
            keywordsThreshold = 0.25f,
            maxActivePaths = 4,
            numTrailingBlanks = 1
        )
    }

    private fun startAudioCapture() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "No audio permission")
            updateNotification("需要麦克风权限")
            return
        }
        
        val sampleRate = 16000
        val bufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ) * 2
        
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )
        
        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord init failed")
            updateNotification("麦克风初始化失败")
            return
        }
        
        audioRecord?.startRecording()
        
        // 创建 KWS stream
        kwsStream = kwsRecognizer?.createStream() ?: return
        
        listeningJob = serviceScope.launch {
            val shortBuffer = ShortArray(1600) // 0.1s @ 16kHz
            
            while (isActive && isListening && !isPaused) {
                val read = audioRecord?.read(shortBuffer, 0, shortBuffer.size) ?: -1
                if (read > 0) {
                    // 转换为浮点
                    val floatBuffer = FloatArray(read) { shortBuffer[it] / 32768.0f }
                    
                    // 送入 KWS
                    kwsStream?.acceptWaveform(floatBuffer, 16000)
                    
                    // 检查是否检测到唤醒词
                    while (kwsRecognizer?.isReady(kwsStream!!) == true) {
                        kwsRecognizer?.decode(kwsStream!!)
                    }
                    
                    val result = kwsRecognizer?.getResult(kwsStream!!)
                    if (result != null && result.keyword.isNotEmpty()) {
                        Log.i(TAG, "🎉 Wake word detected: ${result.keyword}")
                        onWakeWordDetected(result.keyword)
                        
                        // 重置 stream
                        kwsStream?.release()
                        kwsStream = kwsRecognizer?.createStream()
                    }
                }
            }
        }
    }

    private fun onWakeWordDetected(keyword: String) {
        // 暂停唤醒检测
        pauseListening()
        
        // 触发语音识别流程
        serviceScope.launch(Dispatchers.Main) {
            agentRepository.setListening(true)
        }
        
        Log.i(TAG, "Triggered voice recognition after wake word: $keyword")
    }

    private fun observeAgentState() {
        serviceScope.launch(Dispatchers.Main) {
            agentRepository.agentState.collectLatest { state ->
                when (state) {
                    is AgentState.Listening -> {
                        // 正在语音识别，暂停唤醒检测
                        if (isListening && !isPaused) {
                            pauseListening()
                        }
                    }
                    is AgentState.Idle, is AgentState.Error -> {
                        // 空闲状态，恢复唤醒检测
                        if (isListening && isPaused && (!powerSavingMode || isScreenOn)) {
                            delay(500) // 短暂延迟，避免冲突
                            resumeListening()
                        }
                    }
                    else -> {
                        // 运行中，保持暂停
                    }
                }
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "语音唤醒服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "持续监听唤醒词"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("语音唤醒")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_mic_glass)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun updateNotification(text: String) {
        val notification = createNotification(text)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopListening()
        
        try {
            unregisterReceiver(screenReceiver)
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering receiver", e)
        }
        
        kwsRecognizer?.release()
        kwsRecognizer = null
        
        serviceScope.cancel()
        Log.i(TAG, "WakeWordService destroyed")
    }
}
