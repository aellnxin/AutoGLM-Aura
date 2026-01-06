package com.autoglm.autoagent.shell

import android.util.Log
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Shell Service Connector (TCP Socket Version)
 * 
 * Connects to TCP Socket on localhost:23456
 * Protocol matches ServerMain.java
 */
@Singleton
class ShellServiceConnector @Inject constructor(
    private val deployer: ShellServiceDeployer
) {
    
    private val token: String
        get() = deployer.getOrGenerateToken()
        
    private val PORT = 23456
    private val HOST = "127.0.0.1"
    private val TIMEOUT_MS = 3000
    
    // Command IDs
    private val CMD_PING = 1
    private val CMD_INJECT_TOUCH = 2
    private val CMD_INJECT_KEY = 3
    private val CMD_CAPTURE_SCREEN = 4
    private val CMD_CREATE_DISPLAY = 5
    private val CMD_RELEASE_DISPLAY = 6
    private val CMD_START_ACTIVITY = 7
    private val CMD_DESTROY = 99

    /**
     * Helper to send command and get response
     */
    private fun <T> sendCommand(
        cmd: Int,
        payloadWriter: (DataOutputStream, DataInputStream) -> T
    ): T? {
        var socket: Socket? = null
        try {
            socket = Socket()
            socket.connect(InetSocketAddress(HOST, PORT), TIMEOUT_MS)
            socket.soTimeout = TIMEOUT_MS
            
            val out = DataOutputStream(socket.outputStream)
            val input = DataInputStream(socket.inputStream)
            
            // 1. Send Token
            val tokenBytes = token.toByteArray(StandardCharsets.UTF_8)
            out.writeInt(tokenBytes.size)
            out.write(tokenBytes)
            out.flush() // Ensure token is sent immediately
            
            // 2. Send Command ID
            out.writeInt(cmd)
            out.flush()
            
            // 3. Read Status
            val status = input.readInt()
            if (status != 1) {
                Log.e(TAG, "Server rejected request (Status: $status)")
                return null
            }
            
            // 4. Send Payload & Read Response
            return payloadWriter(out, input)
            
        } catch (e: Exception) {
            Log.e(TAG, "Socket communication failed", e)
            return null
        } finally {
            try {
                socket?.close()
            } catch (e: Exception) { /* Ignore */ }
        }
    }
    
    /**
     * Test connection to Shell Service
     */
    fun connect(): Boolean {
        return sendCommand(CMD_PING) { _, _ -> true } ?: false
    }

    fun injectTouch(displayId: Int, action: Int, x: Int, y: Int): Boolean {
        return sendCommand(CMD_INJECT_TOUCH) { out, input ->
            out.writeInt(displayId)
            out.writeInt(action)
            out.writeInt(x)
            out.writeInt(y)
            out.flush()
            input.readBoolean()
        } ?: false
    }
    
    fun injectKey(keyCode: Int): Boolean {
        return sendCommand(CMD_INJECT_KEY) { out, input ->
            out.writeInt(keyCode)
            out.flush()
            input.readBoolean()
        } ?: false
    }
    
    fun captureScreen(displayId: Int): String? {
        return sendCommand(CMD_CAPTURE_SCREEN) { out, input ->
            out.writeInt(displayId)
            out.flush()
            val path = input.readUTF()
            if (path.isEmpty()) null else path
        }
    }
    
    /**
     * Create a VirtualDisplay for background execution
     * @return displayId or -1 on failure
     */
    fun createVirtualDisplay(name: String, width: Int, height: Int, density: Int): Int {
        return sendCommand(CMD_CREATE_DISPLAY) { out, input ->
            out.writeUTF(name)
            out.writeInt(width)
            out.writeInt(height)
            out.writeInt(density)
            out.flush()
            input.readInt()
        } ?: -1
    }
    
    /**
     * Release a VirtualDisplay
     */
    fun releaseDisplay(displayId: Int): Boolean {
        return sendCommand(CMD_RELEASE_DISPLAY) { out, input ->
            out.writeInt(displayId)
            out.flush()
            input.readBoolean()
        } ?: false
    }
    
    /**
     * Start an activity on specific display
     */
    fun startActivityOnDisplay(displayId: Int, packageName: String): Boolean {
        return sendCommand(CMD_START_ACTIVITY) { out, input ->
            out.writeInt(displayId)
            out.writeUTF(packageName)
            out.flush()
            input.readBoolean()
        } ?: false
    }
    
    fun destroy() {
        sendCommand(CMD_DESTROY) { _, input ->
            input.readBoolean()
        }
    }
    
    companion object {
        private const val TAG = "ShellServiceConnector"
    }
}
