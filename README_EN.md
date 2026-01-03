# AutoDroid

<div align="center">

**Open-AutoGLM's Native Android Client**

Run AI Intelligent Agents directly on your phone, without a PC.

> This is an independent Android implementation and is not an official Open-AutoGLM client.

</div>

## Core Features
1. **Native Execution** - No PC (ADB) required, runs directly on the phone via Accessibility Services.
2. **Multimodal Perception** - Supports screenshot understanding and voice input (Sherpa-ONNX offline recognition).
3. **APK Size** - Includes offline voice models, APK size is approx. **100 MB**.
4. **Privacy & Security** - All data processing (except LLM API calls) is done locally.

---

## Demo

> 🚧 **Under Development**: Demo video coming soon.

---

## Technical Details

### Input Mechanism
- **Accessibility Service Native Injection**: Performs clicks and gestures directly via Accessibility Service.
- **Text Input**: Uses `AgentInputMethodService` for native text injection, **NO** need to switch virtual keyboards.

### Core Components
- **AutoAgentService**: Main background service (`app/src/main/java/com/autoglm/autoagent/service/AutoAgentService.kt`)
- **AgentInputMethodService**: Text input service (`app/src/main/java/com/autoglm/autoagent/service/AgentInputMethodService.kt`)
- **AIClient**: LLM communication layer (`app/src/main/java/com/autoglm/autoagent/data/AIClient.kt`)
- **AgentRepository**: Core business logic (`app/src/main/java/com/autoglm/autoagent/data/AgentRepository.kt`)

---

## Quick Start

### 1. Installation
Download and install the latest Release version (`.apk`).

### 2. Grant Permissions
Upon first launch, grant the following permissions:
- **Accessibility Service**: For screen control and screen capture.
- **Floating Window**: For displaying the status bar.
- **Audio Recording**: For voice commands.

### 3. Configure API
Configure your LLM API (e.g., Zhipu GLM-6V) in the settings page.

---

## Third-party Components and Referenced Projects

This project uses the following open-source components or references:

- **Open-AutoGLM** (Apache License 2.0) - Automation agent design and protocol reference
- **Sherpa-ONNX** (Apache 2.0) - Offline Speech Recognition Engine
- **Paraformer Model** (Apache 2.0) - Chinese Speech Recognition Model
- **AutoGLM-Phone-9B family** (See original model license) - Large language model used via API

See [Third Party Licenses](./THIRD_PARTY_LICENSES.md) for details.

⚠️ **Disclaimer**: This project is for research and educational purposes only. Any illegal use is strictly prohibited.

---

**Developer**: Aell Xin  
**Last Updated**: 2026-01-03
