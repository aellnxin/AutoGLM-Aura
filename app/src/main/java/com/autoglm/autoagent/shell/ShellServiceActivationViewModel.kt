package com.autoglm.autoagent.shell

import android.content.Context
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.autoglm.autoagent.shizuku.ActivationStatus
import com.autoglm.autoagent.shizuku.ShizukuManager
import javax.inject.Inject

/**
 * Shell Service 激活界面的状态数据
 */
data class ShellActivationUiState(
    val isServiceRunning: Boolean = false,
    val needsPairing: Boolean = false, // 回退版默认不显示配对卡片
    val activationCommand: String = "",
    val shizukuStatus: ActivationStatus = ActivationStatus.NOT_INSTALLED,
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

/**
 * Shell Service 激活界面的 ViewModel (回退版 - 移除 Kadb)
 */
@HiltViewModel
class ShellServiceActivationViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val deployer: ShellServiceDeployer,
    private val connector: ShellServiceConnector,
    private val adbLauncher: AdbServiceLauncher,
    private val shizukuManager: ShizukuManager,
    private val agentRepository: com.autoglm.autoagent.data.AgentRepository
) : ViewModel() {
    
    private val _state = MutableStateFlow(ShellActivationUiState())
    val state: StateFlow<ShellActivationUiState> = _state
    
    init {
        // 确保 DEX 已部署并生成命令
        viewModelScope.launch {
            if (!deployer.isDeployed()) {
                deployer.deployServerDex()
            }
            _state.value = _state.value.copy(
                activationCommand = deployer.getActivationCommand()
            )
        }
        
        checkServiceStatus()
        updateShizukuStatus()
    }
    
    fun updateShizukuStatus() {
        _state.value = _state.value.copy(
            shizukuStatus = shizukuManager.getActivationStatus()
        )
    }
    
    fun checkServiceStatus() {
        viewModelScope.launch {
            val connected = withContext(Dispatchers.IO) {
                connector.connect()
            }
            
            _state.value = _state.value.copy(
                isLoading = false,
                isServiceRunning = connected
            )
        }
    }
    
    fun launchService() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, errorMessage = null)
            
            val status = shizukuManager.getActivationStatus()
            if (status == ActivationStatus.ACTIVATED) {
                // 1. 先尝试静默保活注入 (不需要 UI 输出)
                agentRepository.ensureKeepAlive(silent = true)
                
                // 2. 启动服务逻辑
                val command = deployer.getActivationCommand()
                val success = withContext(Dispatchers.IO) {
                    shizukuManager.runCommand(command)
                }
                
                if (success) {
                    // 等待服务启动并尝试连接
                    repeat(5) {
                        delay(1000)
                        if (connector.connect()) {
                            _state.value = _state.value.copy(isServiceRunning = true, isLoading = false)
                            return@launch
                        }
                    }
                    _state.value = _state.value.copy(
                        isLoading = false,
                        errorMessage = "服务已启动但连接超时，请重试"
                    )
                } else {
                    _state.value = _state.value.copy(
                        isLoading = false,
                        errorMessage = "通过 Shizuku 启动失败，请检查 Shizuku 状态"
                    )
                }
            } else {
                _state.value = _state.value.copy(
                    isLoading = false,
                    errorMessage = "请先授权 Shizuku 或手动执行下方的 ADB 命令"
                )
            }
        }
    }
    
    fun testService() {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                connector.injectKey(4) // BACK key
            }
            android.util.Log.d("ShellService", "Test result: $result")
        }
    }
    
    fun stopService() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                connector.destroy()
            }
            checkServiceStatus()
        }
    }
}
