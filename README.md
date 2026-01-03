[English Version](README_EN.md)

# AutoDroid

<div align="center">

**Open-AutoGLM 的 Android 原生客户端**

让 AI 智能助理直接在手机上运行,无需电脑辅助

> 本项目为社区独立实现，非 Open-AutoGLM 官方客户端。

</div>

## 核心特性
1. **原生运行** - 无需电脑 (ADB),直接在手机上通过无障碍服务运行
2. **多模态感知** - 支持截图理解和语音输入 (Sherpa-ONNX 离线识别)
3. **APK 体积** - 包含离线语音模型,APK 约 100 MB
4. **隐私安全** - 所有数据处理(除大模型API外)均在本地完成

---

## 演示

> 🚧 **开发中**: 演示视频即将上传

---

## 技术细节

### 输入机制
- **Accessibility Service Native Injection**: 直接通过无障碍服务进行点击和手势操作。
- **Text Input**: 使用 `AgentInputMethodService` 进行原生文本注入，**无需** 切换虚拟键盘。

### 核心组件
- **AutoAgentService**: 主后台服务 (`app/src/main/java/com/autoglm/autoagent/service/AutoAgentService.kt`)
- **AgentInputMethodService**: 文本输入服务 (`app/src/main/java/com/autoglm/autoagent/service/AgentInputMethodService.kt`)
- **AIClient**: 大模型通信层 (`app/src/main/java/com/autoglm/autoagent/data/AIClient.kt`)
- **AgentRepository**: 核心业务逻辑 (`app/src/main/java/com/autoglm/autoagent/data/AgentRepository.kt`)

---

## 快速开始

### 1. 安装
下载并安装最新 Release 版本 (`.apk`)。

### 2. 权限授予
首次启动需授予以下权限:
- **无障碍服务**: 用于屏幕控制和截图
- **悬浮窗**: 用于显示状态栏
- **录音**: 用于语音指令

### 3. 配置 API
在设置页面配置您的 LLM API (如智谱 GLM-6V)。

---

## 第三方组件与参考项目

本项目使用了以下开源组件或参考了相关设计:

- **Open-AutoGLM** (Apache License 2.0) - 自动化 Agent 设计与协议参考
- **Sherpa-ONNX** (Apache 2.0) - 离线语音识别引擎
- **Paraformer 模型** (Apache 2.0) - 中文语音识别模型
- **AutoGLM-Phone-9B family** (See original model license) - Large language model used via API

详见 [第三方组件许可](./THIRD_PARTY_LICENSES.md)

⚠️ **免责声明**: 本项目仅供研究和学习使用,严禁用于任何非法用途。

---

**开发者**: Aell Xin  
**最后更新**: 2026-01-03
