# Modrinth 简介（合规版）

> 字段说明：
> - **Summary（摘要字段）**：纯文本、无格式、不重复标题，建议 ≤ 150 字符。
> - **Description（描述区）**：支持 Markdown，必须含英文（下方附中文翻译）。
> - 发布时请勾选「Contains AI-generated content」披露（见文末说明）。

---

## Summary（摘要字段，直接粘贴）

Offline text-to-speech for Minecraft: turns chat, system text, and other mods' messages into natural human-like voice — fully local, no internet.

---

## Description（描述区，直接粘贴）

**Speaker API** is an offline, privacy-first text-to-speech (TTS) middleware for Minecraft. It converts in-game text — chat, system messages, toasts, and any text submitted by other mods — into natural, human-like speech played through your speakers. Everything runs locally on your machine: no network requests, no cloud, no accounts.

### What it does
- Local, offline TTS powered by sherpa-onnx and Kokoro voice models (both Apache-2.0).
- Chinese-first reading with mixed Chinese/English support and multiple built-in voices.
- Other mods can register their own "sources" and submit text through a simple API to receive spoken feedback.
- Optional: replace the system narrator (Windows Narrator / SAPI) so other mods and the game itself speak through this mod instead.
- Position-aware audio: bind speech to an entity or block so the voice appears to come from that location in the world.

### Why you might want it
- Fully offline and private — your text never leaves your computer.
- Gives Minecraft, and accessibility-focused mods, a real, natural-sounding voice instead of silent text.
- Lightweight and client-side only; it does not affect game servers.

### Important before you download
- Client-side only. Install on the Minecraft client (Forge / NeoForge), not on a server.
- On first use it downloads a voice model (about 380 MB). A download-mirror option is included for faster fetching; you can also run `/speakerapi download kokoro-multilang`.
- Supported versions: Minecraft 1.19.2 (Forge), 1.20.1 (Forge), 1.21.1 (NeoForge).
- Requires Java 17 for the Forge builds, Java 21 for the NeoForge build.

### Commands
- `/speakerapi test` — speak a sample sentence (also loads the model if needed).
- `/speakerapi status` — show engine and model status in chat.
- `/speakerapi model <id>` — select and load a model.
- `/speakerapi download <id>` — download a model only.
- `/speakerapi stop` — stop all speech.
- `/speakerapi voices` — list available voices.

### Credits
- Speech engine: sherpa-onnx (Apache-2.0).
- Voice models: Kokoro (Apache-2.0) and other open-source models.
- Author: li2012China.

---

### 中文翻译（如需要可附在英文之后）

**Speaker API** 是一款离线、隐私优先的 Minecraft 文字转语音（TTS）中间件。它把游戏内文字——聊天、系统消息、弹窗提示，以及其它模组提交的任何文本——转换为自然、拟人化的语音，通过扬声器播放。一切都在本机本地运行：无网络请求、无云端、无账号。

**功能**
- 本地离线 TTS，由 sherpa-onnx 与 Kokoro 语音模型驱动（均为 Apache-2.0）。
- 中文优先，支持中英混读，内置多种嗓音。
- 其它模组可注册自己的「来源」并通过简单 API 提交文本以获得语音反馈。
- 可选：替换系统讲述人（Windows 讲述人 / SAPI），让其它模组与游戏本体都改用本模组发声。
- 位置感知音频：把语音绑定到实体或方块，使声音仿佛从世界中的该位置传来。

**为什么下载**
- 完全离线、隐私安全——你的文本绝不会离开本机。
- 为 Minecraft 及无障碍类模组提供真实、自然的语音，告别无声文字。
- 轻量且仅客户端运行，不影响游戏服务器。

**下载前须知**
- 仅客户端：请装在 Minecraft 客户端（Forge / NeoForge），不要装到服务器。
- 首次使用会下载语音模型（约 380 MB）。内置下载镜像以加速；也可执行 `/speakerapi download kokoro-multilang`。
- 支持版本：Minecraft 1.19.2（Forge）、1.20.1（Forge）、1.21.1（NeoForge）。
- Forge 版需 Java 17，NeoForge 版需 Java 21。

**指令**
- `/speakerapi test` — 朗读示例句（必要时加载模型）。
- `/speakerapi status` — 在聊天栏显示引擎与模型状态。
- `/speakerapi model <id>` — 选择并加载模型。
- `/speakerapi download <id>` — 仅下载模型。
- `/speakerapi stop` — 停止所有朗读。
- `/speakerapi voices` — 列出可用嗓音。

**署名**
- 语音引擎：sherpa-onnx（Apache-2.0）。
- 语音模型：Kokoro（Apache-2.0）等开源模型。
- 作者：li2012China。

---

## 发布必读（合规提示）
- **AI 披露**：本项目代码与页面文字在生成过程中使用了生成式 AI 工具辅助，按 Modrinth 规则 §6.1 须在项目页勾选「Contains AI-generated content」披露；运行时引擎与语音模型均为第三方开源组件，按各自许可证使用。
- **元数据一致**：license 填 `Apache-2.0`；环境（Environment）选 `Client`；并在 Dependencies 区按版本标注 Forge / NeoForge 依赖。
- **图标**：`Aluminum.png`（你提供的原图，非 AI 生成）已设为模组图标，符合 §6.2「禁止 AI 生成图片」要求。
