package com.autoglm.autoagent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.autoglm.autoagent.data.ApiConfig
import com.autoglm.autoagent.data.ApiProvider
import com.autoglm.autoagent.data.SettingsRepository
import com.autoglm.autoagent.ui.theme.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PermissionStatus(
    val accessibilityGranted: Boolean = false,
    val shellServiceActive: Boolean = false,
    val overlayGranted: Boolean = false,
    val queryPackagesGranted: Boolean = false
) {
    // 只要有无障碍或Shell服务其中之一，就视为拥有操作权限
    val canControlDevice: Boolean get() = accessibilityGranted || shellServiceActive
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
    private val shellConnector: com.autoglm.autoagent.shell.ShellServiceConnector
) : ViewModel() {
    val config = repository.config.stateIn(viewModelScope, SharingStarted.Lazily, null)
    
    val agentMode = repository.agentMode.stateIn(viewModelScope, SharingStarted.Lazily, com.autoglm.autoagent.agent.AgentMode.TURBO)
    
    private val _permissionStatus = MutableStateFlow(PermissionStatus())
    val permissionStatus = _permissionStatus.asStateFlow()
    
    fun saveAgentMode(mode: com.autoglm.autoagent.agent.AgentMode) {
        repository.saveAgentMode(mode)
    }

    fun saveConfig(provider: ApiProvider, baseUrl: String, apiKey: String, model: String) {
        viewModelScope.launch {
            repository.saveConfig(provider, baseUrl, apiKey, model)
        }
    }
    
    fun checkPermissions(context: Context) {
        viewModelScope.launch {
            // 检查 Shell 服务状态（异步）
            val isShellActive = try {
                shellConnector.connect()
            } catch (e: Exception) {
                false
            }
            
            val status = PermissionStatus(
                accessibilityGranted = isAccessibilityServiceEnabled(context),
                shellServiceActive = isShellActive,
                overlayGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    Settings.canDrawOverlays(context)
                } else true,
                queryPackagesGranted = canQueryPackages(context)
            )
            _permissionStatus.value = status
        }
    }
    
    private fun canQueryPackages(context: Context): Boolean {
        return try {
            val pm = context.packageManager
            val packages = pm.getInstalledPackages(0)
            // 正常的安卓手机至少有20+系统应用
            // 如果少于20个，说明权限被拒
            packages.size >= 20
        } catch (e: Exception) {
            false
        }
    }
    
    private fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val service = "${context.packageName}/com.autoglm.autoagent.service.AutoAgentService"
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""
        return enabledServices.contains(service)
    }
    
    fun requestPermissions(context: Context) {
        val status = _permissionStatus.value
        
        when {
            // 如果没有控制权限（既没开无障碍也没开Shell），才跳转无障碍设置
            !status.canControlDevice -> {
                // 跳转到无障碍设置
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
            !status.overlayGranted -> {
                // 跳转到悬浮窗设置
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}")
                    )
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                }
            }
            !status.queryPackagesGranted -> {
                // 跳转到应用详情页，方便用户找到“权限管理”并开启“获取应用列表”
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                val uri = Uri.fromParts("package", context.packageName, null)
                intent.data = uri
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
            else -> {
                // 所有权限已授予
                checkPermissions(context)
            }
        }
    }
    
    fun loadProviderConfig(provider: ApiProvider): ApiConfig {
        return repository.loadProviderConfig(provider)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit
) {
    val config by viewModel.config.collectAsState()
    val permissionStatus by viewModel.permissionStatus.collectAsState()
    val context = LocalContext.current
    
    
    // 监听生命周期，每次返回设置页都重新检查权限
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.checkPermissions(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    
    
    if (config == null) return

    var selectedProvider by remember { mutableStateOf(config!!.provider) }
    var baseUrl by remember { mutableStateOf(config!!.baseUrl) }
    var apiKey by remember { mutableStateOf(config!!.apiKey) }
    var model by remember { mutableStateOf(config!!.model) }
    var showApiKey by remember { mutableStateOf(false) }

    // Pre-fill defaults when switching
    LaunchedEffect(selectedProvider) {
        val providerConfig = viewModel.loadProviderConfig(selectedProvider)
        baseUrl = providerConfig.baseUrl
        apiKey = providerConfig.apiKey
        model = providerConfig.model
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(DarkBackground, DarkBackgroundSecondary)
                )
            )
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // 顶部导航栏
            TopAppBar(
                title = { 
                    Text(
                        "设置",
                        style = MaterialTheme.typography.titleLarge
                    ) 
                },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text(
                            text = "←",
                            fontSize = 24.sp,
                            color = PrimaryBlue
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = DarkBackground.copy(alpha = 0.8f),
                    titleContentColor = TextPrimary
                )
            )

            // 滚动内容
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                // 权限检查卡片
                SettingsCard(
                    title = "🛡️ 权限状态",
                    content = {
                        PermissionItem("无障碍服务", permissionStatus.accessibilityGranted)
                        Text(
                            text = "• 用途: 支持 AI 自动点击、滑动、输入等所有操作",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                            modifier = Modifier.padding(start = 8.dp, bottom = 8.dp)
                        )
                        
                        // 显示 Shell 服务状态（如果有）
                        if (permissionStatus.shellServiceActive) {
                            PermissionItem("Shell 服务 (高级模式)", true)
                            Text(
                                text = "• 状态: 已激活 ✅",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary,
                                modifier = Modifier.padding(start = 8.dp, bottom = 8.dp)
                            )
                        }
                        
                        PermissionItem("悬浮窗权限", permissionStatus.overlayGranted)
                        Text(
                            text = "• 用途: 显示悬浮控制按钮，方便随时停止任务",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                            modifier = Modifier.padding(start = 8.dp, bottom = 8.dp)
                        )
                        
                        PermissionItem("获取应用列表", permissionStatus.queryPackagesGranted)
                        Text(
                            text = "• 用途: 让 AI 能启动其他应用(如\"打开拼多多\")\n• 若显示未授予，请点击下方按钮进入应用详情页手动开启“获取应用列表”权限",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                            modifier = Modifier.padding(start = 8.dp, bottom = 8.dp)
                        )
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        Button(
                            onClick = { viewModel.requestPermissions(context) },
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = PrimaryBlue
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("检查并请求权限", color = TextPrimary)
                        }
                    }
                )
                
                // 模式切换卡片
                val currentMode by viewModel.agentMode.collectAsState()
                SettingsCard(
                    title = "🧠 执行模式",
                    content = {
                        Text(
                            "选择 AI 执行模式",
                            style = MaterialTheme.typography.labelMedium,
                            color = TextSecondary,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            ProviderChip(
                                text = "⚡ 极速模式",
                                selected = currentMode == com.autoglm.autoagent.agent.AgentMode.TURBO,
                                onClick = { viewModel.saveAgentMode(com.autoglm.autoagent.agent.AgentMode.TURBO) },
                                modifier = Modifier.weight(1f)
                            )
                            ProviderChip(
                                text = "🧠 思考模式",
                                selected = currentMode == com.autoglm.autoagent.agent.AgentMode.DEEP,
                                onClick = { viewModel.saveAgentMode(com.autoglm.autoagent.agent.AgentMode.DEEP) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        Text(
                            text = if (currentMode == com.autoglm.autoagent.agent.AgentMode.TURBO)
                                "• 极速模式: 单模型快速执行，适合简单任务"
                            else
                                "• 思考模式: 双模型协作，大模型规划+小模型执行\n• 适合复杂任务，需要配置 ZHIPU 和 EDGE 两个 API",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                            modifier = Modifier.padding(start = 4.dp)
                        )
                    }
                )

                // API配置卡片
                SettingsCard(
                    title = "🌐 API配置",
                    content = {
                        Text(
                            "选择API提供商",
                            style = MaterialTheme.typography.labelMedium,
                            color = TextSecondary,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            ProviderChip(
                                text = "自定义",
                                selected = selectedProvider == ApiProvider.EDGE,
                                onClick = { selectedProvider = ApiProvider.EDGE },
                                modifier = Modifier.weight(1f)
                            )
                            ProviderChip(
                                text = "智谱AI",
                                selected = selectedProvider == ApiProvider.ZHIPU,
                                onClick = { selectedProvider = ApiProvider.ZHIPU },
                                modifier = Modifier.weight(1f)
                            )
                        }

                        Spacer(modifier = Modifier.height(24.dp))

                        DarkTextField(
                            value = baseUrl,
                            onValueChange = { baseUrl = it },
                            label = "Base URL",
                            placeholder = "https://open.bigmodel.cn/api/paas/v4/"
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        DarkTextField(
                            value = apiKey,
                            onValueChange = { apiKey = it },
                            label = "API Key",
                            placeholder = "输入你的API密钥",
                            visualTransformation = if (showApiKey) 
                                VisualTransformation.None 
                            else 
                                PasswordVisualTransformation(),
                            trailingIcon = {
                                TextButton(onClick = { showApiKey = !showApiKey }) {
                                    Text(
                                        if (showApiKey) "隐藏" else "显示",
                                        color = PrimaryBlue,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        DarkTextField(
                            value = model,
                            onValueChange = { model = it },
                            label = "模型名称",
                            placeholder = "glm-4-flash"
                        )
                    }
                )

                // 关于卡片
                SettingsCard(
                    title = "ℹ️ 关于",
                    content = {
                        var clickCount by remember { mutableStateOf(0) }
                        var lastClickTime by remember { mutableStateOf(0L) }
                        
                        // 版本号行（可点击）
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp)
                                .clickable {
                                    val currentTime = System.currentTimeMillis()
                                    // 如果距离上次点击超过2秒，重置计数
                                    if (currentTime - lastClickTime > 2000) {
                                        clickCount = 1
                                    } else {
                                        clickCount++
                                    }
                                    lastClickTime = currentTime
                                    
                                    // 连续点击7次触发
                                    if (clickCount >= 7) {
                                        clickCount = 0
                                        // TODO: 跳转到日志界面（下一步实现）
                                        android.widget.Toast
                                            .makeText(context, "日志已开启", android.widget.Toast.LENGTH_SHORT)
                                            .show()
                                    }
                                },
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "版本",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary
                            )
                            Text(
                                text = "1.0.0",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextPrimary
                            )
                        }
                        InfoRow("项目", "AutoDroid")
                        InfoRow("开源", "GitHub")
                    }
                )

                // 底部保存按钮
                Button(
                    onClick = {
                        viewModel.saveConfig(selectedProvider, baseUrl, apiKey, model)
                        onBack()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PrimaryBlue
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(
                        "保存设置",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

@Composable
fun SettingsCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    com.autoglm.autoagent.ui.components.GlassCard(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(24.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            content()
        }
    }
}

@Composable
fun PermissionItem(name: String, granted: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.bodyMedium,
            color = TextPrimary
        )
        Text(
            text = if (granted) "✅" else "❌",
            fontSize = 18.sp
        )
    }
}

@Composable
fun ProviderChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(48.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) PrimaryBlue else Color.Transparent
        ),
        border = if(selected) null else androidx.compose.foundation.BorderStroke(1.dp, GlassLight),
        shape = RoundedCornerShape(12.dp)
    ) {
        Text(
            text = text,
            color = TextPrimary,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
fun DarkTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String = "",
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingIcon: @Composable (() -> Unit)? = null
) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = TextSecondary,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        TextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth().border(1.dp, GlassLight.copy(alpha=0.2f), RoundedCornerShape(12.dp)),
            placeholder = { Text(placeholder, color = TextHint, style = MaterialTheme.typography.bodyMedium) },
            visualTransformation = visualTransformation,
            trailingIcon = trailingIcon,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary,
                cursorColor = PrimaryBlue,
                focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent
            ),
            shape = RoundedCornerShape(12.dp),
            textStyle = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = TextPrimary
        )
    }
}
