package com.autoglm.autoagent.executor

import android.util.Log
import com.autoglm.autoagent.service.AutoAgentService
import com.autoglm.autoagent.shell.ShellServiceConnector
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import android.content.Context
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 执行模式
 */
enum class ExecutionMode {
    SHELL,          // Shell 服务模式 (ADB 激活)
    ACCESSIBILITY,  // 无障碍服务模式
    UNAVAILABLE     // 无服务可用
}

/**
 * 操作执行接口
 */
interface ActionExecutor {
    suspend fun tap(x: Float, y: Float): Boolean
    suspend fun scroll(x1: Float, y1: Float, x2: Float, y2: Float): Boolean
    suspend fun inputText(text: String): Boolean
    suspend fun pressKey(keyCode: Int): Boolean
    suspend fun pressBack(): Boolean
    suspend fun pressHome(): Boolean
    suspend fun longPress(x: Float, y: Float): Boolean
    suspend fun doubleTap(x: Float, y: Float): Boolean
    fun isAvailable(): Boolean
}

/**
 * Shell 服务执行器
 */
class ShellActionExecutor(
    private val connector: ShellServiceConnector,
    private val screenWidth: Int,
    private val screenHeight: Int,
    private var displayId: Int = 0  // 默认主屏幕，可设置为 VirtualDisplay
) : ActionExecutor {
    
    companion object {
        private const val TAG = "ShellActionExecutor"
    }
    
    /** 设置目标 displayId（用于 VirtualDisplay） */
    fun setDisplayId(id: Int) {
        displayId = id
    }
    
    override suspend fun tap(x: Float, y: Float): Boolean = withContext(Dispatchers.IO) {
        try {
            // MotionEvent.ACTION_DOWN = 0, ACTION_UP = 1
            val down = connector.injectTouch(displayId, 0, x.toInt(), y.toInt())
            val up = connector.injectTouch(displayId, 1, x.toInt(), y.toInt())
            down && up
        } catch (e: Exception) {
            Log.e(TAG, "Tap failed", e)
            false
        }
    }
    
    override suspend fun scroll(x1: Float, y1: Float, x2: Float, y2: Float): Boolean = withContext(Dispatchers.IO) {
        try {
            // 模拟滑动：ACTION_DOWN -> ACTION_MOVE -> ACTION_UP
            val down = connector.injectTouch(displayId, 0, x1.toInt(), y1.toInt())
            val move = connector.injectTouch(displayId, 2, x2.toInt(), y2.toInt()) // ACTION_MOVE = 2
            val up = connector.injectTouch(displayId, 1, x2.toInt(), y2.toInt())
            down && move && up
        } catch (e: Exception) {
            Log.e(TAG, "Scroll failed", e)
            false
        }
    }
    
    override suspend fun inputText(text: String): Boolean = withContext(Dispatchers.IO) {
        // Shell 服务目前不支持文本输入，返回 false 以触发降级
        Log.w(TAG, "Shell service does not support text input, fallback needed")
        false
    }
    
    override suspend fun pressKey(keyCode: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            connector.injectKey(keyCode)
        } catch (e: Exception) {
            Log.e(TAG, "Key press failed", e)
            false
        }
    }
    
    override suspend fun pressBack(): Boolean = pressKey(4) // KEYCODE_BACK
    
    override suspend fun pressHome(): Boolean = pressKey(3) // KEYCODE_HOME
    
    override suspend fun longPress(x: Float, y: Float): Boolean = withContext(Dispatchers.IO) {
        try {
            // 长按：保持 DOWN 状态一段时间后 UP
            val down = connector.injectTouch(displayId, 0, x.toInt(), y.toInt())
            kotlinx.coroutines.delay(800)
            val up = connector.injectTouch(displayId, 1, x.toInt(), y.toInt())
            down && up
        } catch (e: Exception) {
            Log.e(TAG, "Long press failed", e)
            false
        }
    }
    
    override suspend fun doubleTap(x: Float, y: Float): Boolean = withContext(Dispatchers.IO) {
        try {
            tap(x, y)
            kotlinx.coroutines.delay(100)
            tap(x, y)
        } catch (e: Exception) {
            Log.e(TAG, "Double tap failed", e)
            false
        }
    }
    
    override fun isAvailable(): Boolean {
        return try {
            connector.connect()
        } catch (e: Exception) {
            false
        }
    }
}

/**
 * 无障碍服务执行器
 */
class AccessibilityActionExecutor : ActionExecutor {
    
    companion object {
        private const val TAG = "AccessibilityExecutor"
    }
    
    private val service: AutoAgentService?
        get() = AutoAgentService.instance
    
    override suspend fun tap(x: Float, y: Float): Boolean {
        return service?.click(x, y) ?: false
    }
    
    override suspend fun scroll(x1: Float, y1: Float, x2: Float, y2: Float): Boolean {
        return service?.scroll(x1, y1, x2, y2) ?: false
    }
    
    override suspend fun inputText(text: String): Boolean {
        // 优先尝试 IME，其次无障碍服务
        val imeSuccess = com.autoglm.autoagent.service.AgentInputMethodService.instance?.inputText(text) ?: false
        if (imeSuccess) return true
        return service?.inputText(text) ?: false
    }
    
    override suspend fun pressKey(keyCode: Int): Boolean {
        // 无障碍服务不支持任意按键，只支持全局操作
        return false
    }
    
