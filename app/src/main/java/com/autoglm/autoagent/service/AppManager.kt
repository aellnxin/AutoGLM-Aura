package com.autoglm.autoagent.service

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import com.autoglm.autoagent.data.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val shellConnector: com.autoglm.autoagent.shell.ShellServiceConnector
) {
    // Static fallback from Open-AutoGLM config/apps.py
    // Static fallback from Open-AutoGLM config/apps.py
    private val staticAppMap = mapOf(
        // Social & Messaging
        "微信" to "com.tencent.mm",
        "QQ" to "com.tencent.mobileqq",
        "微博" to "com.sina.weibo",
        "WeChat" to "com.tencent.mm",
        "wechat" to "com.tencent.mm",
        "Whatsapp" to "com.whatsapp",
        "WhatsApp" to "com.whatsapp",
        "Telegram" to "org.telegram.messenger",
        // E-commerce
        "淘宝" to "com.taobao.taobao",
        "京东" to "com.jingdong.app.mall",
        "拼多多" to "com.xunmeng.pinduoduo",
        "淘宝闪购" to "com.taobao.taobao",
        "京东秒送" to "com.jingdong.app.mall",
        "temu" to "com.einnovation.temu",
        "Temu" to "com.einnovation.temu",
        // Lifestyle & Social
        "小红书" to "com.xingin.xhs",
        "豆瓣" to "com.douban.frodo",
        "知乎" to "com.zhihu.android",
        "Twitter" to "com.twitter.android",
        "twitter" to "com.twitter.android",
        "X" to "com.twitter.android",
        "Reddit" to "com.reddit.frontpage",
        "reddit" to "com.reddit.frontpage",
        // Maps & Navigation
        "高德地图" to "com.autonavi.minimap",
        "百度地图" to "com.baidu.BaiduMap",
        "GoogleMaps" to "com.google.android.apps.maps",
        "Google Maps" to "com.google.android.apps.maps",
        "googlemaps" to "com.google.android.apps.maps",
        "google maps" to "com.google.android.apps.maps",
        "Osmand" to "net.osmand",
        // Food & Services
        "美团" to "com.sankuai.meituan",
        "大众点评" to "com.dianping.v1",
        "饿了么" to "me.ele",
        "肯德基" to "com.yek.android.kfc.activitys",
        "McDonald" to "com.mcdonalds.app",
        // Travel
        "携程" to "ctrip.android.view",
        "铁路12306" to "com.MobileTicket",
        "12306" to "com.MobileTicket",
        "去哪儿" to "com.Qunar",
        "去哪儿旅行" to "com.Qunar",
        "滴滴出行" to "com.sdu.didi.psnger",
        "Booking.com" to "com.booking",
        "Booking" to "com.booking",
        "Expedia" to "com.expedia.bookings",
        // Video & Entertainment
        "bilibili" to "tv.danmaku.bili",
        "抖音" to "com.ss.android.ugc.aweme",
        "快手" to "com.smile.gifmaker",
        "腾讯视频" to "com.tencent.qqlive",
        "爱奇艺" to "com.qiyi.video",
        "优酷视频" to "com.youku.phone",
        "芒果TV" to "com.hunantv.imgo.activity",
        "红果短剧" to "com.phoenix.read",
        "Tiktok" to "com.zhiliaoapp.musically",
        "tiktok" to "com.zhiliaoapp.musically",
        "VLC" to "org.videolan.vlc",
        // Music & Audio
        "网易云音乐" to "com.netease.cloudmusic",
        "QQ音乐" to "com.tencent.qqmusic",
        "汽水音乐" to "com.luna.music",
        "喜马拉雅" to "com.ximalaya.ting.android",
        // Reading
        "番茄小说" to "com.dragon.read",
        "番茄免费小说" to "com.dragon.read",
        "七猫免费小说" to "com.kmxs.reader",
        // Productivity
        "飞书" to "com.ss.android.lark",
        "QQ邮箱" to "com.tencent.androidqqmail",
        "gmail" to "com.google.android.gm",
        "Gmail" to "com.google.android.gm",
        "Files" to "com.android.fileexplorer",
        "Chrome" to "com.android.chrome",
        "chrome" to "com.android.chrome",
        "Google Chrome" to "com.android.chrome",
        // AI & Tools
        "豆包" to "com.larus.nova",
        // Health & Fitness
        "keep" to "com.gotokeep.keep",
        "美柚" to "com.lingan.seeyou",
        "GoogleFit" to "com.google.android.apps.fitness",
        // News & Information
        "腾讯新闻" to "com.tencent.news",
        "今日头条" to "com.ss.android.article.news",
        // Real Estate
        "贝壳找房" to "com.lianjia.beike",
        "安居客" to "com.anjuke.android.app",
        // Finance
        "同花顺" to "com.hexin.plat.android",
        "支付宝" to "com.eg.android.AlipayGphone",
        "alipay" to "com.eg.android.AlipayGphone",
        // Games
        "星穹铁道" to "com.miHoYo.hkrpg",
        "崩坏：星穹铁道" to "com.miHoYo.hkrpg",
        "恋与深空" to "com.papegames.lysk.cn",
        // System
        "相机" to "com.android.camera",
        "settings" to "com.android.settings",
        "设置" to "com.android.settings",
        "AndroidSystemSettings" to "com.android.settings",
        "Settings" to "com.android.settings",
        "Clock" to "com.android.deskclock",
        "Contacts" to "com.android.contacts",
        "Files" to "com.android.fileexplorer"
    )
    
    private val appMap = mutableMapOf<String, String>()
    private var isInitialized = false

    init {
        // 先从配置加载缓存的应用列表
        try {
            val cachedApps = settingsRepository.loadAppList()
            if (cachedApps.isNotEmpty()) {
                appMap.putAll(cachedApps)
                Log.d("AppManager", "Loaded ${cachedApps.size} apps from cache")
                isInitialized = true
            }
        } catch (e: Exception) {
            Log.e("AppManager", "Failed to load cached apps", e)
        }
        
        // 添加静态映射作为 fallback
        if (appMap.isEmpty()) {
            appMap.putAll(staticAppMap)
            Log.d("AppManager", "Initialized with ${staticAppMap.size} static apps")
            isInitialized = true
        }
    }
    
    private fun ensureInitialized() {
        if (!isInitialized || appMap.size <= staticAppMap.size) {
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
            
            // 1. Add static map first (high priority for known aliases)
            appMap.putAll(staticAppMap)

            // 2. Add dynamic installed apps
            for (pkg in packages) {
                val intent = pm.getLaunchIntentForPackage(pkg.packageName)
                if (intent != null) {
                    val label = pkg.applicationInfo.loadLabel(pm).toString()
                    appMap[label.lowercase()] = pkg.packageName
                    appMap[label] = pkg.packageName // Case sensitive backup
                }
            }
            Log.d("AppManager", "Indexed ${appMap.size} apps (${packages.size} packages scanned)")
            
            // 保存到配置文件
            settingsRepository.saveAppList(appMap)
            Log.d("AppManager", "Saved app list to config")
        } catch (e: Exception) {
            Log.e("AppManager", "Failed to list apps", e)
            // 如果失败,至少保证有 staticAppMap
            if (appMap.isEmpty()) {
                appMap.putAll(staticAppMap)
                Log.d("AppManager", "Fallback to static app map")
            }
        }
    }

    /**
     * 根据应用名称查找包名（支持模糊匹配）
     */
    fun getPackageName(appName: String): String? {
        ensureInitialized()
        return appMap[appName.lowercase()] 
            ?: appMap.keys.find { it.contains(appName, ignoreCase = true) }?.let { appMap[it] }
    }
    
    /**
     * 在文本中查找已知的应用名称
     */
    fun findAppInText(text: String): String? {
        ensureInitialized()
        // 优先匹配静态映射中的常用 App（更精准），然后扫描全部 App
        for (name in staticAppMap.keys) {
            if (text.contains(name, ignoreCase = true)) {
                return name
            }
        }
        
        // 如果需要，也可以扩展到全部 App，但为了性能和准确性，常用 App 优先
        // for (name in appMap.keys) { ... }
        
        return null
    }

    fun stopApp(appName: String): Boolean {
        ensureInitialized()
        val targetPkg = getPackageName(appName)
            
        if (targetPkg != null) {
            Log.d("AppManager", "Attempting to stop app: $targetPkg ($appName)")
            return try {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                am.killBackgroundProcesses(targetPkg)
                true
            } catch (e: Exception) {
                Log.e("AppManager", "Failed to stop $targetPkg", e)
                false
            }
        }
        return false
    }

    fun launchApp(appName: String, displayId: Int = 0): Boolean {
        ensureInitialized()
        
        Log.d("AppManager", "Attempting to launch app: $appName on display: $displayId")
        
        val targetPkg = getPackageName(appName)
        
        if (targetPkg != null) {
            Log.d("AppManager", "Found package: $targetPkg for app: $appName")
            
            // 如果指定了 displayId 且 > 0，使用 Shell 服务启动
            if (displayId > 0) {
                // 必须获取 Launch Intent 来知道入口 Activity
                val launchIntent = context.packageManager.getLaunchIntentForPackage(targetPkg)
                val componentName = launchIntent?.component?.flattenToShortString() ?: targetPkg
                
                // 传递组件名 (e.g. "com.tencent.mm/.ui.LauncherUI") 而不是包名
                return shellConnector.startActivityOnDisplay(displayId, componentName)
            }
            
            // 默认主屏幕启动
            return try {
                val intent = context.packageManager.getLaunchIntentForPackage(targetPkg)
                if (intent == null) {
                    Log.e("AppManager", "Launch intent is null for $targetPkg")
                    return false
                }
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                Log.d("AppManager", "Successfully started activity for $targetPkg")
                true
            } catch (e: Exception) {
                Log.e("AppManager", "Failed to launch $targetPkg", e)
                false
            }
        }
        Log.e("AppManager", "App not found in map: $appName. Available apps: ${appMap.keys.take(10)}")
        return false
    }
}
