# 幻空屿（Huankongyu）

适配 Android 的 AI 陪伴聊天软件：多角色对话、长期记忆、OpenAI 兼容提供商、网页查询与 MCP 工具调用。

## 功能概览

- **多角色陪伴聊天**：自定义身份 / 性格 / 行为方式 / 表达方式；内置「澜」「玄天」
- **双阶段对话管线**：规划器决策 → 回复器生成 → 情景/字数分段连发
- **长期记忆**：五层记忆（核心/事实/事件/临时/约定）+ 向量与词法混合召回；对话静默后自动总结
- **设备上下文**：时间、农历节日、日历日程、位置（按权限注入）
- **Shizuku 特权通道**：连接 Shizuku/Sui 后以 shell 或 ROOT 身份执行特权命令
- **外部能力**：OpenAI 兼容 API、应用内网页搜索、Streamable HTTP MCP

## 环境要求

| 组件 | 版本 |
|------|------|
| JDK | 17+（推荐 21） |
| Android SDK | Platform 36（+ Build-Tools 36） |
| Gradle | Wrapper 自带（9.4.1） |
| Kotlin / AGP | 见 `gradle/libs.versions.toml` |

配置 SDK 路径（二选一）：

```bash
# 方式 1：环境变量
export ANDROID_HOME="$HOME/Library/Android/sdk"

# 方式 2：local.properties（不要提交）
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties
```

## 构建与测试

```bash
./gradlew test          # JVM 单元测试
./gradlew assembleDebug # Debug APK
./gradlew :app:testDebugUnitTest
```

首次构建会自动下载 Gradle 与依赖。

## 项目结构

```
app/src/main/java/com/huankongyu/app/
├── MainActivity.kt      # Activity 入口 + Compose 界面
├── AppViewModel.kt      # 业务状态与聊天/记忆编排
├── Models.kt            # 数据模型、枚举与常量
├── ChatPrompts.kt       # 规划器 / 回复器提示词
├── ReplySplitter.kt     # 回复分段
├── OpenAiClient.kt      # OpenAI 兼容 API
├── McpHttpClient.kt     # Streamable HTTP MCP
├── WebSearchClient.kt   # 应用内网页查询
├── DeviceContext.kt     # 时间 / 日历 / 位置 / Shizuku 上下文
├── ProviderStore.kt     # SQLite（API Key 经 Keystore 加密）
├── AvatarCropView.kt    # 头像圆形裁剪
├── shizuku/
│   ├── ShizukuClient.kt       # Binder 生命周期、权限、UserService 连接
│   └── ShizukuUserService.kt  # 以 shell/ROOT 身份执行命令
└── ui/theme/            # Material3 主题与色板
```

## Shizuku

本应用通过 [Shizuku-API](https://github.com/RikkaApps/Shizuku-API)（`dev.rikka.shizuku` 13.1.5）连接 [Shizuku](https://github.com/RikkaApps/Shizuku) 或 Sui。

1. 用户安装并启动 [Shizuku](https://shizuku.rikka.app/download/)（未 root 需用 ADB / 无线调试启动）
2. 应用内「我 → Shizuku 连接」请求授权
3. 授权成功后绑定 `ShizukuUserService`，可 `ShizukuClient.exec(command)` 以 shell（uid 2000）或 ROOT（uid 0）执行命令

Manifest 中的 `ShizukuProvider` 与 `<queries>` 包可见性声明是接入所必需的。

## 版本管理约定

- 分支：`main` 为稳定线；功能用短分支（如 `refactor/split-viewmodel`）
- 提交：中文或英文均可，优先说明「为什么改」
- 换行：仓库统一 LF（见 `.gitattributes`）
- 密钥：`local.properties`、`*.jks`、`*.keystore` 等一律不提交

## 许可证

Apache License 2.0