    override suspend fun pressBack(): Boolean {
        return service?.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK) ?: false
    }
    
    override suspend fun pressHome(): Boolean {
        return service?.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME) ?: false
    }
    
    override suspend fun longPress(x: Float, y: Float): Boolean {
        return service?.longPress(x, y) ?: false
    }
    
    override suspend fun doubleTap(x: Float, y: Float): Boolean {
        return service?.doubleTap(x, y) ?: false
    }
    
    override fun isAvailable(): Boolean {
        return service != null
    }
}

/**
 * 降级执行器管理器
 * 优先使用 Shell 服务，失败时自动降级到无障碍服务
 */
@Singleton
class FallbackActionExecutor @Inject constructor(
    private val connector: ShellServiceConnector,
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "FallbackActionExecutor"
    }
    
    private val _currentMode = MutableStateFlow(ExecutionMode.UNAVAILABLE)
    val currentMode: StateFlow<ExecutionMode> = _currentMode
    
    private var shellExecutor: ShellActionExecutor? = null
    private val accessibilityExecutor = AccessibilityActionExecutor()
    
    // 状态栏通知回调
    var onModeChanged: ((ExecutionMode, ExecutionMode) -> Unit)? = null
    
    // VirtualDisplay 支持
    private var currentDisplayId: Int = 0
    
    /**
     * 初始化并检测可用的执行器
     */
    fun initialize(screenWidth: Int, screenHeight: Int) {
        shellExecutor = ShellActionExecutor(connector, screenWidth, screenHeight)
        refreshMode()
    }
    
    /**
     * 设置目标 displayId（用于 VirtualDisplay 后台执行）
     */
    fun setDisplayId(displayId: Int) {
        currentDisplayId = displayId
        shellExecutor?.setDisplayId(displayId)
        Log.i(TAG, "DisplayId set to: $displayId")
    }
    
    /**
     * 获取当前 displayId
     */
    fun getDisplayId(): Int = currentDisplayId
    
    /**
     * 刷新当前模式
     */
    fun refreshMode(): ExecutionMode {
        val previousMode = _currentMode.value
        
        val newMode = when {
            shellExecutor?.isAvailable() == true -> ExecutionMode.SHELL
            accessibilityExecutor.isAvailable() -> ExecutionMode.ACCESSIBILITY
            else -> ExecutionMode.UNAVAILABLE
        }
        
        if (newMode != previousMode) {
            Log.i(TAG, "Mode changed: $previousMode -> $newMode")
            _currentMode.value = newMode
            onModeChanged?.invoke(previousMode, newMode)
        }
        
        return newMode
    }
    
    /**
     * 获取当前可用的执行器
     */
    private fun getExecutor(): ActionExecutor? {
        return when (_currentMode.value) {
            ExecutionMode.SHELL -> shellExecutor
            ExecutionMode.ACCESSIBILITY -> accessibilityExecutor
            ExecutionMode.UNAVAILABLE -> null
        }
    }
    
    /**
     * 执行操作，失败时自动降级
     */
    private suspend fun <T> executeWithFallback(
        operation: String,
        action: suspend (ActionExecutor) -> T,
        fallbackValue: T
    ): T {
        val executor = getExecutor()
        if (executor == null) {
            Log.e(TAG, "$operation failed: No executor available")
            return fallbackValue
        }
        
        return try {
            val result = action(executor)
            
            // 如果 Shell 模式操作失败，尝试降级
            if (result == false && _currentMode.value == ExecutionMode.SHELL) {
                Log.w(TAG, "$operation failed in Shell mode, trying fallback")
                
                if (accessibilityExecutor.isAvailable()) {
                    val previousMode = _currentMode.value
                    _currentMode.value = ExecutionMode.ACCESSIBILITY
                    onModeChanged?.invoke(previousMode, ExecutionMode.ACCESSIBILITY)
                    
                    @Suppress("UNCHECKED_CAST")
                    return action(accessibilityExecutor)
                }
            }
            
            result
        } catch (e: Exception) {
            Log.e(TAG, "$operation exception, trying fallback", e)
            
            // 异常时尝试降级
            if (_currentMode.value == ExecutionMode.SHELL && accessibilityExecutor.isAvailable()) {
                val previousMode = _currentMode.value
                _currentMode.value = ExecutionMode.ACCESSIBILITY
                onModeChanged?.invoke(previousMode, ExecutionMode.ACCESSIBILITY)
                
                return try {
                    action(accessibilityExecutor)
                } catch (e2: Exception) {
                    Log.e(TAG, "$operation fallback also failed", e2)
                    fallbackValue
                }
            }
            
            fallbackValue
        }
    }
    
    // === 公开操作接口 ===
    
    suspend fun tap(x: Float, y: Float): Boolean = executeWithFallback("Tap", { it.tap(x, y) }, false)
    
    suspend fun scroll(x1: Float, y1: Float, x2: Float, y2: Float): Boolean = 
        executeWithFallback("Scroll", { it.scroll(x1, y1, x2, y2) }, false)
    
    suspend fun inputText(text: String): Boolean = executeWithFallback("InputText", { it.inputText(text) }, false)
    
    suspend fun pressBack(): Boolean = executeWithFallback("Back", { it.pressBack() }, false)
    
    suspend fun pressHome(): Boolean = executeWithFallback("Home", { it.pressHome() }, false)
    
    suspend fun longPress(x: Float, y: Float): Boolean = executeWithFallback("LongPress", { it.longPress(x, y) }, false)
    
    suspend fun doubleTap(x: Float, y: Float): Boolean = executeWithFallback("DoubleTap", { it.doubleTap(x, y) }, false)
    
    /**
     * 检查是否有任何执行器可用
     */
    fun isAnyExecutorAvailable(): Boolean {
        return shellExecutor?.isAvailable() == true || accessibilityExecutor.isAvailable()
    }
}
