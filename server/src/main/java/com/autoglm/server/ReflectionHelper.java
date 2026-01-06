package com.autoglm.server;

import android.os.Build;
import android.os.SystemClock;
import android.util.Log;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.MotionEvent;

import java.lang.reflect.Method;

/**
 * Reflection Helper for Hidden APIs
 * Handles API differences across Android 11 - Android 14+
 */
public class ReflectionHelper {
    
    private static final String TAG = "ReflectionHelper";
    
    // Cached reflection objects
    private static Object inputManager;
    private static Method injectInputEventMethod;
    
    /**
     * Initialize InputManager via reflection
     */
    public static boolean initInputManager() {
        try {
            Class<?> inputManagerClass = Class.forName("android.hardware.input.InputManager");
            Method getInstanceMethod = inputManagerClass.getDeclaredMethod("getInstance");
            getInstanceMethod.setAccessible(true);
            inputManager = getInstanceMethod.invoke(null);
            
            if (inputManager == null) {
                Log.e(TAG, "Failed to get InputManager instance");
                return false;
            }
            
            // Try to find injectInputEvent method
            // Android 11-13: injectInputEvent(InputEvent, int)
            // Android 14+:  injectInputEvent(InputEvent, int, int)
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Android 14+ (SDK 34)
                try {
                    injectInputEventMethod = inputManagerClass.getDeclaredMethod(
                        "injectInputEvent",
                        InputEvent.class,
                        int.class,  // mode (INJECT_INPUT_EVENT_MODE_ASYNC)
                        int.class   // targetUid (optional, use -1 for current)
                    );
                    Log.d(TAG, "Using Android 14+ injectInputEvent signature");
                } catch (NoSuchMethodException e) {
                    Log.w(TAG, "Android 14+ method not found, falling back");
                }
            }
            
            if (injectInputEventMethod == null) {
                // Android 11-13 fallback
                injectInputEventMethod = inputManagerClass.getDeclaredMethod(
                    "injectInputEvent",
                    InputEvent.class,
                    int.class  // mode
                );
                Log.d(TAG, "Using Android 11-13 injectInputEvent signature");
            }
            
            injectInputEventMethod.setAccessible(true);
            Log.d(TAG, "✅ InputManager initialized successfully");
            return true;
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize InputManager", e);
            return false;
        }
    }
    
    /**
     * Inject touch event
     * 
     * @param displayId Target display
     * @param action MotionEvent action
     * @param x X coordinate
     * @param y Y coordinate
     * @return true if successful
     */
    public static boolean injectTouch(int displayId, int action, float x, float y) {
        if (inputManager == null || injectInputEventMethod == null) {
            if (!initInputManager()) {
                return false;
            }
        }
        
        try {
            long now = android.os.SystemClock.uptimeMillis();
            
            MotionEvent event = MotionEvent.obtain(
                now,      // downTime
                now,      // eventTime
                action,   // action
                x,        // x
                y,        // y
                0         // metaState
            );
            
            // Set display ID via reflection
            try {
                Method setDisplayIdMethod = MotionEvent.class.getDeclaredMethod("setDisplayId", int.class);
                setDisplayIdMethod.setAccessible(true);
                setDisplayIdMethod.invoke(event, displayId);
            } catch (Exception e) {
                Log.w(TAG, "Failed to set display ID, using default", e);
            }
            
            boolean result;
            
            // MODE_ASYNC = 0
            final int INJECT_INPUT_EVENT_MODE_ASYNC = 0;
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Android 14+: injectInputEvent(event, mode, targetUid)
                result = (boolean) injectInputEventMethod.invoke(
                    inputManager,
                    event,
                    INJECT_INPUT_EVENT_MODE_ASYNC,
                    -1  // targetUid (-1 means current process)
                );
            } else {
                // Android 11-13: injectInputEvent(event, mode)
                result = (boolean) injectInputEventMethod.invoke(
                    inputManager,
                    event,
                    INJECT_INPUT_EVENT_MODE_ASYNC
                );
            }
            
            event.recycle();
            return result;
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to inject touch event", e);
            return false;
        }
    }
    
    /**
     * Inject key event (Back/Home)
     * 
     * @param keyCode KeyEvent code
     * @return true if successful
     */
    public static boolean injectKey(int keyCode) {
        if (inputManager == null || injectInputEventMethod == null) {
            if (!initInputManager()) {
                return false;
            }
        }
        
        try {
            long now = android.os.SystemClock.uptimeMillis();
            
            // Send DOWN event
            KeyEvent downEvent = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0);
            
            // Send UP event
            KeyEvent upEvent = new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0);
            
            final int INJECT_INPUT_EVENT_MODE_ASYNC = 0;
            
            boolean downResult, upResult;
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                downResult = (boolean) injectInputEventMethod.invoke(
                    inputManager, downEvent, INJECT_INPUT_EVENT_MODE_ASYNC, -1
                );
                upResult = (boolean) injectInputEventMethod.invoke(
                    inputManager, upEvent, INJECT_INPUT_EVENT_MODE_ASYNC, -1
                );
            } else {
                downResult = (boolean) injectInputEventMethod.invoke(
                    inputManager, downEvent, INJECT_INPUT_EVENT_MODE_ASYNC
                );
                upResult = (boolean) injectInputEventMethod.invoke(
                    inputManager, upEvent, INJECT_INPUT_EVENT_MODE_ASYNC
                );
            }
            
            return downResult && upResult;
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to inject key event", e);
            return false;
        }
    }
    
    /**
     * Capture screenshot using SurfaceControl (Hidden API)
     * FIX: Save to file instead of returning byte[] to avoid TransactionTooLargeException
     * 
     * @param displayId Target display
     * @return Screenshot file path or null
     */
    public static String captureScreenToFile(int displayId) {
        try {
            String filePath = "/data/local/tmp/autoglm_screenshot_" + 
                System.currentTimeMillis() + ".png";
            
            // Use screencap command for reliability
            String displayArg = displayId > 0 ? "-d " + displayId : "";
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", 
                "screencap " + displayArg + " -p " + filePath);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            int exitCode = process.waitFor();
            
            if (exitCode == 0) {
                Log.d(TAG, "✅ Screenshot saved to: " + filePath);
                return filePath;
            } else {
                Log.e(TAG, "❌ screencap failed with exit code: " + exitCode);
                return null;
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to capture screen", e);
            return null;
        }
    }
    
    // ==================== VirtualDisplay Support ====================
    
    private static Object displayManager;
    private static java.lang.reflect.Method createVirtualDisplayMethod;
    private static java.util.Map<Integer, Object> virtualDisplays = new java.util.concurrent.ConcurrentHashMap<>();
    
    /**
     * Initialize DisplayManager for VirtualDisplay creation
     */
    public static boolean initDisplayManager() {
        try {
            Class<?> dmClass = Class.forName("android.hardware.display.DisplayManagerGlobal");
            java.lang.reflect.Method getInstance = dmClass.getDeclaredMethod("getInstance");
            getInstance.setAccessible(true);
            displayManager = getInstance.invoke(null);
            
            if (displayManager == null) {
                Log.e(TAG, "Failed to get DisplayManagerGlobal instance");
                return false;
            }
            
            Log.d(TAG, "✅ DisplayManagerGlobal initialized");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize DisplayManager", e);
            return false;
        }
    }
    
    /**
     * Create a VirtualDisplay for background execution
     * 
     * @param name Display name
     * @param width Width in pixels
     * @param height Height in pixels
     * @param density DPI
     * @return displayId or -1 on failure
     */
    public static int createVirtualDisplay(String name, int width, int height, int density) {
        try {
            // Use SurfaceControl.createDisplay for shell-level access
            Class<?> surfaceControlClass = Class.forName("android.view.SurfaceControl");
            java.lang.reflect.Method createDisplay = surfaceControlClass.getDeclaredMethod(
                "createDisplay", String.class, boolean.class);
            createDisplay.setAccessible(true);
            
            Object displayToken = createDisplay.invoke(null, name, false);
            
            if (displayToken == null) {
                Log.e(TAG, "Failed to create display token");
                return -1;
            }
            
            // Get next available displayId (simplified: use hashCode)
            int displayId = Math.abs(displayToken.hashCode()) % 10000 + 100;
            virtualDisplays.put(displayId, displayToken);
            
            Log.d(TAG, "✅ VirtualDisplay created: " + name + " (id=" + displayId + ")");
            return displayId;
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to create VirtualDisplay", e);
            return -1;
        }
    }
    
    /**
     * Release a VirtualDisplay
     */
    public static boolean releaseVirtualDisplay(int displayId) {
        try {
            Object displayToken = virtualDisplays.remove(displayId);
            if (displayToken == null) {
                Log.w(TAG, "VirtualDisplay not found: " + displayId);
                return false;
            }
            
            Class<?> surfaceControlClass = Class.forName("android.view.SurfaceControl");
            java.lang.reflect.Method destroyDisplay = surfaceControlClass.getDeclaredMethod(
                "destroyDisplay", android.os.IBinder.class);
            destroyDisplay.setAccessible(true);
            destroyDisplay.invoke(null, displayToken);
            
            Log.d(TAG, "✅ VirtualDisplay released: " + displayId);
            return true;
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to release VirtualDisplay", e);
            return false;
        }
    }
    
    /**
     * Start an activity on specific display
     */
    /**
     * Start an activity on specific display
     * @param displayId Target display
     * @param componentName Package name or Component Name (pkg/.Activity)
     */
    public static boolean startActivityOnDisplay(int displayId, String componentName) {
        try {
            // Flags:
            // FLAG_ACTIVITY_NEW_TASK = 0x10000000
            // FLAG_ACTIVITY_MULTIPLE_TASK = 0x08000000 (Optional, but good for isolation)
            // FLAG_ACTIVITY_LAUNCH_ADJACENT = 0x00001000 (Multi-window support)
            // Combined: 0x18001000 or similar. Let's try standard NEW_TASK first.
            
            // Command: am start -n <component> --display <id> --windowingMode 1 -f 0x10000000
            // --windowingMode 1 = FULLSCREEN
            
            String cmd;
            if (componentName.contains("/")) {
                // It's a component name
                // -S: Force stop the target app before starting it. This ensures it launches on the target display instead of bringing existing task to front on main display.
                // -f 0x10000000: FLAG_ACTIVITY_NEW_TASK
                // --windowingMode 1: FULLSCREEN (on the target display)
                cmd = "am start -n " + componentName + " --display " + displayId + 
                      " --windowingMode 1 -f 0x10000000 -S";
            } else {
                // It's just a package name. Use monkey with display argument (may not work on all versions)
                Log.w(TAG, "Launching by package name on specific display is unstable. Using monkey fallback.");
                cmd = "monkey -p " + componentName + " -c android.intent.category.LAUNCHER 1";
            }
            
            Log.d(TAG, "Executing: " + cmd);
            
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", cmd);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            
            // Read output for debugging
            java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                Log.d(TAG, "am output: " + line);
            }
            
            int exitCode = process.waitFor();
            
            if (exitCode == 0) {
                Log.d(TAG, "✅ Activity started on display " + displayId + ": " + componentName);
                return true;
            } else {
                Log.e(TAG, "❌ Failed to start activity: " + exitCode);
                return false;
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to start activity on display", e);
            return false;
        }
    }
}

