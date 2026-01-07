package com.autoglm.autoagent.shell

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Shell Service Deployer (Simplified for AIDL/Shizuku mode)
 */
@Singleton
class ShellServiceDeployer @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val tokenFile: File
        get() = File(context.filesDir, "server_token.txt")
    
    /**
     * Generate or retrieve security token
     */
    fun getOrGenerateToken(): String {
        if (tokenFile.exists()) {
            try {
                return tokenFile.readText().trim()
            } catch (e: Exception) { /* ignore */ }
        }
        val newToken = UUID.randomUUID().toString()
        try {
            tokenFile.writeText(newToken)
        } catch (e: Exception) { /* ignore */ }
        return newToken
    }
    
    /**
     * Generate activation logic message
     */
    fun getActivationCommand(): String {
        return "# 高级模式目前已完全重构为基于 Shizuku 的直接注入 (Binder 桥接)\n" +
               "# 这种模式比传统的 Shell/DEX 模式更稳定且响应更快。\n" +
               "# \n" +
               "# 如果您没有 Shizuku，建议先安装 Shizuku 并通过无线调试激活它。\n" +
               "# 目前已停止对手动 ADB 脚本模式的支持，以确保注入的安全性与性能。"
    }
    
    fun deployServerDex(): Boolean = true
    fun isDeployed(): Boolean = true
}
