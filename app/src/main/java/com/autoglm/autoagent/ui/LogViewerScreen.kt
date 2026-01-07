package com.autoglm.autoagent.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.autoglm.autoagent.utils.FileLogger
import kotlinx.coroutines.delay
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogViewerScreen(
    fileLogger: FileLogger,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var logContent by remember { mutableStateOf("") }
    var autoRefresh by remember { mutableStateOf(true) }
    val listState = rememberLazyListState()
    
    // 日期格式
    val dateFormat = remember { SimpleDateFormat("yyyyMMdd", Locale.getDefault()) }
    val today = remember { dateFormat.format(Date()) }
    
    // 自动刷新逻辑
    LaunchedEffect(autoRefresh) {
        while (autoRefresh) {
            val logFile = fileLogger.getLogsByDate(today)
            logContent = logFile?.readText() ?: "暂无日志"
            delay(2000) // 每2秒刷新一次
        }
    }
    
    // 初始加载
    LaunchedEffect(Unit) {
        val logFile = fileLogger.getLogsByDate(today)
        logContent = logFile?.readText() ?: "暂无日志"
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("应用日志") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "返回")
                    }
                },
                actions = {
                    // 导出按钮
                    IconButton(onClick = {
                        val logFile = fileLogger.getLogsByDate(today)
                        if (logFile != null && logFile.exists()) {
                            val uri = FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.fileprovider",
                                logFile
                            )
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(shareIntent, "分享日志"))
                        }
                    }) {
                        Icon(Icons.Default.Share, "导出")
                    }
                    
                    // 清除按钮
                    IconButton(onClick = {
                        fileLogger.clearAllLogs()
                        logContent = "日志已清除"
                    }) {
                        Icon(Icons.Default.Delete, "清除")
                    }
                }
            )
        },
        bottomBar = {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                tonalElevation = 3.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("自动刷新")
                    Switch(
                        checked = autoRefresh,
                        onCheckedChange = { autoRefresh = it }
                    )
                }
            }
        }
    ) { padding ->
        if (logContent.isBlank() || logContent == "暂无日志") {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "暂无日志\n\n日志会在任务执行时自动记录",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                state = listState
            ) {
                val lines = logContent.split("\n").filter { it.isNotBlank() }
                items(lines) { line ->
                    LogLine(line)
                }
            }
            
            // 自动滚动到底部
            LaunchedEffect(logContent) {
                if (listState.layoutInfo.totalItemsCount > 0) {
                    listState.animateScrollToItem(listState.layoutInfo.totalItemsCount - 1)
                }
            }
        }
    }
}

@Composable
fun LogLine(line: String) {
    val backgroundColor = when {
        line.contains("[ERROR]") -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
        line.contains("[WARN]") -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f)
        line.contains("[INFO]") -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.1f)
        else -> MaterialTheme.colorScheme.surface
    }
    
    val textColor = when {
        line.contains("[ERROR]") -> MaterialTheme.colorScheme.error
        line.contains("[WARN]") -> MaterialTheme.colorScheme.tertiary
        line.contains("[INFO]") -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurface
    }
    
    Text(
        text = line,
        modifier = Modifier
            .fillMaxWidth()
            .background(backgroundColor)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        color = textColor,
        lineHeight = 14.sp
    )
}
