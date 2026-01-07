package com.autoglm.autoagent.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.autoglm.autoagent.data.*
import com.autoglm.autoagent.data.api.ChatMessage
import com.autoglm.autoagent.ui.components.AnimatedGlowingCircle
import com.autoglm.autoagent.ui.theme.*
import com.autoglm.autoagent.utils.KeepAliveUtils
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.ui.platform.LocalContext
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
// ... (imports)
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.ui.graphics.asImageBitmap
import com.autoglm.autoagent.data.api.ContentPart

data class LogEntry(
    val timestamp: Long,
    val type: LogType,
    val content: String,
    val imageBase64: String? = null // Added for screenshot support
)

enum class LogType {
    USER_COMMAND, AI_ACTION
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val agentRepository: AgentRepository,
    private val dualModelAgent: com.autoglm.autoagent.agent.DualModelAgent,
    @ApplicationContext private val context: Context
) : ViewModel() {
    
    init {
        // Preload voice model on ViewModel creation
        agentRepository.preloadVoiceModel()
    }
    
    // 监听AgentRepository的消息
    val messages = agentRepository.chatMessages
    val agentState = agentRepository.agentState
    
    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    // 从 AgentRepository.agentState 派生状态文本
    val agentStatus: StateFlow<String> = agentRepository.agentState
        .map { state ->
            when (state) {
                is AgentState.Idle -> "等待指令..."
                is AgentState.Planning -> "正在规划..."
                is AgentState.Running -> "正在执行..."
                is AgentState.Paused -> "暂停等待中..."
                is AgentState.Listening -> "正在聆听..."
                is AgentState.Error -> "出现错误: ${state.msg}"
            }
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, "等待指令...")
    
    private val _isLogExpanded = MutableStateFlow(false)
    val isLogExpanded = _isLogExpanded.asStateFlow()

    // 从 AgentRepository.chatMessages 派生日志列表
    val logEntries: StateFlow<List<LogEntry>> = agentRepository.chatMessages
        .map { messages ->
            messages.filter { it.role == "user" || it.role == "assistant" || it.role == "system" }
                .mapNotNull { msg ->
                    var contentText = ""
                    var imageBase64: String? = null

                    when (val c = msg.content) {
                        is String -> contentText = c
                        is List<*> -> {
                            // Handle Multi-modal content
                            val parts = c as? List<*>
                            parts?.forEach { part ->
                                if (part is ContentPart) {
                                    if (part.type == "text") {
                                        contentText += (part.text ?: "") + "\n"
                                    } else if (part.type == "image_url") {
                                        // "data:image/png;base64,..."
                                        val url = part.image_url?.url ?: ""
                                        if (url.startsWith("data:image")) {
                                            imageBase64 = url.substringAfter("base64,")
                                        }
                                    }
                                }
                            }
                            if (contentText.isBlank() && imageBase64 != null) {
                                contentText = "[截图已捕获]"
                            }
                        }
                        else -> contentText = c.toString()
                    }
                    
                    val type = when (msg.role) {
                        "user" -> LogType.USER_COMMAND
                        "assistant", "system" -> LogType.AI_ACTION
                        else -> null
                    }
                    
                    if (type != null) {
                        LogEntry(
                            timestamp = System.currentTimeMillis(),
                            type = type,
                            content = contentText.removePrefix("Action: ").trim(),
                            imageBase64 = imageBase64
                        )
                    } else null
                }
                .takeLast(50)
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    
    // 最后一条用户指令
    val lastUserCommand: StateFlow<String> = logEntries.map { logs ->
        logs.lastOrNull { it.type == LogType.USER_COMMAND }?.content 
            ?: "等待输入指令..."
    }.stateIn(viewModelScope, SharingStarted.Lazily, "等待输入指令...")

    private val _showTextInput = MutableStateFlow(false)
    val showTextInput = _showTextInput.asStateFlow()

    fun toggleTextInput() {
        _showTextInput.value = !_showTextInput.value
    }
    
    fun toggleLogPanel() {
        _isLogExpanded.value = !_isLogExpanded.value
    }
    
    fun sendMessage(text: String) {
        // 发送指令前检测权限：无障碍服务 或 Shell 服务任一开启即可
        // [Fix] 直接调用 agentRepository 中的 shizukuManager 实例获取状态
        val isShizukuActive = agentRepository.getActivationStatus() == com.autoglm.autoagent.shizuku.ActivationStatus.ACTIVATED
        
        if (!isAccessibilityServiceEnabled() && !isShizukuActive) {
            viewModelScope.launch {
                agentRepository.setError("请先开启无障碍服务或激活 Shell 高级模式")
                kotlinx.coroutines.delay(3000)
                agentRepository.resetToIdle()
            }
            return
        }
        
        viewModelScope.launch {
            _isLoading.value = true
            try {
                agentRepository.executeTask(text)
            } catch (ignored: Exception) {
            } finally {
                _isLoading.value = false
            }
        }
    }
    
    private fun isAccessibilityServiceEnabled(): Boolean {
        val service = "${context.packageName}/com.autoglm.autoagent.service.AutoAgentService"
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""
        return enabledServices.contains(service)
    }

    fun startVoiceRecording() {
        agentRepository.setListening(true)
    }

    fun stopExecution() {
        if (agentState.value is AgentState.Listening) {
             agentRepository.cancelListening()
        }
        agentRepository.stopAgent()
        dualModelAgent.stop()
        _isLoading.value = false
    }
    
    // 规划确认相关
    val pendingPlan = dualModelAgent.pendingPlan
    val planCountdown = dualModelAgent.planCountdown
    
    fun confirmPlan() {
        dualModelAgent.confirmPlan()
    }
    
    fun cancelPlan() {
        dualModelAgent.cancelPlan()
    }
    
    // ASK_USER 相关
    val pendingQuestion = dualModelAgent.pendingQuestion
    
    fun answerQuestion(answer: String) {
        dualModelAgent.answerQuestion(answer)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalAnimationApi::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onOpenSettings: () -> Unit,
    onOpenAdvancedMode: () -> Unit = {}  // New parameter
) {
    val agentStatus by viewModel.agentStatus.collectAsState()
    val lastUserCommand by viewModel.lastUserCommand.collectAsState()
    val logEntries by viewModel.logEntries.collectAsState()
    val isLogExpanded by viewModel.isLogExpanded.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val showTextInput by viewModel.showTextInput.collectAsState()
    val agentState by viewModel.agentState.collectAsState()
    val isRecording = agentState is AgentState.Listening
    
    // 规划确认状态
    val pendingPlan by viewModel.pendingPlan.collectAsState()
    val planCountdown by viewModel.planCountdown.collectAsState()
    
    // ASK_USER 状态
    val pendingQuestion by viewModel.pendingQuestion.collectAsState()

    // Root Container with Particle Background
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(DarkBackground, DarkBackgroundSecondary)
                )
            )
    ) {
        // 1. Ambient Motion Layer
        com.autoglm.autoagent.ui.components.ParticleBackground(
            modifier = Modifier.fillMaxSize().alpha(0.6f)
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding(), // Respect bottom nav bar
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 2. Minimalist Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "智灵助手", // Brand Name
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        ),
                        color = TextPrimary
                    )
                    Text(
                        text = "Hands-free AI Agent, built on Open-AutoGLM",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                }
                
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Advanced Mode Icon
                    IconButton(
                        onClick = onOpenAdvancedMode,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(PrimaryPurple.copy(alpha=0.15f))
                            .size(40.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Rocket,
                            contentDescription = "高级模式",
                            tint = PrimaryPurple,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    
                    // Settings Icon (Glass)
                    IconButton(
                        onClick = onOpenSettings,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(GlassLight.copy(alpha=0.1f))
                            .size(40.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Setting",
                            tint = TextSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // 3. THE CORE: Living Orb
            // No more "Power Button". This IS the agent.
            var isOrbPressed by remember { mutableStateOf(false) }
            
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(300.dp)
                    .semantics(mergeDescendants = true) {
                        role = Role.Button
                        contentDescription = if (agentState is AgentState.Running || agentState is AgentState.Listening) {
                            "停止执行"
                        } else {
                            "开始语音识别"
                        }
                        onClick {
                            if (agentState is AgentState.Running || agentState is AgentState.Listening || agentState is AgentState.Planning) {
                                viewModel.stopExecution()
                            } else {
                                viewModel.startVoiceRecording()
                            }
                            true
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                isOrbPressed = true
                                tryAwaitRelease()
                                isOrbPressed = false
                            },
                            onTap = {
                                if (agentState is AgentState.Running || agentState is AgentState.Listening || agentState is AgentState.Planning) {
                                    viewModel.stopExecution()
                                } else {
                                    viewModel.startVoiceRecording()
                                }
                            }
                        )
                    }
            ) {
                com.autoglm.autoagent.ui.components.LivingOrb(
                    modifier = Modifier.fillMaxSize(),
                    isActive = isLoading || agentState !is AgentState.Idle,
                    isListening = isRecording,
                    isPressed = isOrbPressed
                )
            }
            
            Spacer(modifier = Modifier.height(32.dp))

            // 4. Dynamic Status Text
            AnimatedContent(targetState = agentStatus, label = "status") { status ->
            // 4. Status Text with Animated Ellipsis
            Box(
                 modifier = Modifier
                     .padding(top = 24.dp, start = 24.dp, end = 24.dp)
                     .heightIn(min = 40.dp, max = 120.dp),
                 contentAlignment = Alignment.Center
            ) {
                 val baseText = if (status.endsWith("...")) status.dropLast(3) else status
                 val shouldAnimate = status.endsWith("...")
                 
                 if (shouldAnimate) {
                     val infiniteTransition = rememberInfiniteTransition(label = "dots")
                     val dotCount by infiniteTransition.animateValue(
                         initialValue = 0,
                         targetValue = 4, // 0, 1, 2, 3
                         typeConverter = Int.VectorConverter,
                         animationSpec = infiniteRepeatable(
                             animation = tween(1500, easing = LinearEasing),
                             repeatMode = RepeatMode.Restart
                         ),
                         label = "dot_count"
                     )
                     
                     Text(
                        text = "$baseText${".".repeat(dotCount)}",
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontWeight = FontWeight.Light
                        ),
                        color = TextPrimary,
                        textAlign = TextAlign.Center
                    )
                 } else {
                     Text(
                        text = status,
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontWeight = FontWeight.Light
                        ),
                        color = TextPrimary,
                        textAlign = TextAlign.Center
                    )
                 }
            }
            }
            
            Text(
                text = if (isRecording) "点击取消" else "点击圆球开始对话",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary.copy(alpha=0.6f),
                modifier = Modifier.padding(top = 0.dp)
            )

            Spacer(modifier = Modifier.weight(1f))

            // 5. Floating Action Bar (Bottom)
            // Replaces the heavy "Card" layout with a sleek floating row
            Row(
                modifier = Modifier
                    .padding(bottom = 32.dp, start = 24.dp, end = 24.dp)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Log Toggle (Subtle Frosted Glass)
                com.autoglm.autoagent.ui.components.GlassCard(
                    modifier = Modifier
                        .weight(1f)
                        .height(60.dp) // Standard sleek height
                        .clickable { viewModel.toggleLogPanel() },
                    shape = RoundedCornerShape(30.dp),
                    backgroundColor = Color.White.copy(alpha = 0.05f), // Very subtle frost
                    borderColor = Color.White.copy(alpha = 0.15f)
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.List, 
                            contentDescription = null, 
                            tint = PrimaryCyan.copy(alpha = 0.8f), 
                            modifier = Modifier.size(20.dp) // Standard Size
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "日志", 
                            color = TextPrimary.copy(alpha = 0.9f), 
                            fontSize = 14.sp, // Standard Size
                            fontWeight = FontWeight.Normal
                        )
                    }
                }
                
                Spacer(modifier = Modifier.width(16.dp))
                
                // Keyboard Toggle (Subtle Frosted Glass)
                com.autoglm.autoagent.ui.components.GlassCard(
                    modifier = Modifier
                        .weight(1f)
                        .height(60.dp) // Standard sleek height
                        .clickable { viewModel.toggleTextInput() },
                    shape = RoundedCornerShape(30.dp),
                    backgroundColor = Color.White.copy(alpha = 0.05f), // Very subtle frost
                    borderColor = Color.White.copy(alpha = 0.15f)
                ) {
                     Row(
                        modifier = Modifier.fillMaxSize(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Edit, 
                            contentDescription = null, 
                            tint = PrimaryPurple.copy(alpha = 0.8f), 
                            modifier = Modifier.size(20.dp) // Standard Size
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "输入", 
                            color = TextPrimary.copy(alpha = 0.9f), 
                            fontSize = 14.sp, // Standard Size
                            fontWeight = FontWeight.Normal
                        )
                    }
                }
            }
        }
        
        // Overlays (Log & Input) - Kept same logic, just ensure they sit on top
        if (isLogExpanded) {
             LogSheet(logs = logEntries, onDismiss = { viewModel.toggleLogPanel() })
        }

        if (showTextInput) {
             TextInputSheet(
                 onSend = { 
                     viewModel.sendMessage(it)
                     viewModel.toggleTextInput() 
                 }, 
                 onDismiss = { viewModel.toggleTextInput() }
            )
        }
        
        // 规划确认弹窗
        pendingPlan?.let { plan ->
            PlanConfirmationSheet(
                plan = plan,
                countdown = planCountdown,
                onConfirm = { viewModel.confirmPlan() },
                onCancel = { viewModel.cancelPlan() }
            )
        }
        
        // ASK_USER 弹窗
        pendingQuestion?.let { question ->
            AskUserDialog(
                question = question,
                onAnswer = { viewModel.answerQuestion(it) },
                onDismiss = { viewModel.answerQuestion("") }
            )
        }
    }
}
// Dependent sub-components (LogSheet, TextInputSheet, LogItem) remain unchanged...
// Deleted PowerButton and redundant VoiceControlBar


