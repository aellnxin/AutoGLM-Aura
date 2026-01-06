package com.autoglm.autoagent.utils

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

object KeepAliveUtils {
    private const val TAG = "KeepAliveUtils"

    /**
     * 检测是否忽略了电池优化
     */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * 请求忽略电池优化 (弹出系统对话框)
     */
    @SuppressLint("BatteryLife")
    fun requestIgnoreBatteryOptimizations(context: Context) {
        try {
            if (!isIgnoringBatteryOptimizations(context)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                intent.data = Uri.parse("package:${context.packageName}")
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Request ignore battery optimizations failed", e)
        }
    }

    /**
     * 跳转到应用详情页 (用于手动设置省电策略)
     * For MIUI: 省电策略 -> 无限制
     */
    fun openAppDetails(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            intent.data = Uri.parse("package:${context.packageName}")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Open app details failed", e)
        }
    }

    /**
     * 跳转到自启动管理页面 (适配常见厂商)
     */
    fun openAutoStartSettings(context: Context) {
        val intent = Intent()
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        
        val manufacturer = Build.MANUFACTURER.lowercase()
        var componentName: ComponentName? = null

        when {
            manufacturer.contains("xiaomi") || manufacturer.contains("redmi") -> {
                componentName = ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"
                )
            }
            manufacturer.contains("huawei") || manufacturer.contains("honor") -> {
                componentName = ComponentName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
                )
            }
            manufacturer.contains("vivo") -> {
                componentName = ComponentName(
                    "com.vivo.permissionmanager",
                    "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
                )
            }
            manufacturer.contains("oppo") -> {
                componentName = ComponentName(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.permission.startup.StartupAppListActivity"
                )
            }
        }

        try {
            if (componentName != null) {
                intent.component = componentName
                context.startActivity(intent)
            } else {
                // Fallback to settings
                context.startActivity(Intent(Settings.ACTION_SETTINGS).apply { 
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) 
                })
            }
        } catch (e: Exception) {
            Log.e(TAG, "Open auto start settings failed", e)
            // Fallback
            try {
                context.startActivity(Intent(Settings.ACTION_SETTINGS).apply { 
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) 
                })
            } catch (e2: Exception) {
                // ignore
            }
        }
    }

    /**
     * 构建 ADB 白名单命令
     */
    fun getAdbWhitelistCommand(packageName: String): String {
        return "cmd deviceidle whitelist +$packageName"
    }
}
