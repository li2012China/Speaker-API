package net.speakerapi.core;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Speaker API 的核心公共入口：<b>纯 core 版</b>，不含任何 MC / loader 类型。
 *
 * <p>版本子项目在此之上包一层 {@code net.speakerapi.SpeakerAPI}，把
 * {@code Component / Vec3 / BlockPos / Entity / SoundSource} 适配成本层的
 * {@code String / Pos3 / SoundCategory}，供其它模组按熟悉的形态调用。</p>
 *
 * <pre>
 *   SpeakerCore.SpeakHandle h = SpeakerCore.say(SpeakerCore.SayOptions.builder()
 *           .text("前方有怪物")
 *           .sourceId("combat")
 *           .voice(VoiceProfile.ZH_MALE_NATURAL)
 *           .priority(SpeakerCore.Priority.HIGH)
 *           .at(new Pos3(x, y, z))          // 空间化发声
 *           .onComplete(() -&gt; {})
 *           .build());
 *   h.cancel();
 *   SpeakerCore.stopAll();
 * </pre>
 *
 * <p>句内连续、跨调用并发：单次 {@code say()} 的整段文本作为「一句话」顺序朗读（各分段顺序
 * 串接，不重叠）；不同 {@code say()} 调用之间并发、互不排队。绑定坐标时语音从该方向传来
 * （左右声像 + 距离衰减）。</p>
 */
public final class SpeakerCore {

    public enum Priority {
        /** Dropped if the engine isn't ready yet. */
        LOW,
        NORMAL,
        HIGH,
        /** Important announcements: queued even while loading. */
        ANNOUNCE
    }

    private static Config config;
    private static TtsEngine engine;
    private static AudioPlayer player;
    private static TextPreprocessor preprocessor;
    private static SpeechCache cache;
    private static ModelManager models;

    private static volatile boolean enabled = true;
    private static volatile boolean initialized = false;

    /** TTS 输出绑定的声音分类（对应 MC 的 SoundSource）。默认 voice。 */
    private static volatile SoundCategory category = SoundCategory.VOICE;

    /** 是否接管系统讲述人（系统 TTS / SAPI）。默认开启。 */
    private static volatile boolean replaceSystemTts = true;

    private static final Map<String, Predicate<String>> SOURCES = new ConcurrentHashMap<>();

    /** Logical voice id -> VoiceProfile. */
    private static final Map<String, VoiceProfile> VOICES = new ConcurrentHashMap<>();

    static {
        VOICES.put("zh_female_natural", VoiceProfile.ZH_FEMALE_NATURAL);
        VOICES.put("zh_male_natural", VoiceProfile.ZH_MALE_NATURAL);
    }

    /** 所有进行中的朗读句柄（用于 stopAll / activeHandles）。 */
    private static final Set<SpeakHandle> ACTIVE = ConcurrentHashMap.newKeySet();

    private SpeakerCore() {
    }

    // ===== 嗓音 / 分类 =====

    /** Resolve a logical voice id (from config) to a {@link VoiceProfile}. */
    public static VoiceProfile voiceFor(String id) {
        return id == null ? VoiceProfile.ZH_FEMALE_NATURAL : VOICES.getOrDefault(id, VoiceProfile.ZH_FEMALE_NATURAL);
    }

    /** 列出所有可用嗓音。 */
    public static List<VoiceProfile> availableVoices() {
        return new ArrayList<>(VOICES.values());
    }

    /** 列出所有可用嗓音的逻辑 id。 */
    public static List<String> availableVoiceIds() {
        return new ArrayList<>(VOICES.keySet());
    }

    /** 当前绑定的声音分类。 */
    public static SoundCategory getCategory() {
        return category;
    }

    /** 直接设置绑定分类。 */
    public static void setCategory(SoundCategory src) {
        if (src != null) {
            category = src;
        }
    }

    /** 按名称设置绑定分类；名称非法时返回 false 且不改动。 */
    public static boolean setCategory(String name) {
        SoundCategory c = SoundCategory.fromId(name);
        if (c == null) {
            return false;
        }
        category = c;
        return true;
    }

