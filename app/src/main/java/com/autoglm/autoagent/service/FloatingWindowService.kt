package com.autoglm.autoagent.service

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import com.autoglm.autoagent.data.AgentRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import android.widget.ImageView
import com.autoglm.autoagent.data.AgentState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import android.app.Application
import android.app.Activity
import android.os.Bundle

@AndroidEntryPoint
class FloatingWindowService : Service() {

    @Inject
    lateinit var agentRepository: AgentRepository

    private var windowManager: WindowManager? = null
    private var floatView: View? = null
    private lateinit var layoutParams: WindowManager.LayoutParams
    
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val vibrationHelper by lazy { VibrationHelper(this) }
    private val prefs by lazy { getSharedPreferences("floating_window_prefs", MODE_PRIVATE) }
    
    private var isAppInForeground = false
    private var isTaskRunning = false
    
    private val lifecycleCallbacks = object : Application.ActivityLifecycleCallbacks {
        private var activityCount = 0
        
        override fun onActivityStarted(activity: Activity) {
            activityCount++
            if (activityCount == 1) {
                // App entered foreground
                isAppInForeground = true
                updateFloatViewVisibility()
            }
        }
        
        override fun onActivityStopped(activity: Activity) {
            activityCount--
            if (activityCount == 0) {
                // App entered background
                isAppInForeground = false
                updateFloatViewVisibility()
            }
        }
        
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityResumed(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createFloatingView()
        observeAgentState()
        
        // Register lifecycle callbacks
        (application as Application).registerActivityLifecycleCallbacks(lifecycleCallbacks)
    }

    private fun createFloatingView() {
        val imageView = ImageView(this)
        imageView.setImageResource(com.autoglm.autoagent.R.drawable.ic_mic_glass)
        
        // 1. 设置圆型半透明背景
        val background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(android.graphics.Color.parseColor("#99000000")) // 半透明黑色
            setStroke(2, android.graphics.Color.parseColor("#40FFFFFF")) // 微弱白边
        }
        imageView.background = background
        
        // 2. 设置内边距,让图标在圆圈中间
        val padding = dpToPx(12)
        imageView.setPadding(padding, padding, padding, padding)
        
        // 触摸监听逻辑
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false
        val touchSlop = 10 

        imageView.setOnTouchListener { view, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams.x
                    initialY = layoutParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    view.alpha = 0.8f // 按下时稍微变暗
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    
                    if (java.lang.Math.abs(dx) > touchSlop || java.lang.Math.abs(dy) > touchSlop) {
                         isDragging = true
                         layoutParams.x = initialX + dx
                         layoutParams.y = initialY + dy
                         windowManager?.updateViewLayout(floatView, layoutParams)
                    }
                    true
                }
                android.view.MotionEvent.ACTION_UP -> {
                    view.alpha = 1.0f // 抬起恢复
                    if (!isDragging) {
                        view.performClick()
                    } else {
                        // 自动贴边
                        snapToEdge()
                        // 保存位置
                        savePosition()
                    }
                    true
                }
                else -> false
            }
        }

        imageView.setOnClickListener {
            val state = agentRepository.agentState.value
            when (state) {
                is AgentState.Idle, is AgentState.Error -> {
                    Toast.makeText(this, "Listening...", Toast.LENGTH_SHORT).show()
                    agentRepository.setListening(true)
                }
                is AgentState.Listening -> {
                    Toast.makeText(this, "Cancelled", Toast.LENGTH_SHORT).show()
                    agentRepository.setListening(false)
                }
                is AgentState.Paused -> {
                    Toast.makeText(this, "Resuming...", Toast.LENGTH_SHORT).show()
                    agentRepository.resumeAgent()
                }
                // 运行状态可以被该浮窗控制 (Stop)
                is AgentState.Running, is AgentState.Planning -> {
                    Toast.makeText(this, "Stopping (Floating Control)...", Toast.LENGTH_SHORT).show()
                    agentRepository.stopAgent()
                }
            }
        }
        
        // 3. 固定大小 (60dp), 避免过大遮挡
        val size = dpToPx(60)
        
        layoutParams = WindowManager.LayoutParams(
            size,
            size,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            alpha = 1.0f 
            gravity = Gravity.TOP or Gravity.START
            // 恢复上次位置
            x = prefs.getInt("last_x", 0)
            y = prefs.getInt("last_y", 200)
        }
        floatView = imageView
        windowManager?.addView(floatView, layoutParams)
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    private fun observeAgentState() {
        serviceScope.launch {
            agentRepository.agentState.collectLatest { state ->
                val view = floatView as? ImageView ?: return@collectLatest
                
                // Update task running status
                isTaskRunning = state is AgentState.Running || state is AgentState.Planning || state is AgentState.Listening
                updateFloatViewVisibility()
                
                // 4. 状态颜色逻辑
                when (state) {
                    is AgentState.Idle, is AgentState.Error -> {
                        view.setImageResource(com.autoglm.autoagent.R.drawable.ic_mic_glass)
                        view.colorFilter = null // 默认颜色
                        view.background.setTintList(null) // 恢复背景色
                    }
                    is AgentState.Listening -> {
                        view.setImageResource(com.autoglm.autoagent.R.drawable.ic_mic_glass)
                        view.colorFilter = null
                        if (state != lastState) vibrationHelper.vibrateTick()
                    }
                    is AgentState.Paused -> {
                        // 暂停状态 -> 显示播放/继续 -> 绿色
                        view.setImageResource(com.autoglm.autoagent.R.drawable.ic_keyboard_glass) 
                        view.setColorFilter(android.graphics.Color.GREEN, android.graphics.PorterDuff.Mode.SRC_IN)
                    }
                    is AgentState.Running, is AgentState.Planning -> {
                        // 运行状态 -> 显示停止 -> 红色
                        view.setImageResource(com.autoglm.autoagent.R.drawable.ic_stop_glass)
                        view.setColorFilter(android.graphics.Color.RED, android.graphics.PorterDuff.Mode.SRC_IN)
                        if (state != lastState) vibrationHelper.vibrateHeavy()
                    }
                }
                lastState = state
            }
        }
    }
    
    private fun updateFloatViewVisibility() {
        val view = floatView ?: return
        
        // Show if: app in background OR task is running
        // Hide if: app in foreground AND idle
        val shouldShow = !isAppInForeground || isTaskRunning
        
        view.visibility = if (shouldShow) View.VISIBLE else View.GONE
        android.util.Log.d("FloatingWindow", "Visibility: inForeground=$isAppInForeground, taskRunning=$isTaskRunning, show=$shouldShow")
    }

    private var lastState: AgentState? = null

    private fun snapToEdge() {
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val centerX = screenWidth / 2
        
        // 判断是在左边还是右边
        val targetX = if (layoutParams.x + floatView!!.width / 2 < centerX) {
            0 // 吸附到左边
        } else {
            screenWidth - floatView!!.width // 吸附到右边
        }

        // 简单的动画效果 (使用 ValueAnimator)
        val animator = android.animation.ValueAnimator.ofInt(layoutParams.x, targetX)
        animator.duration = 300
        animator.addUpdateListener { animation ->
            layoutParams.x = animation.animatedValue as Int
            windowManager?.updateViewLayout(floatView, layoutParams)
        }
        animator.start()
        
        // 更新 params 为目标位置 (主要用于保存)
        layoutParams.x = targetX
    }

    private fun savePosition() {
        prefs.edit()
            .putInt("last_x", layoutParams.x)
            .putInt("last_y", layoutParams.y)
            .apply()
    }
    
    // 简单的震动帮助类
    class VibrationHelper(private val context: android.content.Context) {
        private val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(android.content.Context.VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager
            manager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(android.content.Context.VIBRATOR_SERVICE) as android.os.Vibrator
        }

        fun vibrateTick() {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    vibrator.vibrate(android.os.VibrationEffect.createPredefined(android.os.VibrationEffect.EFFECT_TICK))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(20)
                }
            } catch (e: SecurityException) {
                android.util.Log.e("FloatingWindow", "振动权限缺失，跳过震动反馈")
            }
        }
        
        fun vibrateHeavy() {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    vibrator.vibrate(android.os.VibrationEffect.createPredefined(android.os.VibrationEffect.EFFECT_CLICK))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(50)
                }
            } catch (e: SecurityException) {
                android.util.Log.e("FloatingWindow", "振动权限缺失，跳过震动反馈")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        if (floatView != null) windowManager?.removeView(floatView)
        
        // Unregister lifecycle callbacks
        (application as? Application)?.unregisterActivityLifecycleCallbacks(lifecycleCallbacks)
    }
}
