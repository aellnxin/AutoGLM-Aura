package com.autoglm.autoagent.shell

import android.content.Context
import android.util.Log
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
    private val shizukuManager: ShizukuManager,
    private val agentRepository: com.autoglm.autoagent.data.AgentRepository
) : ViewModel() {
    
    private val _state = MutableStateFlow(ShellActivationUiState())
    val state: StateFlow<ShellActivationUiState> = _state
    
    init {
        // 观察服务连接状态
        viewModelScope.launch {
            shizukuManager.isServiceConnected.collect { connected ->
                _state.value = _state.value.copy(
                    isServiceRunning = connected, 
                    isLoading = false
                )
            }
        }
        
        // 引导命令仍保留 deployer 生成（用于展示）
        _state.value = _state.value.copy(
            activationCommand = deployer.getActivationCommand()
        )
        
        updateShizukuStatus()
    }
    
    fun updateShizukuStatus() {
        _state.value = _state.value.copy(
            shizukuStatus = shizukuManager.getActivationStatus()
        )
    }
    
    fun checkServiceStatus() {
        // 现在通过 init 中的 collect 自动更新
    }
    
    fun launchService() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, errorMessage = null)
            
            val status = shizukuManager.getActivationStatus()
            if (status == ActivationStatus.ACTIVATED) {
                val success = shizukuManager.bindService()
                if (!success) {
                    _state.value = _state.value.copy(
                        isLoading = false,
                        errorMessage = "绑定 Shizuku 用户服务失败，请检查 Shizuku 是否正常"
                    )
                }
                // 连接成功后的状态由 collect 处理
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
            Log.d("ShellService", "Test result: $result")
        }
    }
    
    fun stopService() {
        viewModelScope.launch {
            shizukuManager.unbindService()
        }
    }
}
