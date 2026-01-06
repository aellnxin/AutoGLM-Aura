package com.autoglm.autoagent.agent

import android.graphics.Bitmap
import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ContextManager - 上下文管理器
 * 
 * 职责：
 * 1. 缓存截图 (最近5张)
 * 2. 缓存UI树 (最近3个)
 * 3. 记录执行历史
 * 4. 管理任务计划
 */
@Singleton
class ContextManager @Inject constructor() {

    companion object {
        private const val TAG = "ContextManager"
        private const val MAX_SCREENSHOT_CACHE = 5
        private const val MAX_UI_CACHE = 3
        private const val MAX_HISTORY = 20
    }

    // ==================== 状态 ====================

    private var currentGoal: String = ""
    private var currentPlan: TaskPlan? = null
    
    // 缓存
    private val screenshotCache = mutableMapOf<Int, Bitmap>()
    private val uiTreeCache = mutableMapOf<Int, String>()
    private val history = mutableListOf<String>()

    // ==================== 公共接口 ====================

    /**
     * 开始新任务
     */
    fun startTask(goal: String) {
        currentGoal = goal
        currentPlan = null
        screenshotCache.values.forEach { it.recycle() }
        screenshotCache.clear()
        uiTreeCache.clear()
        history.clear()
        Log.i(TAG, "任务开始: $goal")
    }

    /**
     * 设置任务计划
     */
    fun setPlan(plan: TaskPlan) {
        currentPlan = plan
        Log.d(TAG, "计划设置: ${plan.steps.size} 步")
    }

    /**
     * 获取当前计划
     */
    fun getPlan(): TaskPlan? = currentPlan

    /**
     * 缓存截图
     */
    fun cacheScreenshot(step: Int, screenshot: Bitmap) {
        screenshotCache[step] = screenshot
        // 清理旧截图
        if (screenshotCache.size > MAX_SCREENSHOT_CACHE) {
            val oldestKey = screenshotCache.keys.minOrNull()
            if (oldestKey != null) {
                screenshotCache[oldestKey]?.recycle()
                screenshotCache.remove(oldestKey)
            }
        }
    }

    /**
     * 获取历史截图
     */
    fun getScreenshot(step: Int): Bitmap? {
        return screenshotCache[step]
    }

    /**
     * 缓存UI树
     */
    fun cacheUiTree(step: Int, uiTree: String) {
        uiTreeCache[step] = uiTree
        // 清理旧UI树
        if (uiTreeCache.size > MAX_UI_CACHE) {
            val oldestKey = uiTreeCache.keys.minOrNull()
            if (oldestKey != null) {
                uiTreeCache.remove(oldestKey)
            }
        }
    }

    /**
     * 获取历史UI树
     */
    fun getUiTree(step: Int): String? {
        return uiTreeCache[step]
    }

    /**
     * 添加执行历史
     */
    fun addHistory(entry: String) {
        history.add(entry)
        // 限制历史长度
        if (history.size > MAX_HISTORY) {
            // 压缩: 保留最近一半
            val recent = history.takeLast(MAX_HISTORY / 2)
            history.clear()
            history.add("[早期步骤已压缩]")
            history.addAll(recent)
        }
    }

    /**
     * 获取执行历史
     */
    fun getHistory(): List<String> = history.toList()

    /**
     * 清理资源
     */
    fun cleanup() {
        screenshotCache.values.forEach { it.recycle() }
        screenshotCache.clear()
        uiTreeCache.clear()
        history.clear()
        currentPlan = null
    }
}