@Composable
fun LogSheet(logs: List<LogEntry>, onDismiss: () -> Unit) {
    // 展开状态：false=半屏(350dp), true=全屏
    var isExpanded by remember { mutableStateOf(false) }
    
    // 使用屏幕高度计算
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val screenHeight = configuration.screenHeightDp.dp
    
    // 高度动画 - 平滑过渡
    val sheetHeight by animateDpAsState(
        targetValue = if (isExpanded) screenHeight else 350.dp,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 400f),
        label = "sheet_height"
    )
    
    // 背景遮罩动画
    val backdropAlpha by animateFloatAsState(
        targetValue = if (isExpanded) 0.85f else 0.6f,
        animationSpec = tween(200),
        label = "backdrop"
    )
    
    // 追踪手势累积量（用于判断方向）
    var accumulatedDrag by remember { mutableStateOf(0f) }
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = backdropAlpha))
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null
            ) { 
                if (!isExpanded) onDismiss() 
            },
        contentAlignment = Alignment.BottomCenter
    ) {
        com.autoglm.autoagent.ui.components.GlassCard(
            modifier = Modifier
                .fillMaxWidth()
                .height(sheetHeight)
                .clickable(enabled = false) {}, // 阻止点击穿透
            shape = if (isExpanded) 
                RoundedCornerShape(0.dp) 
            else 
                RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            backgroundColor = DarkSurface
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // ===== 可拖动的手柄区域 =====
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .pointerInput(isExpanded) {
                            detectVerticalDragGestures(
                                onDragStart = {
                                    accumulatedDrag = 0f
                                },
                                onDragEnd = {
                                    // 根据累积拖动量决定行为
                                    val threshold = 80f
                                    when {
                                        accumulatedDrag > threshold -> {
                                            // 下拉：如果全屏则收起，否则关闭
                                            if (isExpanded) {
                                                isExpanded = false
                                            } else {
                                                onDismiss()
                                            }
                                        }
                                        accumulatedDrag < -threshold -> {
                                            // 上拉：展开全屏
                                            isExpanded = true
                                        }
                                    }
                                    accumulatedDrag = 0f
                                },
                                onVerticalDrag = { _, dragAmount ->
                                    accumulatedDrag += dragAmount
                                }
                            )
                        }
                        .padding(horizontal = 24.dp, vertical = 12.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // 拖动手柄
                        Box(
                            modifier = Modifier
                                .width(48.dp)
                                .height(5.dp)
                                .clip(RoundedCornerShape(2.5.dp))
                                .background(TextSecondary.copy(alpha = 0.5f))
                        )
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        // 标题行
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "执行日志",
                                style = MaterialTheme.typography.titleLarge,
                                color = TextPrimary,
                                fontWeight = FontWeight.SemiBold
                            )
                            
                            // 提示文字
                            Text(
                                if (isExpanded) "↓ 下拉收起" else "↑ 上拉展开",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary.copy(alpha = 0.5f)
                            )
                        }
                    }
                }
                
                // 分隔线
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(Color.White.copy(alpha = 0.08f))
                )
                
                // ===== 日志内容列表 =====
                // 计算步骤编号
                val logsWithSteps = remember(logs) {
                    var counter = 0
                    logs.map { log ->
                        val step = if (log.type == LogType.AI_ACTION) ++counter else 0
                        log to step
                    }
                }
                
                if (logs.isEmpty()) {
                    // 空状态
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "暂无日志",
                            color = TextSecondary.copy(alpha = 0.5f),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                } else {
                    androidx.compose.foundation.lazy.LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            top = 12.dp,
                            bottom = 24.dp
                        )
                    ) {
                        items(logsWithSteps.size) { index ->
                            val (log, step) = logsWithSteps[index]
                            LogItemCard(log, stepNumber = step)
                        }
                    }
                }
            }
        }
    }
}


