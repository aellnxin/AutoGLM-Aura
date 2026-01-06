package com.autoglm.autoagent.agent

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.autoglm.autoagent.data.SettingsRepository
import com.autoglm.autoagent.data.ApiProvider
import com.autoglm.autoagent.data.api.AIClient
import com.autoglm.autoagent.data.api.ChatMessage
import com.autoglm.autoagent.data.api.ContentPart
import com.autoglm.autoagent.data.api.ImageUrl
import org.json.JSONObject
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orchestrator - 大模型规划审查器
 * 
 * 职责：
 * 1. 任务规划：分解用户任务为多个子任务步骤
 * 2. 进度监控：审查小模型执行结果
 * 3. 异常处理：识别问题并重新规划
 * 4. 记忆维护：管理笔记和关键信息
 */
@Singleton
class Orchestrator @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val aiClient: AIClient
) {
    companion object {
        private const val TAG = "Orchestrator"
    }

    private val conversationHistory = mutableListOf<ChatMessage>()
    private val notes = mutableListOf<String>()

    // ==================== 公共接口 ====================

    /**
     * 检查大模型是否可用
     */
    fun checkAvailability(): Boolean {
        return try {
            val config = settingsRepository.loadProviderConfig(ApiProvider.ZHIPU)
            config.apiKey.isNotBlank() && config.baseUrl.isNotBlank()
        } catch (e: Exception) {
            Log.e(TAG, "可用性检查失败", e)
            false
        }
    }

    /**
     * 规划任务：分解为子任务步骤
     * @return PlanResult - 可能是 Plan 或 AskUser
     */
    suspend fun planTask(goal: String): PlanResult {
        conversationHistory.clear()
        notes.clear()
        conversationHistory.add(ChatMessage("system", getPlanningPrompt()))

        val userMessage = """
请分析以下任务并制定执行计划。

任务：$goal
        """.trimIndent()

        conversationHistory.add(ChatMessage("user", userMessage))

        return try {
            val config = settingsRepository.loadProviderConfig(ApiProvider.ZHIPU)
            val response = aiClient.sendMessage(conversationHistory, overrideConfig = config)
            val content = response.content ?: ""
            conversationHistory.add(ChatMessage("assistant", content))
            
            parsePlanResult(content, goal)
        } catch (e: Exception) {
            Log.e(TAG, "任务规划失败", e)
            // 降级：直接作为单步任务执行
            PlanResult.Plan(TaskPlan(goal = goal, steps = listOf(goal)))
        }
    }
    
    /**
     * 解析规划结果
     */
    private fun parsePlanResult(content: String, goal: String): PlanResult {
        return try {
            // 提取 JSON
            val jsonRegex = Regex("\\{[\\s\\S]*\\}")
            val jsonMatch = jsonRegex.find(content) ?: throw Exception("No JSON found")
            val json = JSONObject(jsonMatch.value)
            
            val type = json.optString("type", "PLAN")
            
            when (type.uppercase()) {
                "ASK_USER" -> {
                    val question = json.optString("question", "请提供更多信息")
                    PlanResult.AskUser(question)
                }
                else -> {
                    // PLAN 类型
                    val selectedApp = json.optString("selectedApp", "")
                    val stepsArray = json.optJSONArray("steps") ?: JSONArray()
                    val steps = mutableListOf<String>()
                    for (i in 0 until stepsArray.length()) {
                        steps.add(stepsArray.getString(i))
                    }
                    if (steps.isEmpty()) {
                        steps.add(goal)
                    }
                    PlanResult.Plan(TaskPlan(
                        goal = goal,
                        selectedApp = selectedApp,
                        steps = steps
                    ))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "解析规划结果失败: ${e.message}")
            // 降级处理
            PlanResult.Plan(TaskPlan(goal = goal, steps = listOf(goal)))
        }
    }

    /**
     * 审查小模型汇报，决定下一步
     */
    suspend fun review(
        report: WorkerReport,
        context: ContextSnapshot
    ): OrchestratorDecision {
        val contentParts = mutableListOf<ContentPart>()

        // 添加当前截图
        if (report.currentScreenshot != null) {
            val base64 = bitmapToBase64(report.currentScreenshot)
            contentParts.add(ContentPart(
                type = "image_url",
                image_url = ImageUrl("data:image/png;base64,$base64")
            ))
        }

        // 构建审查文本
        val reviewText = buildReviewPrompt(report, context)
        contentParts.add(ContentPart(type = "text", text = reviewText))

        conversationHistory.add(ChatMessage("user", contentParts))

        return try {
            val config = settingsRepository.loadProviderConfig(ApiProvider.ZHIPU)
            val response = aiClient.sendMessage(conversationHistory, overrideConfig = config)
            val content = response.content ?: ""
            conversationHistory.add(ChatMessage("assistant", content))

            parseDecision(content, context)
        } catch (e: Exception) {
            Log.e(TAG, "审查失败", e)
            OrchestratorDecision(DecisionType.ERROR, message = "审查失败: ${e.message}")
        }
    }

    /**
     * 处理工具调用结果，继续决策
     */
    suspend fun continueWithToolResult(
        tool: ToolType,
        result: Any?,
        context: ContextSnapshot
    ): OrchestratorDecision {
        val resultMessage = when (tool) {
            ToolType.GET_UI -> {
                val uiTree = result as? String ?: "UI树不可用"
                "UI树信息：\n$uiTree\n\n请根据此信息继续决策。"
            }
            ToolType.GET_HISTORY_SCREENSHOT -> {
                if (result is Bitmap) {
                    val base64 = bitmapToBase64(result)
                    conversationHistory.add(ChatMessage("user", listOf(
                        ContentPart(
                            type = "image_url",
                            image_url = ImageUrl("data:image/png;base64,$base64")
                        ),
                        ContentPart(type = "text", text = "这是你请求的历史截图，请继续决策。")
                    )))
                    // 直接请求 AI
                    return requestDecision(context)
                } else {
                    "历史截图不可用。请继续决策。"
                }
            }
            ToolType.GET_HISTORY_UI -> {
                val uiTree = result as? String ?: "历史UI树不可用"
                "历史UI树信息：\n$uiTree\n\n请继续决策。"
            }
        }

        conversationHistory.add(ChatMessage("user", resultMessage))
        return requestDecision(context)
    }

    /**
     * 添加笔记
     */
    fun addNote(note: String) {
        notes.add(note)
        Log.d(TAG, "笔记添加: $note")
    }

    /**
     * 获取所有笔记
     */
    fun getNotes(): List<String> = notes.toList()

    /**
     * 清除历史
     */
    fun clearHistory() {
        conversationHistory.clear()
        notes.clear()
    }

    // ==================== 私有方法 ====================

    private suspend fun requestDecision(context: ContextSnapshot): OrchestratorDecision {
        return try {
            val config = settingsRepository.loadProviderConfig(ApiProvider.ZHIPU)
            val response = aiClient.sendMessage(conversationHistory, overrideConfig = config)
            val content = response.content ?: ""
            conversationHistory.add(ChatMessage("assistant", content))
            parseDecision(content, context)
        } catch (e: Exception) {
            Log.e(TAG, "决策请求失败", e)
            OrchestratorDecision(DecisionType.ERROR, message = "决策失败: ${e.message}")
        }
    }

    private fun getPlanningPrompt(): String {
        val dateFormat = SimpleDateFormat("yyyy年MM月dd日 EEEE", Locale.CHINA)
        val dateStr = dateFormat.format(Date())
        
        return """
今天是：$dateStr

你是 Android 智能助手的任务规划专家。你的职责是将用户请求分解为可执行的子任务。
如果任务不明确，可以询问用户澄清。

【App 映射知识】
- 点外卖/买奶茶/买咖啡 → 美团、饿了么、瑞幸
- 网购/买东西 → 淘宝、京东、拼多多
- 打车 → 滴滴、高德、百度地图
- 发消息/聊天 → 微信、QQ
- 订酒店/机票 → 携程、飞猪
- 搜索信息 → 浏览器、抹音、小红书

【执行规则摘要（小模型会遵守，规划时需考虑）】
1. 执行前先检查是否在目标App
2. 页面未加载时最多等待3次
3. 遇到网络问题点击重新加载
4. 找不到目标时尝试滑动查找
5. 筛选条件可适当放宽
6. 购物车操作前先清空选中状态
7. 外卖任务尽量在同一店铺购买
8. 遵循用户特殊要求（如口味、价格）
9. 确认支付前暂停等待用户
10. 登录/验证时请求用户介入
11. 如果用户指定了具体价格如9.9,请上下浮动10%.你输出价格应该是9-10块.

【决策类型】
1. PLAN - 任务明确，返回步骤列表
2. ASK_USER - 任务不明确，需要询问用户澄清

【输出格式】
任务明确时：
{"type": "PLAN", "selectedApp": "App名", "steps": ["步骤1", "步骤2", ...], "note": "规划说明"}

任务不明确时：
{"type": "ASK_USER", "question": "您想点外卖还是在淘宝买奶茶粉？"}
        """.trimIndent()
    }

    private fun getReviewPrompt(): String {
        return """
你是任务执行监督专家。根据小模型的执行汇报和当前截图，做出下一步决策。

[当前界面截图]

【决策类型】
1. NEXT_STEP - 继续执行（当前步骤OK）
2. REPLAN - 重新规划（遇到障碍需要换路径）
3. FINISH - 任务完成
4. GET_INFO - 需要更多信息（使用工具）
5. NOTE - 记录关键数据
6. ERROR - 无法继续（说明原因）

【可用工具】
- GetUI: 获取当前页面UI树
- GetHistoryScreenshot: 获取历史截图 (step=1-5)
- GetHistoryUI: 获取历史UI树 (step=1-3)

【异常处理】
- 连续3次相同操作无效 → REPLAN
- 出现登录/验证弹窗 → 暂停请求用户介入
- App崩溃 → 重新启动

【输出格式】
{
  "type": "NEXT_STEP|REPLAN|FINISH|GET_INFO|NOTE|ERROR",
  "reason": "决策理由",
  "nextStep": "下一步指令",
  "newSteps": ["新步骤..."],
  "message": "完成/错误消息",
  "tool": "工具名",
  "step": 2,
  "note": "笔记内容"
}
        """.trimIndent()
    }

    private fun buildReviewPrompt(report: WorkerReport, context: ContextSnapshot): String {
        return buildString {
            append("【任务目标】${context.goal}\n\n")

            append("【当前进度】第 ${context.currentStep}/${context.totalSteps} 步\n")
            append("当前子任务: ${report.subTask}\n\n")

            append("【执行汇报】\n")
            append("状态: ${report.status}\n")
            append("执行了 ${report.stepsTaken} 步操作:\n")
            report.actions.forEachIndexed { i, action ->
                val result = if (report.results.getOrNull(i) == true) "✅" else "❌"
                append("  ${i + 1}. $action $result\n")
            }
            if (report.message.isNotBlank()) {
                append("附加信息: ${report.message}\n")
            }
            append("\n")

            if (context.textHistory.isNotEmpty()) {
                append("【历史记录】\n")
                context.textHistory.takeLast(5).forEach { append("$it\n") }
                append("\n")
            }

            if (notes.isNotEmpty()) {
                append("【笔记】\n")
                notes.forEach { append("- $it\n") }
                append("\n")
            }

            append("【当前App】${context.currentApp}\n")
            append("（已提供当前屏幕截图）\n\n")
            append("请审查并决定下一步。")
        }
    }

    private fun parseTaskPlan(response: String, fallbackGoal: String): TaskPlan {
        return try {
            val jsonStr = response
                .replace(Regex("```json\\s*"), "")
                .replace(Regex("```\\s*"), "")
                .trim()

            val json = JSONObject(jsonStr)
            val stepsArray = json.optJSONArray("steps") ?: JSONArray()
            val steps = (0 until stepsArray.length()).map { stepsArray.getString(it) }

            TaskPlan(goal = fallbackGoal, steps = steps.ifEmpty { listOf(fallbackGoal) })
        } catch (e: Exception) {
            Log.w(TAG, "计划解析失败，使用降级", e)
            TaskPlan(goal = fallbackGoal, steps = listOf(fallbackGoal))
        }
    }

    private fun parseDecision(response: String, context: ContextSnapshot): OrchestratorDecision {
        return try {
            val jsonStr = response
                .replace(Regex("```json\\s*"), "")
                .replace(Regex("```\\s*"), "")
                .trim()

            val json = JSONObject(jsonStr)
            val typeStr = json.optString("type", "NEXT_STEP")
            
            val type = try {
                DecisionType.valueOf(typeStr.uppercase())
            } catch (e: Exception) {
                DecisionType.NEXT_STEP
            }

            // 处理笔记
            val noteContent = json.optString("note", "")
            if (noteContent.isNotBlank()) {
                addNote(noteContent)
            }

            when (type) {
                DecisionType.NEXT_STEP -> {
                    val nextStep = json.optString("nextStep", "")
                    if (nextStep.isBlank() && context.currentStep < context.totalSteps) {
                        // 如果没有指定，取下一个计划步骤
                        OrchestratorDecision(
                            type = DecisionType.NEXT_STEP,
                            nextStep = context.plan?.steps?.getOrNull(context.currentStep) ?: "",
                            message = json.optString("reason", "")
                        )
                    } else {
                        OrchestratorDecision(
                            type = DecisionType.NEXT_STEP,
                            nextStep = nextStep,
                            message = json.optString("reason", "")
                        )
                    }
                }
                DecisionType.REPLAN -> {
                    val newStepsArray = json.optJSONArray("newSteps") ?: JSONArray()
                    val newSteps = (0 until newStepsArray.length()).map { newStepsArray.getString(it) }
                    OrchestratorDecision(
                        type = DecisionType.REPLAN,
                        newSteps = newSteps,
                        message = json.optString("reason", "")
                    )
                }
                DecisionType.FINISH -> {
                    OrchestratorDecision(
                        type = DecisionType.FINISH,
                        message = json.optString("message", "任务完成")
                    )
                }
                DecisionType.GET_INFO -> {
                    val toolStr = json.optString("tool", "")
                    val step = json.optInt("step", -1)
                    val toolType = when (toolStr.uppercase()) {
                        "GETUI", "GET_UI" -> ToolType.GET_UI
                        "GETHISTORYSCREENSHOT", "GET_HISTORY_SCREENSHOT" -> ToolType.GET_HISTORY_SCREENSHOT
                        "GETHISTORYUI", "GET_HISTORY_UI" -> ToolType.GET_HISTORY_UI
                        else -> ToolType.GET_UI
                    }
                    OrchestratorDecision(
                        type = DecisionType.GET_INFO,
                        tool = ToolRequest(toolType, if (step > 0) step else null),
                        message = json.optString("reason", "")
                    )
                }
                DecisionType.ERROR -> {
                    OrchestratorDecision(
                        type = DecisionType.ERROR,
                        message = json.optString("message", "未知错误")
                    )
                }
                DecisionType.ASK_USER -> {
                    OrchestratorDecision(
                        type = DecisionType.ASK_USER,
                        message = json.optString("message", "需要用户介入")
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "决策解析失败", e)
            // 默认继续下一步
            OrchestratorDecision(
                type = DecisionType.NEXT_STEP,
                nextStep = context.plan?.steps?.getOrNull(context.currentStep) ?: "",
                message = "解析失败，默认继续"
            )
        }
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 85, outputStream)
        return Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
    }
}
