# Workflow

> 练手项目，想到啥写啥。

一个 Android 自动化实验项目，当前主要做两件事：
- 通过无障碍服务自动执行“小米钱包”里的一段固定流程
- 提供 OpenAI 兼容接口的聊天能力（支持多配置、可选图片输入）

## 功能概览

- 首页：检查权限状态（无障碍、使用情况访问、Shizuku）并启动自动流程
- 执行页：查看实时日志、停止流程、清空日志
- 聊天页：用当前模型配置发起对话（可附带图片）
- 设置页：管理 Base URL / API Key / Model 等配置

## 环境要求

- Android Studio（建议最新稳定版）
- Android SDK：`compileSdk = 36`，`minSdk = 24`
- JDK 17（AGP 8.13.x 推荐）
- 一台 Android 7.0+ 真机（涉及无障碍和系统权限，真机体验更稳定）

## 快速开始

1. 克隆并打开项目：`D:\androidporjects\workflow`
2. 用 Android Studio 同步 Gradle
3. 连接手机并运行 `app` 模块（Debug）
4. 首次启动后，按下面“使用方法”完成权限和配置

## 使用方法

1. 打开 App，进入首页  
2. 先开启无障碍服务（必须）  
3. 建议开启“使用情况访问权限”（提升回跳稳定性）  
4. 如果你装了 Shizuku，启动并授权（可选，未就绪会自动降级）  
5. 点击“开始自动领取”，App 会尝试拉起 `com.mipay.wallet` 并执行流程  
6. 在“执行页”查看实时日志，必要时可手动停止  
7. 如果要用聊天功能，到“设置页”填写：
   - Base URL（例如 OpenAI 兼容网关地址）
   - API Key
   - Model（默认 `gpt-4o-mini`）
8. 切到“聊天页”开始对话（可选图，单图限制 4MB）

## 运行与测试（命令行）

```powershell
# 构建 Debug 包
.\gradlew.bat assembleDebug

# 安装到已连接设备
.\gradlew.bat installDebug

# 单元测试
.\gradlew.bat test
```

## 注意事项

- 这是实验性质项目，流程和策略会频繁调整
- 自动化能力依赖系统权限与目标 App 页面结构，可能随版本变化失效
- 请仅在你认可风险的设备/账号环境下使用