    /**
     * 该分类当前的滑块音量（0~1）。通过 {@link Platform#categoryLevel(SoundCategory)} 取得；
     * 无平台（纯测试）时返回 1.0。把分类调到 0 即等效静音。
     */
    public static double categoryVolume(SoundCategory cat) {
        Platform p = Core.platform();
        if (p == null) {
            return 1.0;
        }
        try {
            return p.categoryLevel(cat);
        } catch (Throwable ignored) {
            return 1.0;
        }
    }

    private static float effectiveVolume(SoundCategory cat, Float override) {
        double level = config != null && config.applyCategoryVolume() ? categoryVolume(cat) : 1.0;
        float base = override != null ? override : (config != null ? config.volume() : 1.0f);
        return (float) (base * level);
    }

    // ===== 系统讲述人接管开关 =====

    /** 是否接管系统讲述人。 */
    public static boolean isReplaceSystemTts() {
        return replaceSystemTts;
    }

    /** 实时开关系统讲述人接管（无需重启）。 */
    public static void setReplaceSystemTts(boolean on) {
        replaceSystemTts = on;
    }

    /**
     * 系统讲述人入口：把 UI/聊天/提示文本交给本模组 TTS 朗读。
     * 使用默认语音与当前绑定分类，HIGH 优先级（不因加载中而丢弃）。
     */
    public static SpeakHandle narratorSay(String text) {
        return sayNarrator(text);
    }

    /** 同 {@link #narratorSay}。 */
    public static SpeakHandle sayNarrator(String text) {
        if (text == null || text.isEmpty()) {
            SpeakHandle h = new SpeakHandle();
            h.complete();
            return h;
        }
        return say(SayOptions.builder()
                .text(text)
                .sourceId("narrator")
                .priority(Priority.HIGH)
                .build());
    }

    // ===== 引擎状态 / 就绪通知 =====

    /** 离线 TTS 引擎是否就绪（模型已加载、可合成）。 */
    public static boolean isEngineReady() {
        return engine != null && engine.isReady();
    }

    /** 注册引擎就绪监听：若已就绪立即触发，否则在就绪瞬间触发。 */
    public static void addEngineReadyListener(Runnable listener) {
        if (engine != null) {
            engine.addReadyListener(listener);
        }
    }

    /** 移除引擎就绪监听。 */
    public static void removeEngineReadyListener(Runnable listener) {
        if (engine != null) {
            engine.removeReadyListener(listener);
        }
    }

    // ===== 句柄 / 队列控制 =====

    /** 当前进行中的朗读句柄快照。 */
    public static List<SpeakHandle> activeHandles() {
        return new ArrayList<>(ACTIVE);
    }

    /** 当前正在朗读的数量。 */
    public static int activeCount() {
        return ACTIVE.size();
    }

    /** 停止并取消所有正在进行的朗读。 */
    public static void stopAll() {
        for (SpeakHandle h : activeHandles()) {
            h.cancel();
        }
        ACTIVE.clear();
    }

    // ===== SayOptions 流式构造器 =====

    /** 一次朗读的可选参数集。用 {@link #builder()} 构造。 */
    public static final class SayOptions {

        public final String text;
        public final String sourceId;
        public final VoiceProfile voice;
        public final Priority priority;
        public final SoundCategory category;   // null => 全局默认分类
        public final Float volumeOverride;     // null => general.volume；否则作为基础增益（仍乘分类滑块）
        public final Pos3 position;            // null => 全局；非 null => 空间化发声（调用瞬间快照）
        public final PositionProvider follow;  // 非 null => 绑定到实体/方块：每段实时取坐标跟随
        public final Consumer<String> onSentenceStart;
        public final Consumer<String> onSentenceDone;
        public final Runnable onComplete;

        private SayOptions(Builder b) {
            this.text = b.text;
            this.sourceId = b.sourceId == null ? "api" : b.sourceId;
            this.voice = b.voice;
            this.priority = b.priority == null ? Priority.NORMAL : b.priority;
            this.category = b.category;
            this.volumeOverride = b.volumeOverride;
            this.position = b.position;
            this.follow = b.follow;
            this.onSentenceStart = b.onSentenceStart;
            this.onSentenceDone = b.onSentenceDone;
            this.onComplete = b.onComplete;
        }

