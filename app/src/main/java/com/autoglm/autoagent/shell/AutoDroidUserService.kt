package com.autoglm.autoagent.shell

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.annotation.Keep
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import java.lang.reflect.Method

/**
 * AutoDroid User Service
 * Implementation of IAutoDroidShell running in a privileged process via Shizuku.
 */
@Keep
class AutoDroidUserService(private val context: Context) : IAutoDroidShell.Stub() {
    
    companion object {
        private const val TAG = "AutoDroidUserService"
    }
    
    // Cached reflection
    private var inputManager: Any? = null
    private var injectInputEventMethod: Method? = null
    
    // Virtual Display Management
    private val displayMap = mutableMapOf<Int, android.hardware.display.VirtualDisplay>()
    private val imageReaders = mutableMapOf<Int, android.media.ImageReader>()
    
    init {
        Log.d(TAG, "🚀 AutoDroidUserService initialized with App Context")
        initInputManager()
    }

    override fun ping(): Boolean = true

    override fun injectTouch(displayId: Int, action: Int, x: Int, y: Int): Boolean {
        if (inputManager == null && !initInputManager()) return false
        
        return try {
            val now = SystemClock.uptimeMillis()
            val event = MotionEvent.obtain(now, now, action, x.toFloat(), y.toFloat(), 0).apply {
                source = InputDevice.SOURCE_TOUCHSCREEN
                setDisplayIdCompat(displayId)
            }
            val success = performInjection(event)
            event.recycle()
            success
        } catch (e: Exception) {
            Log.e(TAG, "injectTouch failed", e)
            false
        }
    }

