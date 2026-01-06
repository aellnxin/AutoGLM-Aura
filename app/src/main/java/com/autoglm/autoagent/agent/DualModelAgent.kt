package com.autoglm.autoagent.agent

import android.graphics.Bitmap
import android.util.Log
import com.autoglm.autoagent.data.AgentRepository
import com.autoglm.autoagent.service.AutoAgentService
import kotlinx.coroutines.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import dagger.Lazy

/**
 * DualModelAgent - 双模型协调器 (异步审查版)
 * 
 * 执行流程：
 * 1. 大模型初始分析任务
 * 2. 小模型持续执行（不阻塞）
 * 3. 每3步异步发送给大模型审查
 * 4. 大模型发现问题时中断小模型
 * 
 * 边缘情况处理：
 * - 审查超时(8秒)：视为正常继续
 * - 请求堆积：取消旧请求，只保留最新
 * - 小模型 finish：等大模型确认
 * - 小模型卡死：立即通知大模型
 * - Take_over 后恢复：小模型继续，不通知大模型
 */
@Singleton
class DualModelAgent @Inject constructor(
    private val orchestrator: Orchestrator,
    private val worker: VisionWorker,
    private val contextManager: ContextManager,
    private val agentRepositoryProvider: dagger.Lazy<AgentRepository>
) {
    private val agentRepository get() = agentRepositoryProvider.get()

    companion object {
        private const val TAG = "DualModelAgent"
        private const val MAX_TOTAL_STEPS = 50
        private const val REVIEW_INTERVAL = 3      // 每3步审查
        private const val REVIEW_TIMEOUT_MS = 8000L // 审查超时8秒
    }

    // ==================== 状态 ====================

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _statusMessage = MutableStateFlow("")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val _currentStep = MutableStateFlow(0)
    val currentStep: StateFlow<Int> = _currentStep.asStateFlow()

    // 中断信号
    private val shouldInterrupt = AtomicBoolean(false)
    private val interruptReason = AtomicReference<String?>(null)
    
    // 规划确认状态
    private val _pendingPlan = MutableStateFlow<TaskPlan?>(null)
    val pendingPlan: StateFlow<TaskPlan?> = _pendingPlan.asStateFlow()
    
    private val _planCountdown = MutableStateFlow(0)
    val planCountdown: StateFlow<Int> = _planCountdown.asStateFlow()
    
    // 异步任务
    private val reviewScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var reviewJob: Job? = null
    private var confirmationJob: Job? = null
    
    // ==================== 公共接口 ====================

    fun canExecute(): Boolean {
        return orchestrator.checkAvailability() && worker.checkAvailability()
    }

    suspend fun startTask(goal: String): TaskResult {
        if (_isRunning.value) {
            return TaskResult.Error("任务正在执行中")
        }

        _isRunning.value = true
        _statusMessage.value = "正在分析任务..."
        _currentStep.value = 0
        shouldInterrupt.set(false)
        interruptReason.set(null)

        return try {
            contextManager.startTask(goal)
            worker.resetStepCount()
            
            // 1. 大模型初始分析
            _statusMessage.value = "📋 分析任务..."
            log("🧠 [规划] 正在分析任务...")
            
            when (val planResult = orchestrator.planTask(goal)) {
                is PlanResult.AskUser -> {
                    // 需要询问用户澄清
                    log("❓ [规划] 需要澄清: ${planResult.question}")
                    _statusMessage.value = ""
                    _isRunning.value = false
                    return TaskResult.Error("需要澄清: ${planResult.question}")
                }
                is PlanResult.Plan -> {
                    val plan = planResult.plan
                    log("📋 [规划] ${plan.selectedApp} - 共 ${plan.steps.size} 步")
                    
                    // 显示规划到 UI，等待确认
                    _pendingPlan.value = plan
                    _statusMessage.value = "等待确认..."
                    
                    // 启动 3 秒倒计时
                    val confirmed = waitForConfirmation()
                    
                    if (!confirmed) {
                        log("❌ [规划] 用户取消")
                        _pendingPlan.value = null
                        _isRunning.value = false
                        return TaskResult.Cancelled
                    }
                    
                    // 用户确认（或超时自动确认）
                    _pendingPlan.value = null
                    contextManager.setPlan(plan)
                    Log.i(TAG, "任务开始: $goal")
                    
                    // 2. 小模型执行循环
                    executeLoop(goal)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "任务执行失败", e)
            TaskResult.Error("执行失败: ${e.message}")
        } finally {
            _isRunning.value = false
            _statusMessage.value = ""
            _pendingPlan.value = null
            reviewJob?.cancel()
            confirmationJob?.cancel()
            orchestrator.clearHistory()
        }
    }
    
    /**
     * 等待用户确认（3秒超时自动确认）
     * @return true=确认执行, false=取消
     */
    private suspend fun waitForConfirmation(): Boolean {
        _planCountdown.value = 3
        
        return suspendCancellableCoroutine { continuation ->
            confirmationJob = reviewScope.launch {
                for (i in 3 downTo 1) {
                    _planCountdown.value = i
                    delay(1000)
                    
                    // 检查是否被中断（用户点击了按钮）
                    if (shouldInterrupt.get()) {
                        val reason = interruptReason.get()
                        shouldInterrupt.set(false)
                        interruptReason.set(null)
                        
                        if (reason == "确认") {
                            continuation.resume(true) {}
                        } else {
                            continuation.resume(false) {}
                        }
                        return@launch
                    }
                }
                // 倒计时结束，自动确认
                _planCountdown.value = 0
                continuation.resume(true) {}
            }
        }
    }
    
    /**
     * 用户确认规划
     */
    fun confirmPlan() {
        shouldInterrupt.set(true)
        interruptReason.set("确认")
    }
    
    /**
     * 用户取消规划
     */
    fun cancelPlan() {
        shouldInterrupt.set(true)
        interruptReason.set("取消")
    }

    fun stop() {
        shouldInterrupt.set(true)
        interruptReason.set("用户停止")
        _isRunning.value = false
        reviewJob?.cancel()
        confirmationJob?.cancel()
    }

    // ==================== 执行循环 ====================

    private suspend fun executeLoop(goal: String): TaskResult {
        var totalSteps = 0
        var stepsSinceLastReview = 0

        while (_isRunning.value && totalSteps < MAX_TOTAL_STEPS) {
            // 检查中断信号
            if (shouldInterrupt.get()) {
                val reason = interruptReason.get()
                Log.i(TAG, "收到中断信号: $reason")
                
                if (reason == "用户停止") {
                    return TaskResult.Cancelled
                }
                
                // 大模型要求中断，等待新指令
                _statusMessage.value = "🧠 等待大模型指令..."
                val newDecision = waitForReplanDecision()
                
                if (newDecision.type == DecisionType.FINISH) {
                    return TaskResult.Success(newDecision.message)
                }
                if (newDecision.type == DecisionType.ERROR) {
                    return TaskResult.Error(newDecision.message)
                }
                
                // 重置中断，继续执行
                shouldInterrupt.set(false)
                interruptReason.set(null)
                continue
            }

            totalSteps++
            stepsSinceLastReview++
            _currentStep.value = totalSteps
            _statusMessage.value = "[$totalSteps] ⚡ 执行中..."

            // 小模型执行一步（单步模式）
            val report = worker.executeSingleStep(goal)
            
            // 记录日志
            val actionDesc = report.actions.joinToString(", ")
            log("⚡ [$totalSteps] $actionDesc")
            
            // 缓存截图
            if (report.currentScreenshot != null) {
                contextManager.cacheScreenshot(totalSteps, report.currentScreenshot)
            }
            contextManager.addHistory("[$totalSteps] $actionDesc - ${report.status}")

            // 处理特殊状态
            when (report.status) {
                WorkerStatus.COMPLETED -> {
                    // 小模型认为完成，等大模型确认
                    _statusMessage.value = "[$totalSteps] ✅ 确认完成..."
                    log("✅ [$totalSteps] 小模型报告完成: ${report.message}")
                    val confirmed = confirmFinish(report)
                    if (confirmed) {
                        log("🎉 任务完成确认")
                        return TaskResult.Success(report.message.ifBlank { "任务完成" })
                    }
                    log("🔄 大模型认为未完成，继续执行")
                    // 大模型认为未完成，继续执行
                    continue
                }
                
                WorkerStatus.NEEDS_USER -> {
                    // 暂停等待用户操作
                    _statusMessage.value = "[$totalSteps] 👤 等待用户..."
                    waitForUserResume()
                    // 用户操作完成后，小模型继续（不通知大模型）
                    continue
                }
                
                WorkerStatus.STUCK, WorkerStatus.FAILED -> {
                    // 立即通知大模型
                    _statusMessage.value = "[$totalSteps] 🆘 请求帮助..."
                    log("⚠️ [$totalSteps] ${report.status}: ${report.message}")
                    val decision = requestImmediateHelp(report)
                    if (decision.type == DecisionType.FINISH) {
                        log("🎉 大模型决定完成: ${decision.message}")
                        return TaskResult.Success(decision.message)
                    }
                    if (decision.type == DecisionType.ERROR) {
                        log("❌ 错误: ${decision.message}")
                        return TaskResult.Error(decision.message)
                    }
                    log("🔄 大模型提供新指令，继续执行")
                    // 大模型提供了新指令，继续
                    continue
                }
                
                WorkerStatus.IN_PROGRESS -> {
                    // 正常执行中
                }
            }

            // 每3步异步发送审查
            if (stepsSinceLastReview >= REVIEW_INTERVAL) {
                stepsSinceLastReview = 0
                launchAsyncReview(report, totalSteps)
            }

            delay(300) // 步骤间隔
        }

        return if (!_isRunning.value) {
            TaskResult.Cancelled
        } else {
            TaskResult.Error("达到最大步数: $MAX_TOTAL_STEPS")
        }
    }

    // ==================== 异步审查 ====================

    private fun launchAsyncReview(report: WorkerReport, step: Int) {
        // 取消旧的审查请求
        reviewJob?.cancel()
        
        reviewJob = reviewScope.launch {
            try {
                Log.d(TAG, "[$step] 异步审查开始")
                
                val context = buildContext()
                
                // 带超时的审查
                val decision = withTimeoutOrNull(REVIEW_TIMEOUT_MS) {
                    orchestrator.review(report, context)
                }
                
                if (decision == null) {
                    Log.d(TAG, "[$step] 审查超时，继续执行")
                    return@launch
                }
                
                Log.d(TAG, "[$step] 审查结果: ${decision.type}")
                
                // 处理审查结果
                when (decision.type) {
                    DecisionType.NEXT_STEP -> {
                        // 正常，不干预
                    }
                    DecisionType.REPLAN, DecisionType.ERROR, DecisionType.FINISH, DecisionType.ASK_USER -> {
                        // 需要中断小模型
                        shouldInterrupt.set(true)
                        interruptReason.set(decision.message)
                    }
                    DecisionType.GET_INFO -> {
                        // 大模型需要更多信息，处理工具请求
                        handleToolRequest(decision.tool, step)
                    }
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "[$step] 审查被取消")
            } catch (e: Exception) {
                Log.e(TAG, "[$step] 审查失败", e)
            }
        }
    }

    private suspend fun handleToolRequest(tool: ToolRequest?, step: Int) {
        if (tool == null) return
        
        val result = when (tool.tool) {
            ToolType.GET_UI -> AutoAgentService.instance?.dumpOptimizedUiTree()
            ToolType.GET_HISTORY_SCREENSHOT -> contextManager.getScreenshot(tool.step ?: step)
            ToolType.GET_HISTORY_UI -> contextManager.getUiTree(tool.step ?: step)
        }
        
        val context = buildContext()
        val decision = orchestrator.continueWithToolResult(tool.tool, result, context)
        
        if (decision.type != DecisionType.NEXT_STEP) {
            shouldInterrupt.set(true)
            interruptReason.set(decision.message)
        }
    }

    // ==================== 同步等待方法 ====================

    private suspend fun confirmFinish(report: WorkerReport): Boolean {
        val context = buildContext()
        val decision = orchestrator.review(report, context)
        return decision.type == DecisionType.FINISH
    }

    private suspend fun requestImmediateHelp(report: WorkerReport): OrchestratorDecision {
        val context = buildContext()
        return orchestrator.review(report, context)
    }

    private suspend fun waitForReplanDecision(): OrchestratorDecision {
        // 大模型已经在中断时发送了决策，这里只是等待确认
        val context = buildContext()
        // 发送当前状态请求新指令
        val currentScreenshot = captureCurrentScreenshot()
        val report = WorkerReport(
            subTask = "等待新指令",
            stepsTaken = 0,
            actions = emptyList(),
            results = emptyList(),
            currentScreenshot = currentScreenshot,
            status = WorkerStatus.IN_PROGRESS,
            message = interruptReason.get() ?: ""
        )
        return orchestrator.review(report, context)
    }

    private suspend fun waitForUserResume() {
        // TODO: 实现暂停等待用户的逻辑
        // 可以通过 StateFlow 或 Channel 实现
        delay(5000) // 临时实现：等待5秒
    }

    private suspend fun captureCurrentScreenshot(): Bitmap? {
        val accessibilityService = AutoAgentService.instance
        return if (accessibilityService != null && 
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            accessibilityService.takeScreenshotAsync()
        } else {
            null
        }
    }

    // ==================== 辅助方法 ====================

    private fun buildContext(): ContextSnapshot {
        val currentApp = AutoAgentService.instance?.currentPackageName ?: "Unknown"
        val plan = contextManager.getPlan()
        return ContextSnapshot(
            goal = plan?.goal ?: "",
            plan = plan,
            currentStep = _currentStep.value,
            totalSteps = MAX_TOTAL_STEPS,
            textHistory = contextManager.getHistory(),
            notes = orchestrator.getNotes(),
            currentApp = currentApp,
            currentScreenshot = null
        )
    }
    
    /**
     * 添加日志到 UI
     */
    private fun log(message: String) {
        agentRepository.logMessage("system", message)
    }
}