        public static Builder builder() {
            return new Builder();
        }

        public static final class Builder {

            private String text;
            private String sourceId;
            private VoiceProfile voice;
            private Priority priority;
            private SoundCategory category;
            private Float volumeOverride;
            private Pos3 position;
            private PositionProvider follow;
            private Consumer<String> onSentenceStart;
            private Consumer<String> onSentenceDone;
            private Runnable onComplete;

            public Builder text(String t) {
                this.text = t;
                return this;
            }

            public Builder sourceId(String s) {
                this.sourceId = s;
                return this;
            }

            public Builder voice(VoiceProfile v) {
                this.voice = v;
                return this;
            }

            public Builder priority(Priority p) {
                this.priority = p;
                return this;
            }

            public Builder category(SoundCategory c) {
                this.category = c;
                return this;
            }

            public Builder category(String c) {
                this.category = SoundCategory.fromId(c);
                return this;
            }

            public Builder volume(Float v) {
                this.volumeOverride = v;
                return this;
            }

            /** 绑定世界坐标：语音从该方向传来（空间化，调用瞬间快照）。 */
            public Builder at(Pos3 pos) {
                this.position = pos;
                return this;
            }

            /**
             * 绑定到实体 / 方块：语音<b>持续跟随</b>该源移动（每段实时取坐标）。
             * 方块可用 {@link #at(Pos3)} 等价表达；实体请用此处的 {@link PositionProvider}。
             */
            public Builder at(PositionProvider provider) {
                this.follow = provider;
                return this;
            }

            /** 从已有 SayOptions 复制全部字段（便于在其基础上叠加绑定）。 */
            public Builder from(SayOptions o) {
                if (o == null) {
                    return this;
                }
                this.text = o.text;
                this.sourceId = o.sourceId;
                this.voice = o.voice;
                this.priority = o.priority;
                this.category = o.category;
                this.volumeOverride = o.volumeOverride;
                this.position = o.position;
                this.follow = o.follow;
                this.onSentenceStart = o.onSentenceStart;
                this.onSentenceDone = o.onSentenceDone;
                this.onComplete = o.onComplete;
                return this;
            }

            public Builder onSentenceStart(Consumer<String> c) {
                this.onSentenceStart = c;
                return this;
            }

            public Builder onSentenceDone(Consumer<String> c) {
                this.onSentenceDone = c;
                return this;
            }

            public Builder onComplete(Runnable r) {
                this.onComplete = r;
                return this;
            }

            public SayOptions build() {
                return new SayOptions(this);
            }
        }
    }

    /** 一次朗读的可取消/可查询句柄。 */
    public static final class SpeakHandle {

        private final CompletableFuture<Void> future = new CompletableFuture<>();
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private final AtomicReference<AudioPlayer.Session> session = new AtomicReference<>();

        SpeakHandle() {
            ACTIVE.add(this);
        }

        void attachSession(AudioPlayer.Session s) {
            session.set(s);
        }

        boolean isCancelled() {
            return cancelled.get();
        }

        /** 取消本次朗读：立即关闭音频线并停止后续分段。 */
        public boolean cancel() {
            if (!cancelled.compareAndSet(false, true)) {
                return false;
            }
            AudioPlayer.Session s = session.get();
            if (s != null) {
                s.close();
            }
            future.cancel(false);
            ACTIVE.remove(this);
            return true;
        }

        /** 是否已结束（正常完成 / 取消 / 异常）。 */
        public boolean isDone() {
            return future.isDone();
        }

        /** 是否仍在进行中。 */
        public boolean isActive() {
            return !future.isDone() && !cancelled.get();
        }

        /** 该句朗读的完成 future。 */
        public CompletableFuture<Void> future() {
            return future;
        }

        void complete() {
            ACTIVE.remove(this);
            future.complete(null);
        }