    override fun injectKey(keyCode: Int): Boolean {
        if (inputManager == null && !initInputManager()) return false
        
        return try {
            val now = SystemClock.uptimeMillis()
            val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0)
            val up = KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0)
            performInjection(down) && performInjection(up)
        } catch (e: Exception) {
            Log.e(TAG, "injectKey failed", e)
            false
        }
    }

    override fun inputText(displayId: Int, text: String): Boolean {
        return try {
            // 对空格进行转义，这是 'input text' 命令的要求
            val escapedText = text.replace(" ", "%s")
            val displayArg = if (displayId > 0) "--display $displayId" else ""
            val cmd = "input $displayArg text \"$escapedText\""
            Log.i(TAG, "Executing input text: $cmd")
            val process = runShizukuCommand(cmd)
            process?.waitFor() == 0
        } catch (e: Exception) {
            Log.e(TAG, "inputText failed", e)
            false
        }
    }

    override fun captureScreen(displayId: Int): ByteArray? {
        // 1. Fast Path: ImageReader (Virtual Display)
        imageReaders[displayId]?.let { reader ->
            try {
                reader.acquireLatestImage()?.use { image ->
                    val planes = image.planes
                    val buffer = planes[0].buffer
                    val pixelStride = planes[0].pixelStride
                    val rowStride = planes[0].rowStride
                    val rowPadding = rowStride - pixelStride * reader.width
                    
                    val bitmap = android.graphics.Bitmap.createBitmap(
                        reader.width + rowPadding / pixelStride,
                        reader.height,
                        android.graphics.Bitmap.Config.ARGB_8888
                    )
                    bitmap.copyPixelsFromBuffer(buffer)
                    
                    // Crop padding if necessary
                    val finalBitmap = if (rowPadding == 0) bitmap else 
                        android.graphics.Bitmap.createBitmap(bitmap, 0, 0, reader.width, reader.height)
                    
                    val stream = java.io.ByteArrayOutputStream()
                    finalBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, stream)
                    
                    if (rowPadding != 0) bitmap.recycle()
                    finalBitmap.recycle()
                    
                    return stream.toByteArray()
                }
            } catch (e: Exception) {
                Log.w(TAG, "ImageReader capture failed, falling back to shell", e)
            }
        }

        // 2. Slow Path: Shizuku Shell (Main Display or Fallback)
        return captureScreenViaShell(displayId)
    }

    override fun startActivity(displayId: Int, packageName: String): Boolean {
        // Strategy 1: Shizuku Force Launch (Priority for Background)
        if (displayId > 0) {
            if (launchViaShizuku(displayId, packageName)) return true
        }

        // Strategy 2: PendingIntent (Standard Android API)
        if (launchViaPendingIntent(displayId, packageName)) return true

        // Strategy 3: Raw Shell Fallback
        Log.w(TAG, "Falling back to raw am start...")
        val cmd = "am start --display $displayId $packageName"
        runShizukuCommand(cmd)
        return true
    }

    override fun createVirtualDisplay(name: String, width: Int, height: Int, density: Int): Int {
        try {
            // 边缘修复：创建新屏幕前先释放旧屏幕，防止 ID 累加 (2, 3, 4...)
            val existingIds = displayMap.keys.toList()
            for (id in existingIds) {
                Log.i(TAG, "Releasing existing display $id before creating new one")
                releaseDisplay(id)
            }

            val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
            val imageReader = android.media.ImageReader.newInstance(width, height, android.graphics.PixelFormat.RGBA_8888, 2)
            
            // Flags: 
            // 1: PUBLIC
            // 8: OWN_CONTENT_ONLY (关键：绕过镜像显示所需的 ADD_MIRROR_DISPLAY 权限)
            // 64: TRUSTED (允许在虚拟屏上注入事件)
            val flags = 1 or 8 or 64
            
            val virtualDisplay = displayManager.createVirtualDisplay(
                name, width, height, density, imageReader.surface, flags
            )
            
            if (virtualDisplay != null) {
                val id = virtualDisplay.display.displayId
                Log.i(TAG, "✅ VirtualDisplay created: ID=$id")
                
                displayMap[id] = virtualDisplay
                imageReaders[id] = imageReader
                return id
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create VirtualDisplay", e)
        }
        return -1
    }

    override fun releaseDisplay(displayId: Int) {
        displayMap.remove(displayId)?.release()
        imageReaders.remove(displayId)?.close()
    }

    override fun destroy() {
        displayMap.keys.toList().forEach { releaseDisplay(it) }
    }

    // ==================== Private Helpers ====================

    private fun launchViaShizuku(displayId: Int, packageName: String): Boolean {
        try {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
            val componentName = intent.component?.flattenToShortString() ?: return false
            
            // -S: Force stop (Cold Start)
            // -W: Wait for launch
            // --windowingMode 1: Fullscreen
            val cmd = "am start -n $componentName --display $displayId -S -W --windowingMode 1"
            Log.i(TAG, "Shizuku Launch: $cmd")
            
            val process = runShizukuCommand(cmd)
            return process?.waitFor() == 0
        } catch (e: Exception) {
            Log.e(TAG, "Shizuku launch failed", e)
            return false
        }
    }

    private fun launchViaPendingIntent(displayId: Int, packageName: String): Boolean {
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
            val options = ActivityOptions.makeBasic().apply { launchDisplayId = displayId }
            
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
            
            PendingIntent.getActivity(
                context, 
                packageName.hashCode(), 
                intent, 
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT, 
                options.toBundle()
            ).send()
            
            Log.d(TAG, "PendingIntent launch success")
            true
        } catch (e: Exception) {
            Log.e(TAG, "PendingIntent launch failed", e)
            false
        }
    }

    private fun captureScreenViaShell(displayId: Int): ByteArray? {
        return try {
            val displayArg = if (displayId > 0) "-d $displayId" else ""
            val process = runShizukuCommand("screencap $displayArg -p") ?: return null
            
            val buffer = java.io.ByteArrayOutputStream()
            process.inputStream.copyTo(buffer)
            
            if (process.waitFor() == 0 && buffer.size() > 0) {
                return buffer.toByteArray()
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "Shell capture failed", e)
            null
        }
    }

    private fun runShizukuCommand(command: String): Process? {
        return try {
            val method = getShizukuNewProcessMethod()
            val args = if (method.parameterCount == 3) 
                arrayOf(arrayOf("sh", "-c", command), null, null)
            else 
                arrayOf(arrayOf("sh", "-c", command), null)
            
            method.invoke(null, *args) as Process
        } catch (e: Exception) {
            Log.e(TAG, "Shizuku exec failed", e)
            null
        }
    }
    
    private fun getShizukuNewProcessMethod(): Method {
        return try {
            Shizuku::class.java.getDeclaredMethod(
                "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
            )
        } catch (e: NoSuchMethodException) {
            Shizuku::class.java.getDeclaredMethod(
                "newProcess", Array<String>::class.java, Array<String>::class.java
            )
        }.apply { isAccessible = true }
    }

    private fun initInputManager(): Boolean {
        return try {
            val binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java)
                .invoke(null, "input") as IBinder
            
            val wrapper = ShizukuBinderWrapper(binder)
            val stubClass = Class.forName("android.hardware.input.IInputManager\$Stub")
            inputManager = stubClass.getMethod("asInterface", IBinder::class.java).invoke(null, wrapper)
            
            val imClass = inputManager!!.javaClass
            
            // Try Android 14+ signature first
            injectInputEventMethod = try {
                imClass.getMethod("injectInputEventToTarget", InputEvent::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            } catch (e: NoSuchMethodException) {
                // Fallback to Android 11-13
                imClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
            }
            injectInputEventMethod?.isAccessible = true
            true
        } catch (e: Exception) {
            Log.e(TAG, "InputManager init failed", e)
            false
        }
    }

    private fun performInjection(event: android.view.InputEvent): Boolean {
        val method = injectInputEventMethod ?: return false
        return try {
            if (method.parameterCount == 3) {
                method.invoke(inputManager, event, 2, -1) as Boolean
            } else {
                method.invoke(inputManager, event, 2) as Boolean
            }
        } catch (e: Exception) {
            false
        }
    }
    
    private fun MotionEvent.setDisplayIdCompat(displayId: Int) {
        try {
            MotionEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
                .invoke(this, displayId)
        } catch (e: Exception) {
            // Ignore on older Android versions where this might fail
        }
    }
}