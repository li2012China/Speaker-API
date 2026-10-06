**Speaker API** is an offline, privacy-first text-to-speech (TTS) middleware for Minecraft. It converts in-game text — chat, system messages, toasts, and any text submitted by other mods — into natural, human-like speech played through your speakers. Everything runs locally on your machine: no network requests, no cloud, no accounts.

> 我突然发现有很多人抵制AI模组，我非常能理解且感到抱歉。但是在抱歉之余，我想说的是：我们真的想为mc社区做些什么。我们使用AI，但我们不是被AI腐蚀，我们仍旧有着自己的想法和创意，只是没有能力自己做出来。我完全可以大大方方地告诉您，我们使用了WorkBuddy。感谢您伟大的宽容。

<details>
<summary>中文</summary>
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
</details>

## What it does
- Local, offline TTS powered by sherpa-onnx and Kokoro voice models (both Apache-2.0).
- Chinese-first reading with mixed Chinese/English support and multiple built-in voices.
- Other mods can register their own "sources" and submit text through a simple API to receive spoken feedback.
- Optional: replace the system narrator (Windows Narrator / SAPI) so other mods and the game itself speak through this mod instead.
- Position-aware audio: bind speech to an entity or block so the voice appears to come from that location in the world.

## Why you might want it
- Fully offline and private — your text never leaves your computer.
- Gives Minecraft, and accessibility-focused mods, a real, natural-sounding voice instead of silent text.
- Lightweight and client-side only; it does not affect game servers.

## Important before you download
- Client-side only. Install on the Minecraft client (Forge / NeoForge), not on a server.
- On first use it downloads a voice model (about 380 MB). A download-mirror option is included for faster fetching; you can also run `/speakerapi download kokoro-multilang`.
- Supported versions: Minecraft 1.19.2 (Forge), 1.20.1 (Forge), 1.21.1 (NeoForge).
- Requires Java 17 for the Forge builds, Java 21 for the NeoForge build.

## Commands
- `/speakerapi test` — speak a sample sentence (also loads the model if needed).
- `/speakerapi status` — show engine and model status in chat.
- `/speakerapi model <id>` — select and load a model.
- `/speakerapi download <id>` — download a model only.
- `/speakerapi stop` — stop all speech.
- `/speakerapi voices` — list available voices.

## Credits
- Speech engine: sherpa-onnx (Apache-2.0).
- Voice models: Kokoro (Apache-2.0) and other open-source models.
- Author: li2012China.

---
## API Usage (for other mod developers)

Speaker API exposes a stable, cross-version API under the package `net.speakerapi`. All methods are static. The mod is client-side only: API calls made from a dedicated/server environment are safe no-ops (they simply do nothing), so guard with `isEnabled()` / `isEngineReady()` if you also run headless.

### Adding the dependency
On Forge / NeoForge, add a dependency on `speakerapi` (any supported version). At runtime the class `net.speakerapi.SpeakerAPI` is available whenever the mod is loaded.

### Basic speech
```java
import net.speakerapi.SpeakerAPI;
import net.speakerapi.SpeakerAPI.Priority;
import net.speakerapi.core.VoiceProfile;
import net.minecraft.network.chat.Component;

// Speak a line from your mod ("mymod" is the source id).
SpeakerAPI.say(Component.literal("Hello, world"), "mymod", VoiceProfile.ZH_FEMALE_NATURAL, Priority.NORMAL);

// Or with a plain string:
SpeakerAPI.say("Watch your step", "mymod", VoiceProfile.ZH_MALE_NATURAL, Priority.HIGH);
```
- `sourceId` — string identifying your mod; used for per-source filtering/registration.
- `voice` — a `VoiceProfile`. Built-ins: `VoiceProfile.ZH_FEMALE_NATURAL`, `VoiceProfile.ZH_MALE_NATURAL`. Discover more via `availableVoices()` / `voiceFor("zh_female_natural")`.
- `priority` — `Priority.LOW | NORMAL | HIGH | ANNOUNCE`.

### Cancellation & completion
The `say(Component/String, …)` overloads return `CompletableFuture<Void>` (completes when speech finishes). The positioned / builder variants return a `SpeakHandle`:
```java
SpeakerAPI.SpeakHandle handle = SpeakerAPI.sayForEntity(player, "Follow me");
handle.cancel();                              // stop this utterance early
boolean done = handle.isDone();
handle.future().thenRun(() -> System.out.println("finished"));
```

### Positioned & bound (follow) audio
```java
// One-shot spatialized at a coordinate / entity / block (snapshot at call time):
SpeakerAPI.sayAt("Danger zone", "mymod", null, Priority.HIGH, someBlockPos);
SpeakerAPI.sayAt("Enemy approaching", "mymod", null, Priority.HIGH, someEntity);

// Bound audio: the voice follows a moving entity for the whole utterance:
SpeakerAPI.SpeakHandle h = SpeakerAPI.sayForEntity(player, "Come with me");
SpeakerAPI.SpeakHandle b = SpeakerAPI.sayForBlock(blockPos, "This is the workbench");
```

For full control, use the builder:
```java
SpeakerAPI.SayOptions opt = SpeakerAPI.SayOptions.builder()
    .text("Quest complete")
    .sourceId("mymod")
    .voice(VoiceProfile.ZH_FEMALE_NATURAL)
    .priority(Priority.ANNOUNCE)
    .volume(0.8f)                                       // optional per-call gain
    .category(net.minecraft.sounds.SoundSource.RECORDS) // optional MC sound category
    .bindTo(targetEntity)                               // follow the entity (eye height)
    .onSentenceStart(s -> { /* per-sentence */ })
    .onSentenceDone(s -> { /* per-sentence */ })
    .onComplete(() -> { /* whole utterance done */ })
    .build();
SpeakerAPI.say(opt);
```
`bindTo(Entity)` / `bindTo(BlockPos)` make the voice track the target; you can also pass a custom `PositionProvider` (`net.speakerapi.core.PositionProvider`) for arbitrary moving anchors. Spatialization needs a client-side listener pose; if unavailable it degrades to centered playback (still audible).

### Engine readiness
The engine loads the voice model asynchronously on first use. Check or wait before relying on speech:
```java
if (SpeakerAPI.isEngineReady()) {
    // speak now
}
SpeakerAPI.addEngineReadyListener(() -> { /* engine loaded */ });
```

### Models (select / download)
```java
SpeakerAPI.knownModelIds();                 // supported model ids
SpeakerAPI.selectModel("kokoro-multilang"); // set default + download if missing + hot-reload
SpeakerAPI.downloadModel("kokoro-multilang"); // download only (CompletableFuture<Boolean>)
SpeakerAPI.listModels();                    // local readiness snapshot
```

### Other controls
```java
SpeakerAPI.setEnabled(boolean);          // global mute toggle
SpeakerAPI.stopAll();                    // stop every active utterance
SpeakerAPI.setCategory(SoundSource);     // bind output to a MC sound category
SpeakerAPI.setReplaceSystemTts(boolean); // route the system narrator through this mod
SpeakerAPI.registerSource("mymod", text -> true); // register a named source
```

### Notes
- Client-side only. All speech is local; no text leaves the machine.
- The API surface is identical across Minecraft 1.19.2 / 1.20.1 (Forge) and 1.21.1 (NeoForge).