        void completeExceptionally(Throwable t) {
            ACTIVE.remove(this);
            future.completeExceptionally(t);
        }
    }

    // ===== 规范入口 =====

    /**
     * 规范朗读入口：接收 {@link SayOptions}，返回 {@link SpeakHandle}。
     * 句内顺序串接，跨 say 并发；绑定 position 时空间化发声。
     */
    public static SpeakHandle say(SayOptions o) {
        SpeakHandle h = new SpeakHandle();
        if (!initialized || !enabled) {
            h.complete();
            return h;
        }
        String raw = o.text;
        if (raw == null || raw.isEmpty()) {
            h.complete();
            return h;
        }
        Predicate<String> f = SOURCES.get(o.sourceId);
        if (f != null && !f.test(raw)) {
            h.complete();
            return h;
        }
        // Global regex blacklist (commands, coords, tokens).
        for (String rx : config.filterRegex()) {
            if (Pattern.compile(rx).matcher(raw).find()) {
                h.complete();
                return h;
            }
        }
        List<String> sentences = preprocessor.prepare(raw);
        if (sentences.isEmpty()) {
            h.complete();
            return h;
        }
        if (!engine.isReady() && o.priority == Priority.LOW) {
            h.complete();
            return h;
        }

        SoundCategory useCat = o.category != null ? o.category : category;
        VoiceProfile v = o.voice == null ? VoiceProfile.ZH_FEMALE_NATURAL : o.voice;
        float speed = config.speed();
        float vol = effectiveVolume(useCat, o.volumeOverride);
        Pos3 pos = o.position;

        // 本次 say() 开一条独占线（定位时立体声，否则单声道）；异步不阻塞调用线程。
        // 绑定到实体/方块时用 follow 会话：每段实时取坐标，语音持续跟随移动源。
        CompletableFuture<AudioPlayer.Session> opened = (o.follow != null)
                ? player.openFollowAsync(o.follow)
                : player.openAsync(pos);
        opened.thenAccept(session -> {
            h.attachSession(session);
            if (session == null || h.isCancelled()) {
                if (session != null) {
                    session.close();
                }
                h.complete();
                return;
            }
            // 句内顺序串接；每段前检查取消标记。
            CompletableFuture<Void> c = CompletableFuture.completedFuture(null);
            for (String seg : sentences) {
                final String s = seg;
                c = c.thenCompose(prev -> {
                    if (h.isCancelled()) {
                        return CompletableFuture.completedFuture(null);
                    }
                    if (o.onSentenceStart != null) {
                        try {
                            o.onSentenceStart.accept(s);
                        } catch (Throwable cbEx) {
                            // 监听异常不影响朗读
                        }
                    }
                    return speakSegment(s, v, speed, vol, session).thenApply(x -> {
                        if (o.onSentenceDone != null && !h.isCancelled()) {
                            try {
                                o.onSentenceDone.accept(s);
                            } catch (Throwable cbEx) {
                                // 同上
                            }
                        }
                        return x;
                    });
                });
            }
            c.whenComplete((x, ex) -> {
                session.close();
                if (ex != null) {
                    h.completeExceptionally(ex);
                } else {
                    h.complete();
                }
                if (o.onComplete != null) {
                    try {
                        o.onComplete.run();
                    } catch (Throwable ignored) {
                        // 同上
                    }
                }
            });
        }).exceptionally(ex -> {
            h.completeExceptionally(ex);
            return null;
        });
        return h;
    }

    /** Convenience：纯文本 + 默认参数（版本层 shim 复用）。 */
    public static CompletableFuture<Void> say(String raw, String sourceId, VoiceProfile voice, Priority priority) {
        return say(SayOptions.builder()
                .text(raw)
                .sourceId(sourceId)
                .voice(voice)
                .priority(priority)
                .build()).future();
    }