@Composable
fun TextInputSheet(onSend: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
     Box(
        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha=0.7f)).clickable { onDismiss() },
        contentAlignment = Alignment.BottomCenter,
    ) {
        com.autoglm.autoagent.ui.components.GlassCard(
            modifier = Modifier.fillMaxWidth().padding(16.dp).imePadding(), // Handle keyboard
            backgroundColor = DarkSurface
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                 TextField(
                     value = text, 
                     onValueChange = { text = it }, 
                     modifier = Modifier.weight(1f),
                     colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedTextColor = TextPrimary,
                         unfocusedTextColor = TextPrimary,
                     ),
                     placeholder = { Text("输入指令...", color = TextSecondary) }
                 )
                 IconButton(onClick = { if (text.isNotBlank()) onSend(text) }) {
                     Icon(Icons.Default.Send, null, tint = PrimaryBlue)
                 }
            }
        }
    }
}

/**
 * ASK_USER 弹窗 - 显示 AI 问题并接收用户回复
 */
@Composable
fun AskUserDialog(
    question: String,
    onAnswer: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf("") }
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.85f))
            .clickable(enabled = false) {},
        contentAlignment = Alignment.Center
    ) {
        com.autoglm.autoagent.ui.components.GlassCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
                .imePadding(),
            backgroundColor = DarkSurface
        ) {
            Column(
                modifier = Modifier.padding(20.dp)
            ) {
                // 标题
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.QuestionAnswer,
                        contentDescription = null,
                        tint = PrimaryBlue,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "需要您的确认",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // AI 问题
                Text(
                    text = question,
                    style = MaterialTheme.typography.bodyLarge,
                    color = TextSecondary,
                    lineHeight = 24.sp
                )
                
                Spacer(modifier = Modifier.height(20.dp))
                
                // 输入框
                TextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.White.copy(alpha = 0.1f),
                        unfocusedContainerColor = Color.White.copy(alpha = 0.05f),
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    ),
                    placeholder = { Text("输入您的回复...", color = TextSecondary) },
                    shape = RoundedCornerShape(12.dp)
                )
                
                Spacer(modifier = Modifier.height(20.dp))
                
                // 按钮行
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = TextSecondary
                        )
                    ) {
                        Text("取消")
                    }
                    
                    Button(
                        onClick = { if (text.isNotBlank()) onAnswer(text) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PrimaryBlue
                        ),
                        enabled = text.isNotBlank()
                    ) {
                        Text("发送", color = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
fun LogItem(log: LogEntry) {
    Row(verticalAlignment = Alignment.Top) {
        Text(if (log.type == LogType.USER_COMMAND) "👤" else "🤖", modifier = Modifier.padding(end=12.dp))
        Column {
             Text(
                text = log.content,
                color = if (log.type == LogType.USER_COMMAND) PrimaryBlueLight else TextSecondary,
                style = MaterialTheme.typography.bodyMedium
             )
        }
    }
}

/** 卡片化的日志项，带步骤编号 */
/** 卡片化的日志项，带步骤编号 */
@Composable
fun LogItemCard(log: LogEntry, stepNumber: Int) {
    val isUser = log.type == LogType.USER_COMMAND
    
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        com.autoglm.autoagent.ui.components.GlassCard(
            modifier = Modifier.widthIn(max = 300.dp),
            shape = if (isUser) 
                RoundedCornerShape(16.dp, 2.dp, 16.dp, 16.dp) 
            else 
                RoundedCornerShape(2.dp, 16.dp, 16.dp, 16.dp),
            backgroundColor = if (isUser) 
                PrimaryBlue.copy(alpha = 0.2f) 
            else 
                Color.White.copy(alpha = 0.05f),
            borderColor = if (isUser)
                PrimaryBlue.copy(alpha = 0.4f)
            else
                Color.White.copy(alpha = 0.1f)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                // Header Row: Step Number (Only for AI)
                if (!isUser && stepNumber > 0) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // 步骤编号圆圈
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .clip(CircleShape)
                                .background(PrimaryCyan.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "$stepNumber",
                                style = MaterialTheme.typography.labelSmall,
                                color = PrimaryCyan,
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp
                            )
                        }
                        
                        Spacer(modifier = Modifier.width(8.dp))
                        
                        // AI Role Label
                        Text(
                            text = "AutoAgent",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary.copy(alpha = 0.7f),
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
                
                // Content Row (Image + Text)
                // 如果有图片，显示
                if (log.imageBase64 != null) {
                    val bitmap = remember(log.imageBase64) {
                        try {
                            val decodedString = Base64.decode(log.imageBase64, Base64.DEFAULT)
                            BitmapFactory.decodeByteArray(decodedString, 0, decodedString.size)?.asImageBitmap()
                        } catch (e: Exception) {
                            null
                        }
                    }
                    
                    if (bitmap != null) {
                        androidx.compose.foundation.Image(
                            bitmap = bitmap,
                            contentDescription = "Screenshot",
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 200.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.Black),
                            contentScale = androidx.compose.ui.layout.ContentScale.Fit
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
                
                // Text Content
                val displayContent = log.content.trim()
                if (displayContent.isNotBlank()) {
                    Text(
                        text = displayContent,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextPrimary.copy(alpha = 0.9f),
                        lineHeight = 20.sp
                    )
                }
            }
        }
    }
}

/**
 * 规划确认弹窗
 */
@Composable
fun PlanConfirmationSheet(
    plan: com.autoglm.autoagent.agent.TaskPlan,
    countdown: Int,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.7f)),
        contentAlignment = Alignment.Center
    ) {
        com.autoglm.autoagent.ui.components.GlassCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            shape = RoundedCornerShape(24.dp),
            backgroundColor = DarkSurface
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 标题
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Checklist,
                        contentDescription = null,
                        tint = PrimaryCyan,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "任务规划",
                        style = MaterialTheme.typography.headlineSmall,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // 步骤列表
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 200.dp)
                ) {
                    plan.steps.forEachIndexed { index, step ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            // 步骤编号
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(CircleShape)
                                    .background(PrimaryCyan.copy(alpha = 0.2f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "${index + 1}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = PrimaryCyan,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            
                            Spacer(modifier = Modifier.width(12.dp))
                            
                            Text(
                                text = step.description,
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary
                            )
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(24.dp))
                
                // 按钮行
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // 取消按钮
                    OutlinedButton(
                        onClick = onCancel,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = TextSecondary
                        )
                    ) {
                        Text("取消")
                    }
                    
                    // 确认按钮（带倒计时）
                    Button(
                        onClick = onConfirm,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PrimaryBlue
                        )
                    ) {
                        Text(
                            text = if (countdown > 0) "确认 (${countdown}s)" else "确认",
                            color = Color.White
                        )
                    }
                }
            }
        }
    }
}
