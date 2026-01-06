package com.autoglm.server;

import android.os.Build;
import android.util.Log;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.nio.charset.StandardCharsets;

/**
 * Server Entry Point (TCP Socket Version)
 * 
 * Uses TCP Socket on localhost to avoid SELinux restrictions.
 * Protocol:
 * [Token Length (Int)] [Token (Bytes)] [Command ID (Int)] [Payload...]
 */
public class ServerMain {
    
    private static final String TAG = "AutoDroid-Server";
    private static final int PORT = 23456; // Localhost port
    private static final int VERSION = 2;
    
    private static String securityToken;
    private static boolean isRunning = true;
    
    // Command IDs
    private static final int CMD_PING = 1;
    private static final int CMD_INJECT_TOUCH = 2;
    private static final int CMD_INJECT_KEY = 3;
    private static final int CMD_CAPTURE_SCREEN = 4;
    private static final int CMD_CREATE_DISPLAY = 5;
    private static final int CMD_RELEASE_DISPLAY = 6;
    private static final int CMD_START_ACTIVITY = 7;
    private static final int CMD_DESTROY = 99;
    
    public static void main(String[] args) {
        System.out.println("🚀 [Server] Starting (TCP)...");
        
        try {
            Log.d(TAG, "🚀 AutoDroid Server (TCP) starting...");
            
            // Parse security token
            if (args.length > 0) {
                securityToken = args[0];
                System.out.println("✅ [Server] Token received: " + securityToken.substring(0, 5) + "...");
            } else {
                System.err.println("❌ [Server] No token provided!");
                Log.e(TAG, "❌ No security token provided!");
                System.exit(1);
            }
            
            System.out.println("🔄 [Server] Initializing InputManager...");
            if (!ReflectionHelper.initInputManager()) {
                System.err.println("⚠️ [Server] InputManager init failed");
                Log.w(TAG, "⚠️ InputManager init failed");
            }
            
            System.out.println("🔄 [Server] Creating TCP Socket on port " + PORT + "...");
            try (ServerSocket serverSocket = new ServerSocket(PORT, 5, java.net.InetAddress.getByName("127.0.0.1"))) {
                System.out.println("✅ [Server] Listening on localhost:" + PORT);
                Log.d(TAG, "✅ Listening on localhost:" + PORT);
                
                ExecutorService executor = Executors.newCachedThreadPool();
                
                System.out.println("✨ [Server] Ready and waiting for connections...");
                while (isRunning) {
                    try {
                        Socket client = serverSocket.accept();
                        System.out.println("🔗 [Server] Client connected from " + client.getInetAddress());
                        Log.d(TAG, "🔗 Client connected");
                        executor.submit(() -> handleClient(client));
                    } catch (IOException e) {
                        if (isRunning) {
                            System.err.println("❌ [Server] Accept failed: " + e.getMessage());
                            Log.e(TAG, "Accept failed", e);
                        }
                    }
                }
                
            } catch (IOException e) {
                System.err.println("❌ [Server] Socket fatal error: " + e.getMessage());
                e.printStackTrace();
                Log.e(TAG, "❌ Failed to create ServerSocket", e);
                System.exit(1);
            }
        } catch (Throwable t) {
            System.err.println("🔥 [Server] FATAL CRASH: " + t.getMessage());
            t.printStackTrace();
            Log.e(TAG, "FATAL CRASH", t);
            System.exit(1);
        }
    }
    
    private static void handleClient(Socket client) {
        try (
            DataInputStream in = new DataInputStream(client.getInputStream());
            DataOutputStream out = new DataOutputStream(client.getOutputStream())
        ) {
            // Read Token
            int tokenLen = in.readInt();
            if (tokenLen > 1024 || tokenLen < 0) throw new SecurityException("Invalid token length");
            
            byte[] tokenBytes = new byte[tokenLen];
            in.readFully(tokenBytes);
            String receivedToken = new String(tokenBytes, StandardCharsets.UTF_8);
            
            if (!securityToken.equals(receivedToken)) {
                Log.e(TAG, "❌ Invalid token");
                out.writeInt(-1); // Status: Error
                out.flush();
                return;
            }
            
            // Read Command
            int cmd = in.readInt();
            out.writeInt(1); // Status: OK (Ack authentication)
            out.flush();
            
            switch (cmd) {
                case CMD_PING:
                    out.writeInt(VERSION);
                    out.flush();
                    break;
                    
                case CMD_INJECT_TOUCH: {
                    int displayId = in.readInt();
                    int action = in.readInt();
                    int x = in.readInt();
                    int y = in.readInt();
                    boolean result = ReflectionHelper.injectTouch(displayId, action, x, y);
                    out.writeBoolean(result);
                    out.flush();
                    break;
                }
                
                case CMD_INJECT_KEY: {
                    int keyCode = in.readInt();
                    boolean result = ReflectionHelper.injectKey(keyCode);
                    out.writeBoolean(result);
                    out.flush();
                    break;
                }
                
                case CMD_CAPTURE_SCREEN: {
                    int displayId = in.readInt();
                    String path = ReflectionHelper.captureScreenToFile(displayId);
                    out.writeUTF(path != null ? path : "");
                    out.flush();
                    break;
                }
                
                case CMD_CREATE_DISPLAY: {
                    String name = in.readUTF();
                    int width = in.readInt();
                    int height = in.readInt();
                    int density = in.readInt();
                    int displayId = ReflectionHelper.createVirtualDisplay(name, width, height, density);
                    out.writeInt(displayId);
                    out.flush();
                    break;
                }
                
                case CMD_RELEASE_DISPLAY: {
                    int displayId = in.readInt();
                    boolean result = ReflectionHelper.releaseVirtualDisplay(displayId);
                    out.writeBoolean(result);
                    out.flush();
                    break;
                }
                
                case CMD_START_ACTIVITY: {
                    int displayId = in.readInt();
                    String packageName = in.readUTF();
                    boolean result = ReflectionHelper.startActivityOnDisplay(displayId, packageName);
                    out.writeBoolean(result);
                    out.flush();
                    break;
                }
                
                case CMD_DESTROY: {
                    Log.d(TAG, "👋 Received destroy command");
                    out.writeBoolean(true);
                    out.flush();
                    isRunning = false;
                    System.exit(0);
                    break;
                }
                
                default:
                    Log.w(TAG, "Unknown command: " + cmd);
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Client handler error", e);
        } finally {
            try {
                client.close();
            } catch (IOException e) {
                // Ignore
            }
        }
    }
}