    /**
     * 把语音绑定到某个会移动的源（实体 / 方块）：朗读期间每段实时取坐标，
     * 声像与距离衰减随源移动而更新。
     *
     * @param provider 坐标提供者（方块返回常量即可；实体返回其当前坐标）
     * @param text     要朗读的文本
     */
    public static SpeakHandle sayBound(PositionProvider provider, String text) {
        if (provider == null) {
            return say(SayOptions.builder().text(text).build());
        }
        return say(SayOptions.builder().text(text).at(provider).build());
    }

    /**
     * 在已有参数基础上再绑定到移动源（保留 voice / priority / category 等设置）。
     */
    public static SpeakHandle sayBound(PositionProvider provider, SayOptions base) {
        if (provider == null) {
            return say(base);
        }
        return say(SayOptions.builder().from(base).at(provider).build());
    }

    /**
     * 合成并写一段到指定会话（同一条线）；session 为 null 时跳过。
     * 不在此开关音频线 —— 线的生命周期由 say() 统一管理。
     */
    private static CompletableFuture<Void> speakSegment(String seg, VoiceProfile v, float speed,
                                                        float vol, AudioPlayer.Session session) {
        if (session == null) {
            return CompletableFuture.completedFuture(null);
        }
        if (cache.has(seg, v.id(), speed)) {
            AudioData cached = cache.load(seg, v.id(), speed);
            if (cached != null) {
                session.write(cached, vol);
                return CompletableFuture.completedFuture(null);
            }
        }
        return engine.synthesize(seg, speed, v.id()).thenAccept(audio -> {
            cache.store(audio, seg, speed);
            session.write(audio, vol);
        });
    }

    // ===== 配置 / 生命周期 =====

    public static boolean readChat() {
        return initialized && config.readChat();
    }

    public static boolean readSystemToast() {
        return initialized && config.readSystemToast();
    }

    public static boolean readAchievements() {
        return initialized && config.readAchievements();
    }

    public static boolean readLowHealth() {
        return initialized && config.readLowHealth();
    }

    /** Register a named source with an optional text filter. */
    public static void registerSource(String id, Predicate<String> filter) {
        SOURCES.put(id, filter == null ? (s -> true) : filter);
    }

    /** 客户端一次性初始化。gameDir 为空时回退到 {@link Platform#gameDir()}。 */
    public static synchronized void bootstrap(Path gameDir) {
        if (initialized) {
            return;
        }
        Path dir = gameDir;
        Platform p = Core.platform();
        if (dir == null && p != null) {
            dir = p.gameDir();
        }
        if (dir == null) {
            throw new IllegalStateException("[SpeakerAPI] bootstrap 失败: 既没有 gameDir 也没有已安装的 Platform");
        }
        config = Config.load(dir.resolve("config/speakerapi/speakerapi-common.toml"));
        models = new ModelManager(dir, config);
        cache = new SpeechCache(models.cacheDir(), config.cacheSize());
        preprocessor = new TextPreprocessor(false, config.maxTextLength());
        player = new AudioPlayer(config.resampleToHz());
        engine = TtsEngine.instance();
        engine.init(config, models);
        enabled = config.enabled();

        SoundCategory parsed = SoundCategory.fromId(config.audioCategory());
        category = parsed != null ? parsed : SoundCategory.VOICE;
        replaceSystemTts = config.replaceSystemTts();

        initialized = true;
        Core.log().info("[SpeakerAPI] Bootstrapped. enabled={} category={} loader={} mc={}",
                enabled, category.id(), p != null ? p.loaderName() : "none",
                p != null ? p.minecraftVersion() : "none");
    }

    /** 列出所有已知模型及其本地就绪状态（快照）。 */
    public static List<ModelManager.ModelInfo> listModels() {
        List<ModelManager.ModelInfo> out = new ArrayList<>();
        if (models == null) {
            return out;
        }
        for (String id : models.knownModelIds()) {
            ModelManager.ModelInfo info = models.resolve(id);
            if (info != null) {
                out.add(info);
            }
        }
        return out;
    }

    /** 当前模型下载镜像策略（供状态汇报展示）。 */
    public static String modelMirror() {
        return config == null ? "auto" : config.modelMirror();
    }

