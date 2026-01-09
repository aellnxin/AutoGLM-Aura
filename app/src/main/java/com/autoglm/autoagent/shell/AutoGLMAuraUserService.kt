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
import java.lang.reflect.Method

/**
 * AutoGLM-Aura User Service
 * 运行在 Shizuku 进程中的特权服务。
 * 
 * 注意：此服务通过 Shizuku.bindUserService() 启动，运行在独立的 Shizuku 进程中，
 * 拥有系统级权限，可以进行输入注入、虚拟屏幕管理等操作。
 */
@Keep
class AutoGLMAuraUserService() : IAutoGLMAuraShell.Stub() {
    
    companion object {
        private const val TAG = "AutoGLMAuraUserService"
    }
    
    // Shizuku 进程中获取系统上下文（多种回退方案）
    private val context: Context by lazy {
        // 方法1：ActivityThread.currentApplication() - 最常见
        try {
            val activityThread = Class.forName("android.app.ActivityThread")
            val currentApplication = activityThread.getMethod("currentApplication")
            val app = currentApplication.invoke(null) as? Context
            if (app != null) {
                Log.d(TAG, "✅ Context obtained via currentApplication()")
                return@lazy app
            }
        } catch (e: Exception) {
            Log.w(TAG, "currentApplication() failed", e)
        }
        
        // 方法2：ActivityThread.systemMain().getSystemContext() - 适用于系统进程
        try {
            val activityThread = Class.forName("android.app.ActivityThread")
            val systemMain = activityThread.getMethod("systemMain")
            val at = systemMain.invoke(null)
            val getSystemContext = activityThread.getMethod("getSystemContext")
            val ctx = getSystemContext.invoke(at) as? Context
            if (ctx != null) {
                Log.d(TAG, "✅ Context obtained via systemMain().getSystemContext()")
                return@lazy ctx
            }
        } catch (e: Exception) {
            Log.w(TAG, "systemMain().getSystemContext() failed", e)
        }
        
        // 方法3：创建 ContextImpl (最后手段)
        try {
            val contextImplClass = Class.forName("android.app.ContextImpl")
            val createSystemContext = contextImplClass.getDeclaredMethod(
                "createSystemContext",
                Class.forName("android.app.ActivityThread")
            )
            createSystemContext.isAccessible = true
            
            val activityThread = Class.forName("android.app.ActivityThread")
            val systemMain = activityThread.getMethod("systemMain")
            val at = systemMain.invoke(null)
            
            val ctx = createSystemContext.invoke(null, at) as? Context
            if (ctx != null) {
                Log.d(TAG, "✅ Context obtained via ContextImpl.createSystemContext()")
                return@lazy ctx
            }
        } catch (e: Exception) {
            Log.w(TAG, "ContextImpl.createSystemContext() failed", e)
        }
        
        throw IllegalStateException("Cannot obtain context in Shizuku process - all methods failed")
    }
    
    // Cached reflection
    private var inputManager: Any? = null
    private var injectInputEventMethod: Method? = null
    
    // Virtual Display Management
    private val displayMap = mutableMapOf<Int, android.hardware.display.VirtualDisplay>()
    private val imageReaders = mutableMapOf<Int, android.media.ImageReader>()
    
    init {
        Log.d(TAG, "🚀 AutoGLMAuraUserService initialized in Shizuku process")
        initInputManager()
    }

    override fun ping(): Boolean = true

    override fun injectTouch(displayId: Int, action: Int, x: Int, y: Int): Boolean {
        // 对于虚拟屏幕，优先尝试 shell 命令（更可靠）
        if (displayId > 0 && action == 1) { // ACTION_UP 时执行 tap
            return try {
                val cmd = "input --display $displayId tap $x $y"
                Log.d(TAG, "Virtual display tap via shell: $cmd")
                val process = runShizukuCommand(cmd)
                val result = process?.waitFor() == 0
                if (result) {
                    Log.i(TAG, "✅ Shell tap succeeded on display $displayId")
                } else {
                    Log.w(TAG, "❌ Shell tap failed on display $displayId")
                }
                result
            } catch (e: Exception) {
                Log.e(TAG, "Shell tap failed", e)
                // 回退到 InputManager
                injectTouchViaInputManager(displayId, action, x, y)
            }
        }
        
        // 对于 ACTION_DOWN 或主屏幕，使用 InputManager
        if (displayId > 0 && action == 0) {
            // 虚拟屏幕的 ACTION_DOWN 跳过（shell tap 会处理 down+up）
            return true
        }
        
        return injectTouchViaInputManager(displayId, action, x, y)
    }
    
