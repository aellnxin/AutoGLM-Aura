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
            // 模拟真实滑动：分步插值 ACTION_MOVE
            val steps = 20
            val duration = 300L
            val stepDelay = duration / steps

            // ACTION_DOWN = 0
            var success = connector.injectTouch(displayId, 0, x1.toInt(), y1.toInt())
            if (!success) return@withContext false
            
            // ACTION_MOVE = 2
            for (i in 1..steps) {
                val t = i.toFloat() / steps
                val currentX = x1 + (x2 - x1) * t
                val currentY = y1 + (y2 - y1) * t
                
                success = connector.injectTouch(displayId, 2, currentX.toInt(), currentY.toInt())
                if (!success) break
                kotlinx.coroutines.delay(stepDelay)
            }
            
            // ACTION_UP = 1
            if (success) {
                success = connector.injectTouch(displayId, 1, x2.toInt(), y2.toInt())
            }
            success
        } catch (e: Exception) {
            Log.e(TAG, "Scroll failed", e)
            false
        }
    }
    
    override suspend fun inputText(text: String): Boolean = withContext(Dispatchers.IO) {
        try {
            connector.inputText(displayId, text)
        } catch (e: Exception) {
            Log.e(TAG, "Shell input text failed", e)
            false
        }
    }
    
    override suspend fun pressKey(keyCode: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            connector.injectKey(keyCode)
        } catch (e: Exception) {
            Log.e(TAG, "Key press failed", e)
            false
        }
    }
    
    override suspend fun pressBack(): Boolean = withContext(Dispatchers.IO) {
        try {
            connector.pressBack(displayId)
        } catch (e: Exception) {
            Log.e(TAG, "Back press failed", e)
            false
        }
    }
    
    override suspend fun pressHome(): Boolean = withContext(Dispatchers.IO) {
        try {
            connector.pressHome(displayId)
        } catch (e: Exception) {
            Log.e(TAG, "Home press failed", e)
            false
        }
    }
    
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
 * Shizuku 服务执行失败异常
 * 当 Shizuku 模式下操作失败时抛出，用于中断任务并通知用户
 */
class ShizukuExecutionException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)

/**
 * 执行器管理器（无降级）
 * 根据当前模式（Shell/Accessibility）选择执行器，不做自动降级
 * Shell 模式异常时直接抛出 ShizukuExecutionException
 */
@Singleton
class FallbackActionExecutor @Inject constructor(
    private val connector: ShellServiceConnector,
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "ActionExecutorManager"
    }
    
    private val _currentMode = MutableStateFlow(ExecutionMode.UNAVAILABLE)
    val currentMode: StateFlow<ExecutionMode> = _currentMode
    
    private var shellExecutor: ShellActionExecutor? = null
    private val accessibilityExecutor = AccessibilityActionExecutor()
    
    // 状态变更回调
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
     * 获取当前可用的执行器（不做降级）
     */
    private fun getExecutor(): ActionExecutor? {
        // 如果当前不可用，尝试刷新一次
        if (_currentMode.value == ExecutionMode.UNAVAILABLE) {
            refreshMode()
        }
        
        return when (_currentMode.value) {
            ExecutionMode.SHELL -> shellExecutor
            ExecutionMode.ACCESSIBILITY -> accessibilityExecutor
            ExecutionMode.UNAVAILABLE -> null
        }
    }
    
    /**
     * 执行操作（无降级）
     * Shell 模式失败时抛出 ShizukuExecutionException
     */
    private suspend fun <T> execute(
        operation: String,
        action: suspend (ActionExecutor) -> T,
        failureValue: T
    ): T {
        val executor = getExecutor()
            ?: throw ShizukuExecutionException("$operation failed: No executor available (mode=${_currentMode.value})")
        
        return try {
            val result = action(executor)
            
            // Shell 模式下，操作失败直接抛出异常
            if (result == false && _currentMode.value == ExecutionMode.SHELL) {
                throw ShizukuExecutionException("$operation failed in Shell mode on Display $currentDisplayId")
            }
            
            result
        } catch (e: ShizukuExecutionException) {
            throw e  // 重新抛出
        } catch (e: Exception) {
            if (_currentMode.value == ExecutionMode.SHELL) {
                throw ShizukuExecutionException("$operation exception in Shell mode", e)
            }
            Log.e(TAG, "$operation failed in Accessibility mode", e)
            failureValue
        }
    }
    
    // === 公开操作接口 ===
    
    suspend fun tap(x: Float, y: Float): Boolean = execute("Tap", { it.tap(x, y) }, false)
    
    suspend fun scroll(x1: Float, y1: Float, x2: Float, y2: Float): Boolean = 
        execute("Scroll", { it.scroll(x1, y1, x2, y2) }, false)
    
    suspend fun inputText(text: String): Boolean = execute("InputText", { it.inputText(text) }, false)
    
    suspend fun pressBack(): Boolean = execute("Back", { it.pressBack() }, false)
    
    suspend fun pressHome(): Boolean = execute("Home", { it.pressHome() }, false)
    
    suspend fun longPress(x: Float, y: Float): Boolean = execute("LongPress", { it.longPress(x, y) }, false)
    
    suspend fun doubleTap(x: Float, y: Float): Boolean = execute("DoubleTap", { it.doubleTap(x, y) }, false)
    
    /**
     * 检查是否有任何执行器可用
     */
    fun isAnyExecutorAvailable(): Boolean {
        return shellExecutor?.isAvailable() == true || accessibilityExecutor.isAvailable()
    }
}
