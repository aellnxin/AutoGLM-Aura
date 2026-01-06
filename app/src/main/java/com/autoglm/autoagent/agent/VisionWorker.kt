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
import com.autoglm.autoagent.executor.FallbackActionExecutor
import com.autoglm.autoagent.service.AutoAgentService
import com.autoglm.autoagent.service.AppManager
import kotlinx.coroutines.delay
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.regex.Pattern
import javax.inject.Inject
import javax.inject.Singleton

/**
 * VisionWorker - 小模型执行器
 * 
 * 职责：
 * 1. 接收子任务指令，自主决定具体操作
 * 2. 纯视觉定位，基于截图识别和操作
 * 3. 执行操作并缓存截图
 * 4. 汇报执行结果 (完成/3步/异常)
 * 
 * ⚠️ 小模型不处理复杂异常，遇到问题直接上报大模型
 */
@Singleton
class VisionWorker @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val aiClient: AIClient,
    private val actionExecutor: FallbackActionExecutor,
    private val appManager: AppManager
) {
    companion object {
        private const val TAG = "VisionWorker"
        private const val MAX_STEPS_PER_SUBTASK = 3  // 每个子任务最多3步
    }

    // 执行历史 (用于汇报)
    private val executedActions = mutableListOf<String>()
    private val actionResults = mutableListOf<Boolean>()
    private var currentScreenshot: Bitmap? = null

    // 重复检测
    private var lastAction = ""
    private var sameActionCount = 0

    // ==================== 公共接口 ====================

    /**
     * 检查小模型是否可用
     */
    fun checkAvailability(): Boolean {
        return try {
            val config = settingsRepository.loadProviderConfig(ApiProvider.EDGE)
            config.apiKey.isNotBlank() && config.baseUrl.isNotBlank()
        } catch (e: Exception) {
            Log.e(TAG, "可用性检查失败", e)
            false
        }
    }

    /**
     * 执行单步操作（持续执行模式）
     * @param goal 任务目标
     * @return WorkerReport 执行汇报
     */
    suspend fun executeSingleStep(goal: String): WorkerReport {
        // 截图
        delay(300)
        currentScreenshot = captureScreenshot()
        val currentApp = AutoAgentService.instance?.currentPackageName ?: "Unknown"

        // 调用 AI 决策
        val aiResponse = try {
            callAI(goal, currentScreenshot, currentApp, totalStepCount)
        } catch (e: Exception) {
            Log.e(TAG, "AI 调用失败", e)
            return WorkerReport(
                subTask = goal,
                stepsTaken = 1,
                actions = listOf("AI调用失败"),
                results = listOf(false),
                currentScreenshot = currentScreenshot,
                status = WorkerStatus.FAILED,
                message = "AI 调用失败: ${e.message}"
            )
        }

        // 解析响应
        val (think, actionStr) = parseResponse(aiResponse)
        Log.d(TAG, "Step $totalStepCount: $actionStr")
        totalStepCount++

        // 检查是否完成
        if (actionStr.contains("finish(", ignoreCase = true)) {
            return WorkerReport(
                subTask = goal,
                stepsTaken = 1,
                actions = listOf("Finish"),
                results = listOf(true),
                currentScreenshot = currentScreenshot,
                status = WorkerStatus.COMPLETED,
                message = extractFinishMessage(actionStr)
            )
        }

        // 检查是否需要用户介入
        if (actionStr.contains("Take_over", ignoreCase = true) || 
            actionStr.contains("Interact", ignoreCase = true)) {
            return WorkerReport(
                subTask = goal,
                stepsTaken = 1,
                actions = listOf("NeedsUser"),
                results = listOf(true),
                currentScreenshot = currentScreenshot,
                status = WorkerStatus.NEEDS_USER,
                message = extractMessage(actionStr)
            )
        }

        // 检查是否卡死
        if (checkStuck(actionStr)) {
            return WorkerReport(
                subTask = goal,
                stepsTaken = 1,
                actions = listOf(parseActionName(actionStr)),
                results = listOf(false),
                currentScreenshot = currentScreenshot,
                status = WorkerStatus.STUCK,
                message = "连续3次执行相同操作无效"
            )
        }

        // 执行操作
        val actionName = parseActionName(actionStr)
        val success = executeAction(actionStr)

        delay(500) // 等待操作生效

        return WorkerReport(
            subTask = goal,
            stepsTaken = 1,
            actions = listOf(actionName),
            results = listOf(success),
            currentScreenshot = currentScreenshot,
            status = WorkerStatus.IN_PROGRESS,
            message = if (success) "" else "操作执行失败"
        )
    }

    // 总步数计数（跨轮次）
    private var totalStepCount = 0
    
    fun resetStepCount() {
        totalStepCount = 0
        lastAction = ""
        sameActionCount = 0
    }

    /**
     * 执行子任务
     * @param instruction 子任务指令 (自然语言，如 "打开微信搜索张三")
     * @return WorkerReport 执行汇报
     */
    suspend fun executeSubTask(instruction: String): WorkerReport {
        executedActions.clear()
        actionResults.clear()
        lastAction = ""
        sameActionCount = 0

        Log.i(TAG, "开始执行子任务: $instruction")

        var stepCount = 0
        var status = WorkerStatus.IN_PROGRESS
        var message = ""

        while (stepCount < MAX_STEPS_PER_SUBTASK && status == WorkerStatus.IN_PROGRESS) {
            stepCount++

            // 1. 截图
            delay(300)  // 等待界面稳定
            currentScreenshot = captureScreenshot()
            val currentApp = AutoAgentService.instance?.currentPackageName ?: "Unknown"

            // 2. 调用 AI 决策
            val aiResponse = try {
                callAI(instruction, currentScreenshot, currentApp, stepCount)
            } catch (e: Exception) {
                Log.e(TAG, "AI 调用失败", e)
                status = WorkerStatus.FAILED
                message = "AI 调用失败: ${e.message}"
                break
            }

            // 3. 解析响应
            val (think, actionStr) = parseResponse(aiResponse)
            Log.d(TAG, "Step $stepCount: $actionStr (思考: ${think.take(50)}...)")

            // 4. 检查是否完成
            if (actionStr.contains("finish(", ignoreCase = true)) {
                status = WorkerStatus.COMPLETED
                message = extractFinishMessage(actionStr)
                executedActions.add("Finish")
                actionResults.add(true)
                break
            }

            // 5. 检查是否需要用户介入
            if (actionStr.contains("Take_over", ignoreCase = true) || 
                actionStr.contains("Interact", ignoreCase = true)) {
                status = WorkerStatus.NEEDS_USER
                message = extractMessage(actionStr)
                executedActions.add("NeedsUser")
                actionResults.add(true)
                break
            }

            // 6. 检查是否卡死
            if (checkStuck(actionStr)) {
                status = WorkerStatus.STUCK
                message = "连续3次执行相同操作无效"
                break
            }

            // 7. 执行操作
            val actionName = parseActionName(actionStr)
            executedActions.add(actionName)

            val success = executeAction(actionStr)
            actionResults.add(success)

            if (!success) {
                // 单步失败不立即终止，继续尝试
                Log.w(TAG, "操作执行失败: $actionStr")
            }

            // 8. 等待操作生效
            delay(500)
        }

        // 生成汇报
        return WorkerReport(
            subTask = instruction,
            stepsTaken = stepCount,
            actions = executedActions.toList(),
            results = actionResults.toList(),
            currentScreenshot = currentScreenshot,
            status = status,
            message = message
        )
    }

    // ==================== 私有方法 ====================

    private suspend fun captureScreenshot(): Bitmap? {
        val accessibilityService = AutoAgentService.instance
        return if (accessibilityService != null && 
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            accessibilityService.takeScreenshotAsync()
        } else {
            null
        }
    }

    private suspend fun callAI(
        instruction: String,
        screenshot: Bitmap?,
        currentApp: String,
        stepCount: Int
    ): String {
        val contentParts = mutableListOf<ContentPart>()

        // 添加截图
        if (screenshot != null) {
            val base64 = bitmapToBase64(screenshot)
            contentParts.add(ContentPart(
                type = "image_url",
                image_url = ImageUrl("data:image/png;base64,$base64")
            ))
        }

        // 构建文本
        val textContent = buildString {
            append("【子任务】$instruction\n\n")
            append("【当前状态】\n")
            append("Step: $stepCount / $MAX_STEPS_PER_SUBTASK\n")
            append("当前App: $currentApp\n")
            if (executedActions.isNotEmpty()) {
                append("\n【已执行】\n")
                executedActions.forEachIndexed { i, action ->
                    val result = if (actionResults.getOrNull(i) == true) "✅" else "❌"
                    append("  ${i + 1}. $action $result\n")
                }
            }
            append("\n请根据截图决定下一步操作，继续完成子任务。")
        }
        contentParts.add(ContentPart(type = "text", text = textContent))

        val messages = listOf(
            ChatMessage("system", getSystemPrompt()),
            ChatMessage("user", contentParts)
        )

        val config = settingsRepository.loadProviderConfig(ApiProvider.EDGE)
        val response = aiClient.sendMessage(messages, overrideConfig = config)
        return response.content ?: ""
    }

    private fun getSystemPrompt(): String {
        val dateFormat = SimpleDateFormat("yyyy年MM月dd日 EEEE", Locale.CHINA)
        val dateStr = dateFormat.format(Date())

        return """
今天的日期是: $dateStr

你是一个智能体分析专家，能够结合屏幕截图来精准执行操作。每轮对话你会收到：
1. 当前屏幕截图（视觉上下文）
2. 当前App名称

你必须严格按照要求输出以下格式：
<think>{think}</think>
<answer>{action}</answer>

其中：
- {think} 是对你为什么选择这个操作的简短推理说明。
- {action} 是本次执行的具体操作指令，必须严格遵循下方定义的指令格式。

操作指令及其作用如下：
- do(action="Launch", app="xxx")  
    Launch是启动目标app的操作，这比通过主屏幕导航更快。此操作完成后，您将自动收到结果状态的截图。
- do(action="Tap", element=[x,y])  
    Tap是点击操作，点击屏幕上的特定点。可用此操作点击按钮、选择项目、从主屏幕打开应用程序，或与任何可点击的用户界面元素进行交互。坐标系统从左上角 (0,0) 开始到右下角（999,999)结束。此操作完成后，您将自动收到结果状态的截图。
- do(action="Tap", element=[x,y], message="重要操作")  
    基本功能同Tap，点击涉及财产、支付、隐私等敏感按钮时触发。
- do(action="Type", text="xxx")  
    Type是输入操作，在当前聚焦的输入框中输入文本。使用此操作前，请确保输入框已被聚焦（先点击它）。输入的文本将像使用键盘输入一样输入。重要提示：手机可能正在使用 ADB 键盘，该键盘不会像普通键盘那样占用屏幕空间。要确认键盘已激活，请查看屏幕底部是否显示 'ADB Keyboard {ON}' 类似的文本，或者检查输入框是否处于激活/高亮状态。不要仅仅依赖视觉上的键盘显示。自动清除文本：当你使用输入操作时，输入框中现有的任何文本（包括占位符文本和实际输入）都会在输入新文本前自动清除。你无需在输入前手动清除文本——直接使用输入操作输入所需文本即可。
- do(action="Interact")  
    Interact是当有多个满足条件的选项时而触发的交互操作，询问用户如何选择。
- do(action="Swipe", start=[x1,y1], end=[x2,y2])  
    Swipe是滑动操作，通过从起始坐标拖动到结束坐标来执行滑动手势。可用于滚动内容、在屏幕之间导航、下拉通知栏以及项目栏或进行基于手势的导航。坐标系统从左上角 (0,0) 开始到右下角（999,999)结束。滑动持续时间会自动调整以实现自然的移动。此操作完成后，您将自动收到结果状态的截图。
- do(action="Note", message="xxx")  
    做笔记的操作，用于跨应用记录当前应用页面的关键信息、数据或状态，以便在后续步骤（跨应用时）查阅。笔记内容将在后续每一轮的上下文中展示。
- do(action="Call_API", instruction="xxx")  
    总结或评论当前页面内容，或基于已记录的笔记进行逻辑汇总。
- do(action="Long Press", element=[x,y])  
    Long Press是长按操作，在屏幕上的特定点长按指定时间。可用于触发上下文菜单、选择文本或激活长按交互。坐标系统从左上角 (0,0) 开始到右下角（999,999)结束。此操作完成后，您将自动收到结果状态的屏幕截图。
- do(action="Double Tap", element=[x,y])  
    Double Tap在屏幕上的特定点快速连续点按两次。使用此操作可以激活双击交互，如缩放、选择文本或打开项目。坐标系统从左上角 (0,0) 开始到右下角（999,999)结束。此操作完成后，您将自动收到结果状态的截图。
- do(action="Take_over", message="xxx")  
    Take_over是接管操作，表示在登录和验证阶段需要用户协助。
- do(action="Back")  
    导航返回到上一个屏幕或关闭当前对话框。相当于按下 Android 的返回按钮。使用此操作可以从更深的屏幕返回、关闭弹出窗口或退出当前上下文。此操作完成后，您将自动收到结果状态的截图。
- do(action="Home") 
    Home是回到系统桌面的操作，相当于按下 Android 主屏幕按钮。使用此操作可退出当前应用并返回启动器，或从已知状态启动新任务。此操作完成后，您将自动收到结果状态的截图。
- do(action="Wait", duration="x seconds")  
    等待页面加载，x为需要等待多少秒。
- finish(message="xxx")  
    finish是结束当前子任务的操作，表示子任务已完成，message是完成信息。

必须遵循的规则：
1. 在执行任何操作前，先检查当前app是否是目标app，如果不是，先执行 Launch。
2. 如果进入到了无关页面，先执行 Back。如果执行Back后页面没有变化，请点击页面左上角的返回键进行返回，或者右上角的X号关闭。
3. 如果页面未加载出内容，最多连续 Wait 三次，否则执行 Back重新进入。
4. 如果页面显示网络问题，需要重新加载，请点击重新加载.如果没有重新加载按钮请重新打开app.
5. 如果当前页面找不到目标联系人、商品、店铺等信息，可以尝试 Swipe 滑动查找。
6. 遇到价格区间、时间区间等筛选条件，如果没有完全符合的，可以放宽要求。
7. 在做小红书总结类任务时一定要筛选图文笔记。
8. 购物车全选后再点击全选可以把状态设为全不选，在做购物车任务时，如果购物车里已经有商品被选中时，你需要点击全选后再点击取消全选，再去找需要购买或者删除的商品。
9. 在做外卖任务时，如果相应店铺购物车里已经有其他商品你需要先把购物车清空再去购买用户指定的外卖。
10. 在做点外卖任务时，如果用户需要点多个外卖，请尽量在同一店铺进行购买，如果无法找到可以下单，并说明某个商品未找到。
11. 请严格遵循用户意图执行任务，用户的特殊要求可以执行多次搜索，滑动查找。比如（i）用户要求点一杯咖啡，要咸的，你可以直接搜索咸咖啡，或者搜索咖啡后滑动查找咸的咖啡，比如海盐咖啡。（ii）用户要找到XX群，发一条消息，你可以先搜索XX群，找不到结果后，将"群"字去掉，搜索XX重试。（iii）用户要找到宠物友好的餐厅，你可以搜索餐厅，找到筛选，找到设施，选择可带宠物，或者直接搜索可带宠物，必要时可以使用AI搜索。
12. 在选择日期时，如果原滑动方向与预期日期越来越远，请向反方向滑动查找。
13. 执行任务过程中如果有多个可选择的项目栏，请逐个查找每个项目栏，直到完成任务，一定不要在同一项目栏多次查找，从而陷入死循环。
14. 在执行下一步操作前请一定要检查上一步的操作是否生效，如果点击没生效，可能因为app反应较慢，请先稍微等待一下，如果还是不生效请调整一下点击位置重试，如果仍然不生效请跳过这一步继续任务，并在finish message说明点击不生效。
15. 在执行任务中如果遇到滑动不生效的情况，请调整一下起始点位置，增大滑动距离重试，如果还是不生效，有可能是已经滑到底了，请继续向反方向滑动，直到顶部或底部，如果仍然没有符合要求的结果，请跳过这一步继续任务，并在finish message说明但没找到要求的项目。
16. 在做游戏任务时如果在战斗页面如果有自动战斗一定要开启自动战斗，如果多轮历史状态相似要检查自动战斗是否开启。
17. 如果没有合适的搜索结果，可能是因为搜索页面不对，请返回到搜索页面的上一级尝试重新搜索，如果尝试三次返回上一级搜索后仍然没有符合要求的结果，执行 finish(message="原因")。
18. 在结束任务前请一定要仔细检查任务是否完整准确的完成，如果出现错选、漏选、多选的情况，请返回之前的步骤进行纠正.
19. 如果执行Launch启动app后显示的界面不是符合的界面,请使用home操作返回到主屏幕后尝试通过桌面找到app后使用Tap点击操作启动app,如果桌面找不到请结束任务.
        """.trimIndent()
    }

    private fun parseResponse(content: String): Pair<String, String> {
        var action = ""
        var think = ""

        // 提取 answer
        val answerMatcher = Pattern.compile("<answer>(.*?)</answer", Pattern.DOTALL).matcher(content)
        if (answerMatcher.find()) {
            action = answerMatcher.group(1).trim()
        } else {
            // 兜底：找 do(...) 或 finish(...)
            val cmdP = Pattern.compile("(do\\s*\\(.*?\\)|finish\\s*\\(.*?\\))", Pattern.DOTALL)
            val cmdM = cmdP.matcher(content)
            if (cmdM.find()) {
                action = cmdM.group(1).trim()
            }
        }

        // 提取 think
        val thinkP = Pattern.compile("<think>\\s*(.*?)\\s*</think>", Pattern.DOTALL).matcher(content)
        if (thinkP.find()) {
            think = thinkP.group(1).trim()
        }

        // 规范化 action
        if (action.startsWith("{") && action.contains("}")) {
            val endBrace = action.indexOf("}")
            action = action.substring(1, endBrace).trim()
        }
        if (action.startsWith("action=") && !action.startsWith("do(")) {
            action = "do($action)"
        }
        if (action.contains(")")) {
            action = action.substring(0, action.indexOf(")") + 1)
        }

        return Pair(think, action)
    }

    private fun checkStuck(actionStr: String): Boolean {
        if (actionStr == lastAction) {
            sameActionCount++
        } else {
            sameActionCount = 1
            lastAction = actionStr
        }
        return sameActionCount >= 3
    }

    private suspend fun executeAction(actionStr: String): Boolean {
        val trimmed = actionStr.trim()

        // 解析 action 名称
        val actionPattern = Pattern.compile("action\\s*=\\s*[\"'](.*?)[\"']", Pattern.CASE_INSENSITIVE)
        val matcher = actionPattern.matcher(trimmed)
        val actionName = if (matcher.find()) {
            matcher.group(1).lowercase(Locale.ROOT)
        } else {
            return false
        }

        return try {
            when (actionName) {
                "tap", "click" -> {
                    val coords = parseElement(trimmed) ?: return false
                    actionExecutor.tap(coords.first.toFloat(), coords.second.toFloat())
                }
                "type", "input" -> {
                    val text = parseText(trimmed) ?: return false
                    actionExecutor.inputText(text)
                }
                "swipe", "scroll" -> {
                    val start = parseStart(trimmed) ?: return false
                    val end = parseEnd(trimmed) ?: return false
                    actionExecutor.scroll(
                        start.first.toFloat(), start.second.toFloat(),
                        end.first.toFloat(), end.second.toFloat()
                    )
                }
                "back" -> actionExecutor.pressBack()
                "home" -> actionExecutor.pressHome()
                "launch", "open" -> {
                    val appName = parseApp(trimmed) ?: return false
                    appManager.launchApp(appName)
                }
                "wait" -> {
                    val seconds = parseDuration(trimmed)
                    delay(seconds * 1000L)
                    true
                }
                "long press", "long_press" -> {
                    val coords = parseElement(trimmed) ?: return false
                    actionExecutor.longPress(coords.first.toFloat(), coords.second.toFloat())
                }
                "double tap", "double_tap" -> {
                    val coords = parseElement(trimmed) ?: return false
                    actionExecutor.doubleTap(coords.first.toFloat(), coords.second.toFloat())
                }
                "note" -> {
                    // Note 操作：记录笔记，由上层处理
                    val message = extractMessage(trimmed)
                    Log.d(TAG, "记录笔记: $message")
                    true
                }
                "call_api" -> {
                    // Call_API 操作：总结页面，由上层处理
                    val instruction = extractMessage(trimmed)
                    Log.d(TAG, "Call_API: $instruction")
                    true
                }
                "interact" -> {
                    // Interact 操作：需要用户选择，暂停执行
                    Log.d(TAG, "Interact: 需要用户选择")
                    true
                }
                "take_over" -> {
                    // Take_over 操作：需要用户介入，暂停执行
                    val message = extractMessage(trimmed)
                    Log.d(TAG, "Take_over: $message")
                    true
                }
                else -> {
                    Log.w(TAG, "未知操作: $actionName")
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "执行失败: $actionName", e)
            false
        }
    }

    // ==================== 解析辅助 ====================

    private fun parseElement(actionStr: String): Pair<Int, Int>? {
        val regex = Regex("element\\s*=\\s*\\[\\s*(\\d+)\\s*,\\s*(\\d+)\\s*]")
        val match = regex.find(actionStr) ?: return null
        return Pair(match.groupValues[1].toInt(), match.groupValues[2].toInt())
    }

    private fun parseText(actionStr: String): String? {
        val regex = Regex("text\\s*=\\s*[\"']([^\"']*)[\"']")
        val match = regex.find(actionStr) ?: return null
        return match.groupValues[1]
    }

    private fun parseApp(actionStr: String): String? {
        val regex = Regex("app\\s*=\\s*[\"']([^\"']*)[\"']")
        val match = regex.find(actionStr) ?: return null
        return match.groupValues[1]
    }

    private fun parseStart(actionStr: String): Pair<Int, Int>? {
        val regex = Regex("start\\s*=\\s*\\[\\s*(\\d+)\\s*,\\s*(\\d+)\\s*]")
        val match = regex.find(actionStr) ?: return null
        return Pair(match.groupValues[1].toInt(), match.groupValues[2].toInt())
    }

    private fun parseEnd(actionStr: String): Pair<Int, Int>? {
        val regex = Regex("end\\s*=\\s*\\[\\s*(\\d+)\\s*,\\s*(\\d+)\\s*]")
        val match = regex.find(actionStr) ?: return null
        return Pair(match.groupValues[1].toInt(), match.groupValues[2].toInt())
    }

    private fun parseDuration(actionStr: String): Int {
        val regex = Regex("duration\\s*=\\s*[\"'](\\d+)")
        val match = regex.find(actionStr)
        return match?.groupValues?.get(1)?.toIntOrNull() ?: 3
    }

    private fun parseActionName(actionStr: String): String {
        val pattern = Pattern.compile("action\\s*=\\s*[\"'](.*?)[\"']", Pattern.CASE_INSENSITIVE)
        val matcher = pattern.matcher(actionStr)
        return if (matcher.find()) matcher.group(1) else "Unknown"
    }

    private fun extractFinishMessage(actionStr: String): String {
        val regex = Regex("message\\s*=\\s*[\"']([^\"']*)[\"']")
        val match = regex.find(actionStr)
        return match?.groupValues?.get(1) ?: "完成"
    }

    private fun extractMessage(actionStr: String): String {
        val regex = Regex("message\\s*=\\s*[\"']([^\"']*)[\"']")
        val match = regex.find(actionStr)
        return match?.groupValues?.get(1) ?: ""
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 85, outputStream)
        return Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
    }
}
