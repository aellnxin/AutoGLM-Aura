package com.autoglm.autoagent.shell

import android.util.Log
import com.autoglm.autoagent.shizuku.ShizukuManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Shell Service Connector (AIDL Version)
 * 
 * Communicates with AutoDroidUserService via AIDL.
 */
@Singleton
class ShellServiceConnector @Inject constructor(
    private val shizukuManager: ShizukuManager
) {
    private val TAG = "ShellServiceConnector"

    private fun getService(): IAutoDroidShell? {
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
