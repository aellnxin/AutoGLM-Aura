package com.autoglm.autoagent.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import com.autoglm.autoagent.shell.IAutoGLMAuraShell
import rikka.shizuku.Shizuku
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Shizuku 管理器
 * 负责检查和管理 Shizuku 状态，并管理与 AutoGLM-AuraUserService 的 Binder 连接。
 */
@Singleton
class ShizukuManager @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context
) {
    private var userService: IAutoGLMAuraShell? = null
    private val _isServiceConnected = MutableStateFlow(false)
    val isServiceConnected = _isServiceConnected.asStateFlow()

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        Log.d("ShizukuManager", "📡 Shizuku Binder received, attempting auto-bind...")
        tryAutoBind()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        Log.w("ShizukuManager", "💀 Shizuku Binder dead, service disconnected")
        _isServiceConnected.value = false
        userService = null
    }

    private val permissionResultListener = Shizuku.OnRequestPermissionResultListener { _, result ->
        if (result == PackageManager.PERMISSION_GRANTED) {
            Log.d("ShizukuManager", "✅ Permission granted via request result, binding...")
            bindService()
        }
    }

    /**
     * 初始化监听器 (由 MainActivity 或 AutoAgentService 调用)
     */
    fun initialize() {
        Log.d("ShizukuManager", "🔍 Initializing Shizuku listeners...")
        try {
            Shizuku.addBinderReceivedListener(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.addRequestPermissionResultListener(permissionResultListener)
            
            // 初次尝试
            tryAutoBind()
        } catch (e: Exception) {
            Log.e("ShizukuManager", "Failed to add Shizuku listeners", e)
        }
    }

    /**
     * 自动尝试绑定逻辑
     */
    private fun tryAutoBind() {
        if (isShizukuRunning() && hasPermission()) {
            bindService()
        }
    }

    /**
     * 确保服务已连接，若未连接则尝试静默重连
     * 注意：bindUserService 是异步的，这里会等待连接完成
     */
    fun ensureConnected(): Boolean {
        // 1. 检查是否已连接
        if (userService != null && isShizukuRunning()) {
            try {
                if (userService?.ping() == true) return true
            } catch (e: Exception) {
                Log.w("ShizukuManager", "Service ping failed, attempting reconnect")
            }
        }
        
        // 2. 尝试绑定
        if (!bindService()) {
            return false
        }
        
        // 3. 等待连接完成 (最多 3 秒)
        val startTime = System.currentTimeMillis()
        val timeout = 3000L
        while (!_isServiceConnected.value && (System.currentTimeMillis() - startTime) < timeout) {
            Thread.sleep(100)
        }
        
        val connected = _isServiceConnected.value
        if (!connected) {
            Log.w("ShizukuManager", "⚠️ Service bind timeout after ${timeout}ms")
        }
        return connected
    }
    
    // Shizuku UserService 参数
    private var userServiceArgs: Shizuku.UserServiceArgs? = null
    
    // ServiceConnection 用于接收服务绑定回调
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.d("ShizukuManager", "✅ UserService connected: $name")
            if (service != null && service.pingBinder()) {
                userService = IAutoGLMAuraShell.Stub.asInterface(service)
                _isServiceConnected.value = true
                
                // 设置死亡监听
                try {
                    service.linkToDeath(deathRecipient, 0)
                } catch (e: Exception) {
                    Log.w("ShizukuManager", "Failed to link death recipient", e)
                }
            } else {
                Log.e("ShizukuManager", "❌ Service binder is null or dead")
                _isServiceConnected.value = false
            }
        }
        
        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w("ShizukuManager", "⚠️ UserService disconnected: $name")
            userService = null
            _isServiceConnected.value = false
        }
    }
    
    // 服务死亡监听
    private val deathRecipient = IBinder.DeathRecipient {
        Log.e("ShizukuManager", "💀 UserService process died!")
        userService = null
        _isServiceConnected.value = false
    }
    
    /**
     * 绑定 Shizuku UserService（运行在 Shizuku 进程中）
     */
    fun bindService(): Boolean {
        if (!hasPermission()) {
            Log.e("ShizukuManager", "Cannot bind: No Shizuku permission")
            return false
        }
        
        return try {
            Log.d("ShizukuManager", "🚀 Binding UserService in Shizuku process...")
            
            // 创建 UserService 参数
            userServiceArgs = Shizuku.UserServiceArgs(
                ComponentName(context, com.autoglm.autoagent.shell.AutoGLMAuraUserService::class.java)
            )
                .daemon(false)  // 非守护进程，app 退出时服务也退出
                .processNameSuffix("shell")  // 进程名后缀
                .debuggable(android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O && android.os.StrictMode.allowThreadDiskReads() != null)
                .version(1)  // 版本号，更新服务时增加
            
            // 绑定服务
            Shizuku.bindUserService(userServiceArgs!!, serviceConnection)
            Log.d("ShizukuManager", "📡 UserService bind request sent")
            true
        } catch (e: Exception) {
            Log.e("ShizukuManager", "Failed to bind UserService", e)
            _isServiceConnected.value = false
            false
        }
    }

    /**
     * 断开连接
     */
    fun unbindService() {
        try {
            userServiceArgs?.let { args ->
                Shizuku.unbindUserService(args, serviceConnection, true)
            }
        } catch (e: Exception) {
            Log.w("ShizukuManager", "Error unbinding service", e)
        }
        userService = null
        userServiceArgs = null
        _isServiceConnected.value = false
        Log.d("ShizukuManager", "Disconnected from UserService")
    }

    /**
     * 获取当前服务接口
     */
    fun getService(): IAutoGLMAuraShell? = userService

    /**
     * 检查 Shizuku 是否已安装
     */
    fun isShizukuInstalled(): Boolean {
        return try {
            context.packageManager.getPackageInfo(
                "moe.shizuku.privileged.api",
                0
            )
            true
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * 检查 Shizuku 服务是否正在运行
     */
    fun isShizukuRunning(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (e: Exception) {
            Log.e("ShizukuManager", "Shizuku not running", e)
            false
        }
    }
    
    /**
     * 检查是否已授权
     */
    fun hasPermission(): Boolean {
        if (!isShizukuRunning()) return false
        
        return try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            Log.e("ShizukuManager", "Permission check failed", e)
            false
        }
    }
    
    /**
     * 请求 Shizuku 权限
     */
    fun requestPermission() {
        if (!isShizukuRunning()) {
            Log.w("ShizukuManager", "Cannot request permission: Shizuku not running")
            return
        }
        
        if (hasPermission()) {
            Log.d("ShizukuManager", "Already has permission")
            return
        }
        
        try {
            Shizuku.requestPermission(REQUEST_CODE_SHIZUKU)
            Log.d("ShizukuManager", "Permission requested")
        } catch (e: Exception) {
            Log.e("ShizukuManager", "Failed to request permission", e)
        }
    }
    
    /**
     * 获取激活状态
     */
    fun getActivationStatus(): ActivationStatus {
        return when {
            !isShizukuInstalled() -> ActivationStatus.NOT_INSTALLED
            !isShizukuRunning() -> ActivationStatus.NOT_RUNNING
            !hasPermission() -> ActivationStatus.NO_PERMISSION
            else -> ActivationStatus.ACTIVATED
        }
    }
    
    /**
     * 通过 Shizuku 执行 Shell 命令 (保留作为通用用途)
     */
    fun runCommand(command: String): Boolean {
        // [Safety] 禁止自杀：防止命令误杀自己
        if (command.contains("force-stop") && command.contains("com.autoglm.autoagent")) {
            Log.e("ShizukuManager", "⛔ 拦截自杀命令: $command")
            return false
        }

        if (!hasPermission()) {
            Log.e("ShizukuManager", "Cannot run command: No permission")
            return false
        }
        
        Log.d("ShizukuManager", "📝 Executing command: $command")
        
        return try {
            val newProcessMethod = try {
                Shizuku::class.java.getDeclaredMethod(
                    "newProcess",
                    Array<String>::class.java,
                    Array<String>::class.java,
                    String::class.java
                )
            } catch (e: NoSuchMethodException) {
                Shizuku::class.java.getDeclaredMethod(
                    "newProcess",
                    Array<String>::class.java,
                    Array<String>::class.java
                )
            }
            newProcessMethod.isAccessible = true
            
            val process = if (newProcessMethod.parameterCount == 3) {
                newProcessMethod.invoke(null, arrayOf("sh", "-c", command), null, null)
            } else {
                newProcessMethod.invoke(null, arrayOf("sh", "-c", command), null)
            } as Process
            
            val exitCode = process.waitFor()
            val success = exitCode == 0
            
            if (success) {
                Log.d("ShizukuManager", "✅ Command succeeded (exit=$exitCode): $command")
            } else {
                val error = process.errorStream.bufferedReader().readText()
                Log.w("ShizukuManager", "❌ Command failed (exit=$exitCode): $command\nError: $error")
            }
            
            success
        } catch (e: Exception) {
            Log.e("ShizukuManager", "❌ Failed to run command via Shizuku: ${e.message}", e)
            false
        }
    }
    
    companion object {
        private const val REQUEST_CODE_SHIZUKU = 1001
    }
}

/**
 * Shizuku 激活状态
 */
enum class ActivationStatus {
    NOT_INSTALLED,  // 未安装 Shizuku
    NOT_RUNNING,    // Shizuku 未运行
    NO_PERMISSION,  // 未授权
    ACTIVATED       // 已激活
}
