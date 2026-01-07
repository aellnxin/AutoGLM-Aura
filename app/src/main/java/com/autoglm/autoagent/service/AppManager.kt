package com.autoglm.autoagent.service

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import com.autoglm.autoagent.data.SettingsRepository
import com.autoglm.autoagent.config.DefaultAppConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val shellConnector: com.autoglm.autoagent.shell.ShellServiceConnector
) {
    
    private val appMap = mutableMapOf<String, String>()
    private var isInitialized = false

    init {
        // 1. 加载缓存
        try {
            val cachedApps = settingsRepository.loadAppList()
            if (cachedApps.isNotEmpty()) {
                appMap.putAll(cachedApps)
                isInitialized = true
            }
        } catch (e: Exception) {
            Log.e("AppManager", "Failed to load cached apps", e)
        }
        
        // 2. 加载默认配置 (作为兜底)
        if (appMap.isEmpty()) {
            appMap.putAll(DefaultAppConfig.staticAppMap)
            isInitialized = true
        }
    }
    
    private fun ensureInitialized() {
        if (!isInitialized || appMap.size <= DefaultAppConfig.staticAppMap.size) {
            refreshAppList()
        }
    }

    fun refreshAppList() {
        Log.d("AppManager", "Refreshing app list...")
        try {
            val pm = context.packageManager
            val packages = pm.getInstalledPackages(0)
            
            // 先保存旧数据
            val oldAppMap = appMap.toMap()
            appMap.clear()
            
            // 1. 优先加载静态映射 (保证常用别名存在)
            appMap.putAll(DefaultAppConfig.staticAppMap)

            // 2. 扫描已安装应用 (动态覆盖)
            for (pkg in packages) {
                val intent = pm.getLaunchIntentForPackage(pkg.packageName)
                if (intent != null) {
                    val label = pkg.applicationInfo.loadLabel(pm).toString()
                    appMap[label.lowercase()] = pkg.packageName
                    // 同时保留原始大小写作为 Key
                    appMap[label] = pkg.packageName 
                }
            }
            Log.d("AppManager", "Indexed ${appMap.size} apps")
            
            // 持久化
            settingsRepository.saveAppList(appMap)
        } catch (e: Exception) {
            Log.e("AppManager", "Failed to list apps", e)
            if (appMap.isEmpty()) {
                appMap.putAll(DefaultAppConfig.staticAppMap)
            }
        }
    }

    /**
     * 根据应用名称查找包名（模糊匹配，最长匹配优先）
     */
    fun findAppInText(text: String): String? {
        ensureInitialized()
        return appMap.keys
            .filter { text.contains(it, ignoreCase = true) }
            .maxByOrNull { it.length }
    }
    
    /**
     * 精确查找
     */
    fun getPackageName(appName: String): String? {
        ensureInitialized()
        return appMap[appName.lowercase()] 
            ?: appMap.keys.find { it.contains(appName, ignoreCase = true) }?.let { appMap[it] }
    }

    fun stopApp(appName: String): Boolean {
        ensureInitialized()
        val targetPkg = getPackageName(appName)
            
        if (targetPkg != null) {
            if (targetPkg == context.packageName) return false
            if (isSystemApp(targetPkg)) return false

            return try {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                am.killBackgroundProcesses(targetPkg)
                true
            } catch (e: Exception) {
                false
            }
        }
        return false
    }

    private fun isSystemApp(packageName: String): Boolean {
        return try {
            val ai = context.packageManager.getApplicationInfo(packageName, 0)
            (ai.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
        } catch (e: Exception) {
            false
        }
    }

    fun launchApp(appName: String, displayId: Int = 0): Boolean {
        ensureInitialized()
        val targetPkg = getPackageName(appName) ?: return false
        
        Log.d("AppManager", "Launching $appName ($targetPkg) on display $displayId")
        
        // 如果是后台启动 (Display > 0)，必须走 Shell 强冷启动逻辑
        if (displayId > 0) {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(targetPkg)
            val componentName = launchIntent?.component?.flattenToShortString() ?: targetPkg
            return shellConnector.startActivityOnDisplay(displayId, componentName)
        }
        
        // 主屏启动
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(targetPkg) ?: return false
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e("AppManager", "Failed to launch $targetPkg", e)
            false
        }
    }
}