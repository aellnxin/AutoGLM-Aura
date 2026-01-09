package com.autoglm.autoagent.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.app.NotificationCompat
import com.autoglm.autoagent.R
import com.autoglm.autoagent.data.AgentRepository
import com.autoglm.autoagent.data.AgentState
import com.autoglm.autoagent.shell.ShellServiceConnector
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 虚拟屏幕浮窗服务
 * 
 * 在后台任务执行时，以浮窗形式显示虚拟屏幕的实时画面，
 * 让用户可以观察 Agent 的操作过程而不占用主屏幕。
 */
@AndroidEntryPoint
class VirtualDisplayFloatingService : Service() {

    companion object {
        private const val TAG = "VDFloatingService"
        private const val CHANNEL_ID = "virtual_display_float"
        private const val NOTIFICATION_ID = 2002
        
        const val ACTION_SHOW = "com.autoglm.autoagent.SHOW_VD_FLOAT"
        const val ACTION_HIDE = "com.autoglm.autoagent.HIDE_VD_FLOAT"
        const val EXTRA_DISPLAY_ID = "display_id"
    }

    @Inject
    lateinit var agentRepository: AgentRepository
    
    @Inject
    lateinit var shellConnector: ShellServiceConnector

    private var windowManager: WindowManager? = null
    private var floatView: View? = null
    private var imageView: ImageView? = null
    private lateinit var layoutParams: WindowManager.LayoutParams
    
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("vd_float_prefs", MODE_PRIVATE) }
    
    private var currentDisplayId: Int = -1
    private var isExpanded = false
    
    // 刷新帧率控制
    private val frameUpdateRunnable = object : Runnable {
        override fun run() {
            if (currentDisplayId > 0) {
                updateFrame()
                handler.postDelayed(this, 100) // 10 FPS
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        observeAgentState()
        Log.i(TAG, "VirtualDisplayFloatingService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW -> {
                val displayId = intent.getIntExtra(EXTRA_DISPLAY_ID, -1)
                if (displayId > 0) {
                    showFloatWindow(displayId)
                }
            }
            ACTION_HIDE -> hideFloatWindow()
        }
        return START_STICKY
    }

    private fun showFloatWindow(displayId: Int) {
        if (floatView != null) {
            // 已存在，更新 displayId
            currentDisplayId = displayId
            startFrameUpdates()
            return
        }
        
        currentDisplayId = displayId
        createFloatView()
        startFrameUpdates()
        Log.i(TAG, "Float window shown for display $displayId")
    }

    private fun hideFloatWindow() {
        stopFrameUpdates()
        currentDisplayId = -1
        
        floatView?.let {
            try {
                windowManager?.removeView(it)
            } catch (e: Exception) {
                Log.w(TAG, "Error removing float view", e)
            }
        }
        floatView = null
        imageView = null
        Log.i(TAG, "Float window hidden")
    }

    private fun createFloatView() {
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels
        
        // 浮窗默认大小：屏幕宽度的 30%
        val floatWidth = (screenWidth * 0.3f).toInt()
        val floatHeight = (floatWidth * 16f / 9f).toInt() // 16:9 比例
        
        // 创建容器
        val container = FrameLayout(this)
        container.setBackgroundColor(android.graphics.Color.parseColor("#DD000000"))
        
        // 创建 ImageView 用于显示截图
        imageView = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(android.graphics.Color.BLACK)
        }
        container.addView(imageView)
        
        // 添加圆角边框
        val background = android.graphics.drawable.GradientDrawable().apply {
            setColor(android.graphics.Color.TRANSPARENT)
            cornerRadius = dpToPx(12).toFloat()
            setStroke(dpToPx(2), android.graphics.Color.parseColor("#66FFFFFF"))
        }
        container.background = background
        container.clipToOutline = true
        container.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, dpToPx(12).toFloat())
            }
        }
        
        // 触摸处理
        setupTouchListener(container, floatWidth, floatHeight)
        
        // 窗口参数
        layoutParams = WindowManager.LayoutParams(
            floatWidth,
            floatHeight,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = prefs.getInt("last_x", dpToPx(16))
            y = prefs.getInt("last_y", dpToPx(100))
        }
        
        floatView = container
        windowManager?.addView(floatView, layoutParams)
    }

    private fun setupTouchListener(view: View, initialWidth: Int, initialHeight: Int) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false
        var isLongPress = false
        val touchSlop = 15
        val longPressTimeout = 800L // 长按阈值
        
        val longPressRunnable = Runnable {
            isLongPress = true
            // 长按震动反馈
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val manager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager
                manager.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                vibrator.vibrate(android.os.VibrationEffect.createPredefined(android.os.VibrationEffect.EFFECT_HEAVY_CLICK))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(100)
            }
            // 关闭浮窗
            hideFloatWindow()
            Log.i(TAG, "Float window closed by long press")
        }
        
        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams.x
                    initialY = layoutParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    isLongPress = false
                    v.alpha = 0.9f
                    // 启动长按计时
                    handler.postDelayed(longPressRunnable, longPressTimeout)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (initialTouchX - event.rawX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    
                    if (kotlin.math.abs(dx) > touchSlop || kotlin.math.abs(dy) > touchSlop) {
                        isDragging = true
                        // 取消长按
                        handler.removeCallbacks(longPressRunnable)
                        layoutParams.x = initialX + dx
                        layoutParams.y = initialY + dy
                        windowManager?.updateViewLayout(floatView, layoutParams)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    // 取消长按
                    handler.removeCallbacks(longPressRunnable)
                    v.alpha = 1.0f
                    if (!isDragging && !isLongPress) {
                        toggleExpand(initialWidth, initialHeight)
                    } else if (isDragging) {
                        savePosition()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun toggleExpand(initialWidth: Int, initialHeight: Int) {
        val displayMetrics = resources.displayMetrics
        
        if (isExpanded) {
            // 缩小
            layoutParams.width = initialWidth
            layoutParams.height = initialHeight
            isExpanded = false
        } else {
            // 放大到屏幕 80%
            layoutParams.width = (displayMetrics.widthPixels * 0.8f).toInt()
            layoutParams.height = (layoutParams.width * 16f / 9f).toInt()
            isExpanded = true
        }
        
        windowManager?.updateViewLayout(floatView, layoutParams)
        Log.d(TAG, "Toggle expand: $isExpanded")
    }

    private fun startFrameUpdates() {
        handler.removeCallbacks(frameUpdateRunnable)
        handler.post(frameUpdateRunnable)
    }

    private fun stopFrameUpdates() {
        handler.removeCallbacks(frameUpdateRunnable)
    }

    private fun updateFrame() {
        if (currentDisplayId <= 0 || imageView == null) return
        
        serviceScope.launch(Dispatchers.IO) {
            try {
                // 从 ShellConnector 获取截图
                val bytes = shellConnector.captureScreen(currentDisplayId)
                if (bytes != null && bytes.isNotEmpty()) {
                    val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bitmap != null) {
                        launch(Dispatchers.Main) {
                            imageView?.setImageBitmap(bitmap)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Frame update failed", e)
            }
        }
    }

    private fun observeAgentState() {
        serviceScope.launch {
            agentRepository.agentState.collectLatest { state ->
                when (state) {
                    is AgentState.Running, is AgentState.Planning -> {
                        // 任务运行中，如果有虚拟屏幕则保持显示
                    }
                    else -> {
                        // 任务结束，隐藏浮窗
                        if (currentDisplayId > 0) {
                            hideFloatWindow()
                        }
                    }
                }
            }
        }
    }

    private fun savePosition() {
        prefs.edit()
            .putInt("last_x", layoutParams.x)
            .putInt("last_y", layoutParams.y)
            .apply()
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "虚拟屏幕浮窗",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "显示后台任务的虚拟屏幕预览"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("虚拟屏幕预览")
            .setContentText("正在显示后台任务画面")
            .setSmallIcon(R.drawable.ic_mic_glass)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        hideFloatWindow()
        serviceScope.cancel()
        Log.i(TAG, "VirtualDisplayFloatingService destroyed")
    }
}
