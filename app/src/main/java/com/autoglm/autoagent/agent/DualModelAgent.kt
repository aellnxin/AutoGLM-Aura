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
    private val taskNotificationManager: com.autoglm.autoagent.utils.TaskNotificationManager,
    private val shizukuManager: com.autoglm.autoagent.shizuku.ShizukuManager,
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
    
    // ASK_USER 状态
    private val _pendingQuestion = MutableStateFlow<String?>(null)
    val pendingQuestion: StateFlow<String?> = _pendingQuestion.asStateFlow()
    
    private val _userAnswer = MutableStateFlow<String?>(null)
    
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
                    _pendingQuestion.value = planResult.question
                    _statusMessage.value = "❓ 等待回复..."
                    
                    // 等待用户通过 UI 注入回答
                    val answer = waitForUserAnswer()
                    _pendingQuestion.value = null
                    
                    if (answer.isNotBlank()) {
                        log("📝 收到回复: $answer，正在重新规划...")
                        // 使用用户提供的回答作为新目标或附加信息重新规划
                        // 这里我们简化处理：将原目标与回答合并后重试规划逻辑
                        return startTask("$goal (补充信息: $answer)")
                    } else {
                        log("❌ 任务取消 (未提供澄清回答)")
                        return TaskResult.Cancelled
                    }
                }
                is PlanResult.Plan -> {
                    val plan = planResult.plan
                    log("📋 [规划] 共 ${plan.steps.size} 步")
                    
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
        _planCountdown.value = 8
        
        return suspendCancellableCoroutine { continuation ->
            confirmationJob = reviewScope.launch {
                for (i in 8 downTo 1) {
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
    
    /**
     * 用户回答 ASK_USER 问题
     */
    fun answerQuestion(answer: String) {
        _userAnswer.value = answer
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
                
                // 检查是否是 ASK_USER（reason 包含问题内容）
                if (reason != null && reason.contains("?") || reason?.contains("？") == true) {
                    // ASK_USER: 弹出问题等待用户回答
                    _pendingQuestion.value = reason
                    _statusMessage.value = "❓ 等待用户回复..."
                    log("❓ 需要用户澄清: $reason")
                    
                    val answer = waitForUserAnswer()
                    if (answer.isBlank()) {
                        // 用户未回复或任务被取消
                        shouldInterrupt.set(false)
                        interruptReason.set(null)
                        continue
                    }
                    
                    // 用户回复后重新规划
                    log("📝 用户回复: $answer")
                    val context = buildContext()
                    val newPlan = orchestrator.replanWithUserAnswer(answer, context)
                    contextManager.setPlan(newPlan)
                    log("📋 重新规划: ${newPlan.steps.size} 步")
                    
                    shouldInterrupt.set(false)
                    interruptReason.set(null)
                    continue
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
                
                // 处理 REPLAN：将新步骤注入到上下文
                if (newDecision.type == DecisionType.REPLAN && !newDecision.newSteps.isNullOrEmpty()) {
                    val currentPlan = contextManager.getPlan()
                    val updatedPlan = TaskPlan.fromStringList(
                        goal = currentPlan?.goal ?: goal,
                        stepStrings = newDecision.newSteps
                    )
                    contextManager.setPlan(updatedPlan)
                    log("📋 [重规划] 新计划 ${newDecision.newSteps.size} 步: ${newDecision.newSteps.firstOrNull() ?: ""}")
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

            // 截图前确保 Shell 服务依然存活 (如果处于后台模式)
            if (agentRepository.isBackgroundMode) {
                var retryCount = 0
                while (!shizukuManager.ensureConnected() && retryCount < 3) {
                    retryCount++
                    Log.w(TAG, "Shell disconnected in DualMode background, retry $retryCount/3")
                    taskNotificationManager.updateStatus("正在重连 Shell 服务 ($retryCount/3)...")
                    delay(2000)
                }
                
                if (!shizukuManager.isServiceConnected.value) {
                    taskNotificationManager.showErrorNotification("任务暂停", "Shell 连通性损坏，请检查授权。")
                    log("❌ Shell 服务断开且重连失败")
                    return TaskResult.Error("Shell disconnection")
                }
            }

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
                        // 当前步骤完成，推进到下一步
                        contextManager.getPlan()?.markCurrentCompleted()
                        log("✅ 步骤完成，推进到: ${decision.nextStep ?: "下一步"}")
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
        // 复用 AgentRepository 的等待恢复逻辑
        agentRepository.waitForResume()
    }
    
    /**
     * 等待用户回答 ASK_USER 问题
     */
    private suspend fun waitForUserAnswer(): String {
        _userAnswer.value = null
        return suspendCancellableCoroutine { continuation ->
            reviewScope.launch {
                while (_userAnswer.value == null && _isRunning.value) {
                    delay(200)
                }
                val answer = _userAnswer.value ?: ""
                _pendingQuestion.value = null
                if (continuation.isActive) {
                    continuation.resume(answer) {}
                }
            }
        }
    }

    private suspend fun captureCurrentScreenshot(): Bitmap? {
        val displayId = if (agentRepository.isBackgroundMode) agentRepository.virtualDisplayId else 0
        
        // 1. 如果是后台模式，优先使用 Shell 截图
        if (displayId > 0) {
            try {
                val data = shizukuManager.getService()?.captureScreen(displayId)
                if (data != null && data.isNotEmpty()) {
                    val bitmap = android.graphics.BitmapFactory.decodeByteArray(data, 0, data.size)
                    if (bitmap != null) return bitmap
                }
            } catch (e: Exception) {
                Log.w(TAG, "Shell screenshot failed on Display $displayId", e)
            }
        }

        // 2. 兜底使用无障碍截图
        val accessibilityService = AutoAgentService.instance
        if (accessibilityService != null && 
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            val bitmap = accessibilityService.takeScreenshotAsync()
            if (bitmap != null) return bitmap
        }
        
        // 3. 极速模式 Shell 兜底 (主屏)
        if (displayId == 0) {
            try {
                val data = shizukuManager.getService()?.captureScreen(0)
                if (data != null && data.isNotEmpty()) {
                    return android.graphics.BitmapFactory.decodeByteArray(data, 0, data.size)
                }
            } catch (e: Exception) {}
        }
        
        return null
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
