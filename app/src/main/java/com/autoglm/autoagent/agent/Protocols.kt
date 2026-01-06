package com.autoglm.autoagent.agent

import android.graphics.Bitmap

/**
 * ==================== 思考模式数据结构 ====================
 * 双模型架构：大模型(Orchestrator)规划+审查，小模型(Worker)执行
 */

// ==================== 执行模式 ====================

/**
 * Agent 执行模式
 */
enum class AgentMode {
    TURBO,  // 极速模式：单模型执行
    DEEP    // 思考模式：大小模型协作
}

// ==================== 任务计划 ====================

/**
 * 任务计划
 */
data class TaskPlan(
    val goal: String,
    val selectedApp: String = "",    // 选择的 App
    val steps: List<String>,
    val notes: MutableList<String> = mutableListOf()
)

/**
 * 规划结果（可能是计划或询问用户）
 */
sealed class PlanResult {
    data class Plan(val plan: TaskPlan) : PlanResult()
    data class AskUser(val question: String) : PlanResult()
}

// ==================== 小模型汇报 ====================

/**
 * 小模型执行汇报
 * 每执行完子任务/3步/异常时，向大模型汇报
 */
data class WorkerReport(
    val subTask: String,              // 执行的子任务
    val stepsTaken: Int,              // 执行了几步
    val actions: List<String>,        // 操作列表 (如 "Tap(搜索框)", "Type(T恤)")
    val results: List<Boolean>,       // 每步是否成功
    val currentScreenshot: Bitmap?,   // 当前截图
    val status: WorkerStatus,         // 执行状态
    val message: String = ""          // 附加信息 (异常原因等)
)

/**
 * 小模型执行状态
 */
enum class WorkerStatus {
    COMPLETED,      // 子任务完成
    IN_PROGRESS,    // 达到3步限制，需审查
    FAILED,         // 操作失败
    STUCK,          // 卡死（连续相同操作无效）
    NEEDS_USER      // 需要用户介入（登录/验证）
}

// ==================== 大模型决策 ====================

/**
 * 大模型审查决策
 */
data class OrchestratorDecision(
    val type: DecisionType,
    val nextStep: String? = null,     // 下一步指令 (NEXT_STEP 时)
    val newSteps: List<String>? = null, // 新计划 (REPLAN 时)
    val message: String = "",         // 消息 (FINISH/ERROR 时)
    val tool: ToolRequest? = null     // 工具请求 (GET_INFO 时)
)

/**
 * 决策类型
 */
enum class DecisionType {
    NEXT_STEP,  // 继续下一步
    REPLAN,     // 重新规划
    FINISH,     // 任务完成
    GET_INFO,   // 获取更多信息
    ASK_USER,   // 询问用户
    ERROR       // 错误，终止
}

/**
 * 工具请求
 */
data class ToolRequest(
    val tool: ToolType,
    val step: Int? = null  // 历史步数 (GetHistoryScreenshot/GetHistoryUI 时)
)

/**
 * 工具类型
 */
enum class ToolType {
    GET_UI,                 // 获取当前 UI 树
    GET_HISTORY_SCREENSHOT, // 获取历史截图
    GET_HISTORY_UI          // 获取历史 UI 树
}

// ==================== 上下文快照 ====================

/**
 * 上下文快照 - 传递给大模型审查
 */
data class ContextSnapshot(
    val goal: String,
    val plan: TaskPlan?,
    val currentStep: Int,           // 当前步骤序号
    val totalSteps: Int,            // 总步骤数
    val textHistory: List<String>,  // 执行历史文本
    val notes: List<String>,        // 笔记
    val currentApp: String,
    val currentScreenshot: Bitmap?
)

// ==================== 任务结果 ====================

/**
 * 任务执行结果
 */
sealed class TaskResult {
    data class Success(val message: String) : TaskResult()
    data class Error(val error: String) : TaskResult()
    data object Cancelled : TaskResult()
}

// ==================== 异常类型 ====================

/**
 * 异常类型 (小模型检测)
 */
enum class AnomalyType {
    SCREEN_UNCHANGED,   // 屏幕无变化
    UNEXPECTED_DIALOG,  // 意外弹窗
    APP_CRASH,          // App 崩溃
    NAVIGATION_ERROR    // 导航错误
}
