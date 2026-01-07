package com.autoglm.autoagent.config

/**
 * 默认应用映射配置
 * 用于在无法动态获取包名时的降级处理
 */
object DefaultAppConfig {
    val staticAppMap = mapOf(
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
}