    /** 所有已知（已登记）的模型 id。 */
    public static List<String> knownModelIds() {
        return models == null ? new ArrayList<>() : models.knownModelIds();
    }

    /** 给定 id 是否是被支持的已知模型。 */
    public static boolean isKnownModel(String id) {
        return models != null && id != null && models.knownModelIds().contains(id);
    }

    /**
     * 选择并加载模型：设为默认（持久化到配置），确保模型存在（缺失则下载），然后重载引擎。
     *
     * @return 异步结果，{@code true} 表示切换并加载成功。
     */
    public static CompletableFuture<Boolean> selectModel(String id) {
        if (!isKnownModel(id)) {
            return CompletableFuture.completedFuture(false);
        }
        if (config != null) {
            config.setDefaultModel(id);
        }
        if (engine != null) {
            return engine.loadModel(id);
        }
        return CompletableFuture.completedFuture(false);
    }

    /** 仅下载模型到本地（不切换、不重载引擎）。 */
    public static CompletableFuture<Boolean> downloadModel(String id) {
        if (!isKnownModel(id)) {
            return CompletableFuture.completedFuture(false);
        }
        if (engine != null) {
            return engine.ensureModelAsync(id);
        }
        return CompletableFuture.completedFuture(false);
    }

    /**
     * 生成状态汇报的多行文本（纯数据，不含 MC 类型）。
     *
     * <p>原 {@code ModelStatusScreen} 把同样的内容画在一个 GUI 里；现在改为在聊天栏逐行打印
     * （由版本层用 {@code Component.literal} 包裹后 {@code sendSystemMessage}）。
     * 含 MC 颜色码 {@code §}，版本层需原样透传。</p>
     */
    public static List<String> statusLines() {
        List<String> lines = new ArrayList<>();
        lines.add("§6[SpeakerAPI] 状态汇报");
        lines.add("  §7启用: " + (enabled ? "§a是" : "§c否"));

        TtsEngine eng = engine;
        if (eng != null && eng.isReady()) {
            lines.add("  §7引擎: §a就绪 §7(sampleRate=" + eng.sampleRate() + ")");
        } else if (eng != null && eng.isLoading()) {
            lines.add("  §7引擎: §e加载中… §7(首次需下载模型, 详见日志进度)");
        } else {
            lines.add("  §7引擎: §c未就绪 §7(用 /speakerapi test 触发后台下载/加载)");
        }

        ModelManager.ModelInfo def = models == null ? null : models.defaultModel();
        if (def != null) {
            String state = def.present ? "§a就绪" : "§c缺失";
            lines.add("  §7默认模型: §f" + def.id + " §7[" + state + "] §7license=" + def.license
                    + " sr=" + def.sampleRate);
            if (!def.present && !def.missingFiles.isEmpty()) {
                lines.add("    §c缺失文件: " + String.join(", ", def.missingFiles));
            }
        }

        lines.add("  §7已知模型:");
        for (ModelManager.ModelInfo info : listModels()) {
            lines.add("    " + (info.present ? "§a✔" : "§c✘") + " §f" + info.id
                    + " §7(" + info.license + ", sr=" + info.sampleRate + ")");
        }

        lines.add("  §7声音分类: §f" + category.id());
        lines.add("  §7系统讲述人替换: " + (replaceSystemTts ? "§a开启" : "§c关闭")
                + " §7(接管: " + (NarratorHook.isInstalled() ? "§a已生效" : "§e未生效") + ")");
        lines.add("  §7可用嗓音: §f" + String.join(", ", availableVoiceIds()));
        lines.add("  §7进行中朗读: §f" + activeCount());
        lines.add("  §7模型镜像: §f" + modelMirror());
        return lines;
    }

    /** Global mute / unmute. */
    public static void setEnabled(boolean on) {
        enabled = on;
        if (player != null) {
            player.setMuted(!on);
        }
    }

    public static boolean isEnabled() {
        return enabled && initialized;
    }

    public static void shutdown() {
        stopAll();
        if (engine != null) {
            engine.shutdown();
        }
        if (player != null) {
            player.shutdown();
        }
        initialized = false;
    }
}
