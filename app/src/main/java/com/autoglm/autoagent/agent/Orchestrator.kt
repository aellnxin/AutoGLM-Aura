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
            PlanResult.Plan(TaskPlan.fromStringList(goal, listOf(goal)))
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
                    val stepsArray = json.optJSONArray("steps") ?: JSONArray()
                    val steps = mutableListOf<String>()
                    for (i in 0 until stepsArray.length()) {
                        steps.add(stepsArray.getString(i))
                    }
                    if (steps.isEmpty()) {
                        steps.add(goal)
                    }
                    PlanResult.Plan(TaskPlan.fromStringList(goal, steps))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "解析规划结果失败: ${e.message}")
            // 降级处理
            PlanResult.Plan(TaskPlan.fromStringList(goal, listOf(goal)))
        }
    }
    
    /**
     * 用户回答后重新规划任务
     * 将用户回复追加到对话历史中，让模型基于完整上下文重新规划
     */
    suspend fun replanWithUserAnswer(answer: String, context: ContextSnapshot): TaskPlan {
        // 将用户回复追加到对话历史
        conversationHistory.add(ChatMessage(
            role = "user",
            content = "用户补充信息: $answer\n\n请根据此信息重新规划任务步骤。"
        ))

        return try {
            val config = settingsRepository.loadProviderConfig(ApiProvider.ZHIPU)
            val response = aiClient.sendMessage(conversationHistory, overrideConfig = config)
            val responseText = response.toString()
            
            // 将模型回复也添加到历史
            conversationHistory.add(ChatMessage(role = "assistant", content = responseText))
            
            parseTaskPlan(responseText, context.goal)
        } catch (e: Exception) {
            Log.e(TAG, "重新规划失败", e)
            // 降级：使用用户回复作为新步骤
            TaskPlan.fromStringList(context.goal, listOf(answer))
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

【App 映射,不重要,只是你需要理解关系】
- 点外卖/买奶茶/买咖啡 → 美团、饿了么、瑞幸
- 网购/买东西 → 淘宝、京东、拼多多
- 打车 → 滴滴、高德、百度地图
- 发消息/聊天 → 微信、QQ
- 订酒店/机票 → 携程、飞猪
- 搜索信息 → 浏览器、抹音、小红书

【执行规则摘要（小模型会遵守，规划时需考虑）】
1. 执行前先检查是否在目标App,默认打开目标app
2. 页面未加载时最多等待3次
3. 遇到网络问题点击重新加载
4. 找不到目标时尝试滑动查找
5. 价格理解规则
   (a) 单品场景（无数量词）：
       "9.9的辣条" → 单品价格上限 9.9，下限 9.0（下浮10%）
   
   (b) 多品场景（有数量词 + 有价格）：
       判断依据：是否包含"每个/单个/一个/单价"等单品关键词
       
       - 包含单品词："两个披萨，每个不超过50" → 单品价格≤50
       - 不包含（默认）："两个披萨不超过50" → 总价≤50 → 推算单价≤25
   
   (c) 推算公式：
       单品价格上限 = 总价上限 ÷ 数量
       单品价格下限 = 单品价格上限 × 0.9（下浮10%）
6. 购物车操作前先清空选中状态
7. 外卖任务尽量在同一店铺购买
8. 遵循用户特殊要求（如口味、价格）
9. 确认支付前暂停等待用户
10. 登录/验证时请求用户介入
12. 面对模糊数量词（如‘点几个外卖’、‘点一点赞’），必须根据任务风险等级进行分层处理：
高风险/交易类（买东西、转账、发消息等任务）： 禁止自动填充数字。必须触发‘澄清机制’（ASK_USER），要求用户确认具体数量，或默认设置为最小单位（1）并停留在确认页面等待用户点击。
反例： ‘帮忙订购几个外卖’ -> 不应直接生成订单，而应回复‘已为您打开外卖页面，请确认需要订购的具体份数’或默认选1份但在支付前强阻断。
低风险/操作类（点赞、刷新、下滑等任务）： 设定一个符合人类行为习惯的小随机范围（如 3-8 次）的固定数字，以方便后续智能体执行。

【决策类型】
1. PLAN - 任务明确，返回步骤列表
2. ASK_USER - 任务不明确，需要询问用户澄清

【输出格式】
任务明确时：
{"type": "PLAN", "steps": ["步骤1", "步骤2", ...], "note": "规划说明"}

任务不明确时：
{"type": "ASK_USER", "question": "您想点外卖还是在淘宝买奶茶粉？"}
        """.trimIndent()
    }

    private fun getReviewPrompt(): String {
        return """
你是安卓智能体任务执行监督专家。根据用户指令、任务规划、小模型的执行汇报以及当前截图(或ui树)和执行历史，判断当前步骤是否完成，如果完成则做出下一步任务规划的决策,如果你判断未完成，请重新规划剩余任务。
注意!请严格遵守执行规则特别是涉及到金钱方面,不要出错

【用户指令】



【任务规划】
步骤状态说明：
- [x] 已完成的步骤
- [/] 当前正在执行的步骤（小模型正在处理这一步）
- [ ] 尚未开始的步骤

你的职责：判断 [/] 标记的当前步骤是否真正完成。如果完成，使用 NEXT_STEP 推进到下一步。



【小模型执行汇报】



【当前界面截图】



【执行历史】



【记录关键数据】



【决策类型】
1. NEXT_STEP - 当前 [/] 步骤已完成，推进到下一步
   使用场景: 当前步骤成功完成，界面状态符合预期
   效果: 系统会自动将 [/] 标记为 [x]，并将下一个 [ ] 标记为 [/]
   必填字段: nextStep（复制下一个 [ ] 步骤的内容）

2. REPLAN - 需要重新规划路径
   使用场景: 小模型卡在循环中 / 当前路径走不通 / 发现更优方案
   必填字段: newSteps（新的步骤列表）, reason（重新规划的原因）

3. FINISH - 任务已完成
   使用场景: 截图显示已到达预期最终状态（如订单确认页、支付成功页）
   必填字段: message（完成总结）
   注意: 必须仔细核对当前界面与用户指令是否一致！

4. GET_INFO - 需要更多信息来做决策
   使用场景: 截图看不清细节 / 需要回顾历史步骤确认状态变化
   必填字段: tool（工具名：GetUI/GetHistoryScreenshot/GetHistoryUI）

5. NOTE - 记录关键数据供后续使用
   使用场景: 发现重要信息需要跨步骤记忆（如商品价格、订单号、商品名称等）
   必填字段: note（笔记内容）

6. ERROR - 无法继续执行
   使用场景: 遇到不可恢复的错误（如App无法打开、任务本身不可能完成）
   必填字段: message（错误原因）

【可用工具】
- GetUI: 获取当前页面UI树
- GetHistoryScreenshot: 获取历史截图 (step=1-5)
- GetHistoryUI: 获取历史UI树 (step=1-3)

【异常处理】
- 连续3次相同操作无效 → REPLAN
- 出现登录/验证弹窗 → 暂停请求用户介入
- App崩溃 → 重新启动
- 如果出现价格,你需要严格比对用户的目标和当前价格,如果价格过高,请根据小模型的步骤看看是那里出错了然后重新规划任务让小模型执行.
- 如果小模型报告任务完成,你需要根据当前界面和用户的期望结果判断是否真的完成,如与用户指令和任务规划不一致,请根据小模型的步骤看看是那里出错了然后重新规划任务让小模型执行.

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
            // 【用户指令】
            append("【用户指令】\n")
            append(context.goal)
            append("\n\n")

            // 【任务规划】
            append("【任务规划】\n")
            context.plan?.let { plan ->
                append(plan.toDisplayString())
                append("\n当前进度: ${plan.currentStepIndex + 1}/${plan.steps.size}\n")
            } ?: append("(无规划)\n")
            append("\n")

            // 【小模型执行汇报】
            append("【小模型执行汇报】\n")
            append("当前子任务: ${report.subTask}\n")
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

            // 【当前界面截图】
            append("【当前界面截图】\n")
            append("（已提供当前屏幕截图）\n")
            append("当前App: ${context.currentApp}\n\n")

            // 【执行历史】
            append("【执行历史】\n")
            if (context.textHistory.isNotEmpty()) {
                context.textHistory.takeLast(8).forEach { append("$it\n") }
            } else {
                append("(暂无历史)\n")
            }
            append("\n")

            // 【记录关键数据】
            append("【记录关键数据】\n")
            if (notes.isNotEmpty()) {
                notes.forEach { append("- $it\n") }
            } else {
                append("(暂无笔记)\n")
            }
            append("\n")

            append("请根据以上信息审查并决定下一步。")
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

            TaskPlan.fromStringList(fallbackGoal, steps.ifEmpty { listOf(fallbackGoal) })
        } catch (e: Exception) {
            Log.w(TAG, "计划解析失败，使用降级", e)
            TaskPlan.fromStringList(fallbackGoal, listOf(fallbackGoal))
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
                            nextStep = context.plan?.steps?.getOrNull(context.currentStep)?.description ?: "",
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
                nextStep = context.plan?.steps?.getOrNull(context.currentStep)?.description ?: "",
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
