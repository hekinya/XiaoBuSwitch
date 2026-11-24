<div align="center">

<img src="assets/icon/xiaobu-switch-icon.png" width="112" height="112" alt="小布 Switch 图标">

# 小布 Switch

**为 ColorOS 小布助手（Breeno）接入自定义文本、识图与绘图大模型的 LSPosed 模块**

基于上游 [SeimoDev/XiaoAiSwitch](https://github.com/SeimoDev/XiaoAiSwitch)（`v0.10.12`）二次开发 · 采用 [GPL-3.0-only](LICENSE) 开源协议

</div>

## 当前功能

- **自定义文本模型接入**：支持 OpenAI 兼容 API（自定义 Endpoint / API Key / 模型名 / System Prompt）以及手机端直连 Codex 订阅。
- **图片输入 / 识图（Vision）**：支持在小布原生对话中选图/拍照后带图提问（以及纯发图自动识图），自动读取本地原图并压缩转码后发给自定义多模态模型，同时对共享房间历史脱敏以兼容后续纯文本多轮对话。
- **绘图（文生图 / 图生图，Image）**：支持 `/draw <提示词>` 指令及自然语言自动调用绘图工具（支持文生图、基于附件的原图编辑、以及基于上一张生成图的连续改图），兼容 OpenAI `/images/generations` & `/images/edits` 与 Codex 原生绘图；生成图片落盘到本地缓存后，直接通过小布原生 Markdown 扩展图片卡片（`MyAI.StreamTextCard` + `CardInfo` / `ImageInfos`）渲染，复用原生圆角展示、大图预览与保存相册。
- **复用小布原生 UI、Markdown 与多轮上下文**：在小布原生对话页面中直接渲染用户消息气泡与原生 Markdown 回答卡片，并按当前会话（Room）维护多轮上下文。
- **安全回退优先**：
  - 模块启动时会校验小布包名、版本号及全部内部反射签名；若版本或方法结构不匹配，模块不会强行注入，保持小布原厂链路。
  - 识图附件 Hook 与基础文本 Hook 隔离安装；关闭模块总开关、未启用对应通道、未配置 Provider、或触发小布原生工具标签（`extra_box_toolTag`）及原生保护条件（服务开关、上传中、每日对话上限、全双工模式、特定模式及旅行计划状态）时，自动交回小布原厂处理。
  - 回答请求异常或取消时会在卡片中反馈错误并恢复空闲状态，避免界面卡死。

## 功能边界与未支持项

以下上游小爱版功能**尚未接入或未验证**：

- 手机控制与系统工具
- 独立语音识别 / TTS 播报与双工语音链路（语音转文字是否走同一文本提交入口尚未完整验证）
- 非 `13.4.3 (130403)` 的其他小布版本

## 兼容性与真机验证

| 项目 | 参数 |
| --- | --- |
| 模块包名 | `io.github.xiaobuiswitch` |
| LSPosed 作用域 | `com.heytap.speechassist`（小布助手） |
| 已适配小布版本 | `13.4.3`（`versionCode = 130403`） |
| Android SDK | `minSdk 27` · `targetSdk 35` · `compileSdk 36` |
| 模块框架 | LSPosed（最低声明 Xposed API 82） |
| 构建工具链 | JDK 17 · Gradle 8.13 · AGP 8.13.2 · Kotlin 2.3.20 |
| 真机验证环境 | `PKG110` · ColorOS `16.0.9.400(CN01)` · Android 16 · 小布 `13.4.3 (130403)` |

真机已验证项目：
- LSPosed 注入与主进程过滤、基础文本与识图附件全部目标类/方法/字段签名校验、模块状态页激活回传。
- 通过 `Codex 订阅` 进行小布原生对话界面文本问答、Markdown 渲染与多轮上下文。
- 图片输入 / 识图（选图提问与纯发图识图）以及文生图 / 图生图并通过小布原生图片卡片渲染。
- 模块与系统原厂链路的开关切换与安全回退。

> 提示：如果手机上同时启用了其他 Hook 小布对话入口的 LSPosed 模块，测试或使用时建议避免同时接管同一入口以免冲突。

## 安装与配置

1. 按下方「从源码构建」编译并安装 Debug APK（或从 Releases 下载安装）。
2. 在 **LSPosed 管理器** 中启用 **小布 Switch**，确认作用域勾选 **小布助手**（`com.heytap.speechassist`）。
3. 强行停止小布助手并重新打开一次小布，使其主进程加载模块。
4. 打开 **小布 Switch**：
   - 点击顶部状态卡片确认已显示 **已激活** 及 `已加载小布 13.4.3 文本 Hook`。
   - 进入 **AI Provider**，添加 OpenAI 兼容 API 源或登录 Codex 订阅。
   - 进入 **文本对话**，开启 **使用自定义服务**，选择 Provider、模型名称并按需填写 System Prompt，点击 **保存并立即应用**。
   - 返回首页确认 **模块接管** 总开关已开启，即可在小布对话框直接提问。

## 从源码构建

需要 JDK 17、Android SDK Platform 36（`compileSdk 36`）以及可访问 Gradle/Maven 仓库的网络环境。

```powershell
git clone https://github.com/hekinya/XiaoBuSwitch.git
cd XiaoBuSwitch
.\gradlew.bat test jacocoTestCoverageVerification :app:lintDebug :app:assembleDebug
```

Linux / macOS 可将 `.\gradlew.bat` 替换为 `./gradlew`。

构建产物位置：`app/build/outputs/apk/debug/app-debug.apk`。

## 免责声明

- 本项目仅供技术研究与学习交流使用，与 OPPO、ColorOS、小布助手（Breeno）及任何 AI 模型服务商均无官方关联。
- 使用本项目所产生的任何后果（包括但不限于系统或应用更新导致的模块失效、第三方 API 费用与合规责任等）均由使用者自行承担；请勿将本项目用于违反当地法律法规或相关服务条款的用途。
- 本项目遵循 [GPL-3.0-only](LICENSE) 开源协议，按“原样（AS IS）”提供，不作任何明示或暗示的担保。

## 致谢与协议

- 上游项目：[SeimoDev/XiaoAiSwitch](https://github.com/SeimoDev/XiaoAiSwitch)（适配基线 `v0.10.12`）
- 开源协议：[GPL-3.0-only](LICENSE)