    private fun injectTouchViaInputManager(displayId: Int, action: Int, x: Int, y: Int): Boolean {
        if (inputManager == null && !initInputManager()) return false
        
        return try {
            val now = SystemClock.uptimeMillis()
            val event = MotionEvent.obtain(now, now, action, x.toFloat(), y.toFloat(), 0).apply {
                source = InputDevice.SOURCE_TOUCHSCREEN
                val displaySet = setDisplayIdCompat(displayId)
                if (!displaySet && displayId > 0) {
                    Log.w(TAG, "⚠️ Failed to set displayId $displayId, touch may go to wrong display!")
                }
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
                // 先清理旧的缓冲区，防止 "maxImages already acquired" 错误
                var image = reader.acquireLatestImage()
                if (image == null) {
                    // 没有新帧可用
                    return@let
                }
                
                try {
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
                } finally {
                    // 确保 Image 被关闭
                    image.close()
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
            
            // 创建 Callback 来正确管理虚拟屏幕生命周期
            val callback = object : android.hardware.display.VirtualDisplay.Callback() {
                override fun onPaused() {
                    Log.w(TAG, "⚠️ VirtualDisplay paused")
                }
                override fun onResumed() {
                    Log.i(TAG, "✅ VirtualDisplay resumed")
                }
                override fun onStopped() {
                    Log.e(TAG, "❌ VirtualDisplay stopped by system!")
                    // 当系统停止虚拟屏幕时，从 map 中移除
                    // 注意：这里不调用 releaseDisplay，因为系统已经在停止了
                }
            }
            
            // 使用带 Callback 的 API 创建虚拟屏幕
            val virtualDisplay = displayManager.createVirtualDisplay(
                name, width, height, density, imageReader.surface, flags,
                callback, android.os.Handler(android.os.Looper.getMainLooper())
            )
            
            if (virtualDisplay != null) {
                val id = virtualDisplay.display.displayId
                Log.i(TAG, "✅ VirtualDisplay created with Callback: ID=$id")
                
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
            // --display: Target display
            // -f 0x10008000: FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK
            val cmd = "am start -n $componentName --display $displayId -S -W -f 0x10008000 --windowingMode 1"
            Log.i(TAG, "Shizuku Force Launch: $cmd")
            
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
            // 注意：服务运行在 Shizuku 进程中，已经拥有系统权限
            // 不需要 ShizukuBinderWrapper，直接使用 Binder 即可
            val binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java)
                .invoke(null, "input") as IBinder
            
            // 直接使用 binder，不需要 wrapper（我们已经在 Shizuku 进程中）
            val stubClass = Class.forName("android.hardware.input.IInputManager\$Stub")
            inputManager = stubClass.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
            
            val imClass = inputManager!!.javaClass
            
            // Try Android 14+ signature first (injectInputEventToTarget)
            injectInputEventMethod = try {
                imClass.getMethod("injectInputEventToTarget", InputEvent::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            } catch (e: NoSuchMethodException) {
                // Fallback to Android 11-13 (injectInputEvent)
                imClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
            }
            injectInputEventMethod?.isAccessible = true
            
            Log.d(TAG, "✅ InputManager initialized (method: ${injectInputEventMethod?.name})")
            true
        } catch (e: Exception) {
            Log.e(TAG, "❌ InputManager init failed", e)
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
    
    private fun MotionEvent.setDisplayIdCompat(displayId: Int): Boolean {
        return try {
            val method = MotionEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
            method.invoke(this, displayId)
            
            // 验证设置是否成功
            val getMethod = MotionEvent::class.java.getMethod("getDisplayId")
            val actualId = getMethod.invoke(this) as Int
            if (actualId != displayId) {
                Log.e(TAG, "❌ setDisplayId failed: expected $displayId, got $actualId")
                false
            } else {
                Log.d(TAG, "✅ MotionEvent displayId set to $displayId")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ setDisplayIdCompat exception for display $displayId", e)
            false
        }
    }
}