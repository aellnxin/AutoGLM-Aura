package com.autoglm.autoagent.shell

import android.util.Log
import com.autoglm.autoagent.shizuku.ShizukuManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Shell Service Connector (AIDL Version)
 * 
 * Communicates with AutoGLM-AuraUserService via AIDL.
 */
@Singleton
class ShellServiceConnector @Inject constructor(
    private val shizukuManager: ShizukuManager
) {
    private val TAG = "ShellServiceConnector"

    private fun getService(): IAutoGLMAuraShell? {
        val service = shizukuManager.getService()
        if (service == null) {
            Log.w(TAG, "Shell Service not connected")
        }
        return service
    }
    
    /**
     * Test connection to Shell Service
     */
    fun connect(): Boolean {
        return try {
            getService()?.ping() ?: false
        } catch (e: Exception) {
            Log.e(TAG, "ping failed", e)
            false
        }
    }

    /**
     * Ensure connection is established (re-bind if necessary)
     */
    fun ensureConnection(): Boolean {
        return shizukuManager.ensureConnected()
    }

    fun injectTouch(displayId: Int, action: Int, x: Int, y: Int): Boolean {
        return try {
            getService()?.injectTouch(displayId, action, x, y) ?: false
        } catch (e: Exception) {
            Log.e(TAG, "injectTouch failed", e)
            false
        }
    }
    
    fun injectKey(keyCode: Int): Boolean {
        return try {
            getService()?.injectKey(keyCode) ?: false
        } catch (e: Exception) {
            Log.e(TAG, "injectKey failed", e)
            false
        }
    }
    
    /**
     * 在指定 displayId 上按键（用于虚拟屏幕后台执行）
     */
    fun pressKeyOnDisplay(displayId: Int, keyCode: Int): Boolean {
        if (displayId <= 0) {
            return injectKey(keyCode)
        }
        
        return try {
            // 使用 shell 命令发送按键到指定屏幕
            val service = getService() ?: return false
            val cmd = "input --display $displayId keyevent $keyCode"
            Log.d(TAG, "Executing: $cmd")
            
            // 通过 Shizuku 执行 shell 命令
            val method = try {
                rikka.shizuku.Shizuku::class.java.getDeclaredMethod(
                    "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
                )
            } catch (e: NoSuchMethodException) {
                rikka.shizuku.Shizuku::class.java.getDeclaredMethod(
                    "newProcess", Array<String>::class.java, Array<String>::class.java
                )
            }.apply { isAccessible = true }
            
            val process = if (method.parameterCount == 3) {
                method.invoke(null, arrayOf("sh", "-c", cmd), null, null)
            } else {
                method.invoke(null, arrayOf("sh", "-c", cmd), null)
            } as Process
            
            process.waitFor() == 0
        } catch (e: Exception) {
            Log.e(TAG, "pressKeyOnDisplay failed", e)
            false
        }
    }

    /**
     * Press Home key (KEYCODE_HOME = 3)
     */
    fun pressHome(displayId: Int = 0): Boolean = pressKeyOnDisplay(displayId, 3)

    /**
     * Press Back key (KEYCODE_BACK = 4)
     */
    fun pressBack(displayId: Int = 0): Boolean = pressKeyOnDisplay(displayId, 4)

    fun inputText(displayId: Int, text: String): Boolean {
        return try {
            getService()?.inputText(displayId, text) ?: false
        } catch (e: Exception) {
            Log.e(TAG, "inputText failed", e)
            false
        }
    }
    
    fun captureScreen(displayId: Int): ByteArray? {
        return try {
            getService()?.captureScreen(displayId)
        } catch (e: Exception) {
            Log.e(TAG, "captureScreen failed", e)
            null
        }
    }
    
    /**
     * Create a VirtualDisplay for background execution
     */
    fun createVirtualDisplay(name: String, width: Int, height: Int, density: Int): Int {
        return try {
            getService()?.createVirtualDisplay(name, width, height, density) ?: -1
        } catch (e: Exception) {
            Log.e(TAG, "createVirtualDisplay failed", e)
            -1
        }
    }
    
    /**
     * Release a VirtualDisplay
     */
    fun releaseDisplay(displayId: Int): Boolean {
        return try {
            getService()?.releaseDisplay(displayId)
            true
        } catch (e: Exception) {
            Log.e(TAG, "releaseDisplay failed", e)
            false
        }
    }
    
    /**
     * Start an activity on specific display
     */
    fun startActivityOnDisplay(displayId: Int, packageName: String): Boolean {
        return try {
            getService()?.startActivity(displayId, packageName) ?: false
        } catch (e: Exception) {
            Log.e(TAG, "startActivity failed", e)
            false
        }
    }
    
    fun destroy() {
        try {
            getService()?.destroy()
        } catch (e: Exception) {
            Log.e(TAG, "destroy failed", e)
        }
    }
}
