package net.speakerapi.core;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;

// 日志走 core 抽象 (由版本层注入 Log4j 2), 保证进入 logs/latest.log。

// 以下 sherpa-onnx 类依据规格 A「官方非 Android Java/Kokoro 示例」书写:
//   OfflineTts / OfflineTtsConfig / OfflineTtsModelConfig / OfflineTtsKokoroModelConfig
// 沙箱无网、本地无 jar, 无法反编译 OfflineTts*.class 核对; 若类名/方法名与锁定 release
// 不符, 会编译失败 (见下方逐行 未确认 标注与二选一兼容写法)。
import com.k2fsa.sherpa.onnx.OfflineTts;
import com.k2fsa.sherpa.onnx.OfflineTtsConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig;

/**
 * Singleton wrapper around sherpa-onnx {@link OfflineTts}.
 *
 * <p>Hard constraints (规格 §A/§C):
 * <ul>
 *   <li>单例 + {@code volatile OfflineTts}; 不允许多实例并发共用同一 OfflineTts (非线程安全假设)。</li>
 *   <li>{@link #load()} 为 synchronized, 只在 ttsExecutor 单线程上跑; 不阻塞客户端 tick/render。</li>
 *   <li>{@link #synthesize} 在 ttsExecutor 异步执行, 绝不在 render/tick/listener 同步路径调用 generate。</li>
 *   <li>Kokoro 为默认模型 (神经轻量, 非 eSpeak 纯共振峰主嗓)。</li>
 *   <li>输出采样率由模型决定 (Kokoro 通常 24k); 播放前由 AudioPlayer 重采样到输出格式, 这里不写死 44.1k。</li>
 * </ul>
 */
public final class TtsEngine {

    private static final CoreLogger LOG = Core.log();

    private static final TtsEngine INSTANCE = new TtsEngine();

    public static TtsEngine instance() {
        return INSTANCE;
    }

    // 单线程合成队列: sherpa OfflineTts 同一实例非并发安全, 且需确定顺序。
    private final ExecutorService ttsExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "speakerapi-tts");
        t.setDaemon(true);
        return t;
    });

    // 规格 A: volatile OfflineTts 单例。
    private volatile OfflineTts tts = null;
    private final AtomicBoolean ready = new AtomicBoolean(false);
    private final AtomicBoolean loading = new AtomicBoolean(false);

    // 引擎就绪监听: 注册时若已就绪立即触发一次, 否则在 load() 置 ready 后统一触发。
    private final java.util.List<Runnable> readyListeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    private Config config;
    private ModelManager models;
    private volatile int sampleRate = 24000; // 仅默认值; 实际由模型文件覆盖 (不写死 44.1k)。

    // 逻辑嗓 id -> Kokoro 多语 voices.bin 的 sid 索引。
    // 未确认: 各中英嗓在锁定版本 voices.bin 中的真实 sid, 以发布页 speaker 表为准; 默认 0。
    private static final Map<String, Integer> VOICE_SID = new HashMap<>();
    static {
        VOICE_SID.put("zh_female_natural", 0);
        VOICE_SID.put("zh_male_natural", 1);
    }

    private TtsEngine() {
    }

    public void init(Config config, ModelManager models) {
        this.config = config;
        this.models = models;
        if (!config.enabled()) {
            LOG.info("[SpeakerAPI] 配置禁用; 引擎不加载。");
            return;
        }
        if (!loading.compareAndSet(false, true)) {
            return;
        }
        // 离主线程加载: 若 >1.5s 游戏照常进; 就绪前请求由调用方策略入队或丢弃低优先级。
        ttsExecutor.submit(this::load);
    }

    /** synchronized 加载默认模型; 仅在 ttsExecutor 单线程调用。 */
    private synchronized void load() {
        try {
            ModelManager.ModelInfo info = models.defaultModel();
            models.logResolved(info);
            if (info == null || !info.present) {
                boolean ok = models.ensureModel(config.defaultModel());
                info = models.defaultModel();
                if (!ok || info == null || !info.present) {
                    LOG.error("[SpeakerAPI] 默认模型不可用; TTS 禁用, 直至提供模型。");
                    loading.set(false);
                    return;
                }
            }
            loadFromDir(info);
        } catch (Throwable t) {
            LOG.error("[SpeakerAPI] TTS 引擎加载失败: {}", t);
            t.printStackTrace();
            loading.set(false);
        }
    }

    /**
     * 用给定（已确保存在）的模型目录加载/重载引擎。synchronized、仅在 ttsExecutor 上调用。
     * 切换模型时先释放旧 native 实例，避免 native 资源泄漏。返回是否成功就绪。
     */
    private synchronized boolean loadFromDir(ModelManager.ModelInfo info) {
        OfflineTts old = tts;
        tts = null;
        ready.set(false);
        if (old != null) {
            try {
                old.release();
            } catch (Throwable ignored) {
                // best effort
            }
        }
        try {
            java.nio.file.Path dir = info.dir;

            // ---- Kokoro 配置 (规格 A builder) ----
            // 未确认: OfflineTtsKokoroModelConfig 类名/包名; setModel/setVoices/setTokens/setDataDir 字段名。
            OfflineTtsKokoroModelConfig.Builder kokoroB = OfflineTtsKokoroModelConfig.builder()
                    .setModel(dir.resolve("model.onnx").toString())
                    .setVoices(dir.resolve("voices.bin").toString())
                    .setTokens(dir.resolve("tokens.txt").toString());

            // 规格 §二: setLexicon / setDictDir / setDataDir 为可选前端数据。
            // 已核对真实 kokoro-multi-lang-v1_0.tar.bz2: 单一顶层目录 kokoro-multi-lang-v1_0/,
            //   内含 model.onnx / tokens.txt / voices.bin / lexicon-zh.txt / espeak-ng-data 等。
            // 策略: 先查磁盘存在性再反射挂接 —— 不存在就完全不调用对应 setter,
            //   既避免把空路径丢给 sherpa 导致 init 失败, 也不硬编字段名 (跨版本安全)。
            java.nio.file.Path espeak = dir.resolve("espeak-ng-data");
            if (java.nio.file.Files.isDirectory(espeak)) {
                reflectiveSet(kokoroB, "setDataDir", espeak.toString());
            } else {
                LOG.info("[SpeakerAPI] 未找到 espeak-ng-data, 跳过 setDataDir。");
            }

            java.nio.file.Path dict = dir.resolve("dict");
            if (java.nio.file.Files.isDirectory(dict)) {
                reflectiveSet(kokoroB, "setDictDir", dict.toString());
            }

            // lexicon 用逗号拼接, 只纳入真实存在的文件。
            String lexicon = String.join(",", java.util.stream.Stream
                    .of("lexicon-us-en.txt", "lexicon-zh.txt")
                    .map(dir::resolve)
                    .filter(java.nio.file.Files::exists)
                    .map(java.nio.file.Path::toString)
                    .toArray(String[]::new));
            if (!lexicon.isEmpty()) {
                reflectiveSet(kokoroB, "setLexicon", lexicon);
            }
            OfflineTtsKokoroModelConfig kokoro = kokoroB.build();

            // 未确认: OfflineTtsModelConfig 是否用 setKokoro(...) 挂接, 及 setNumThreads/setDebug 是否存在。
            OfflineTtsModelConfig model = OfflineTtsModelConfig.builder()
                    .setKokoro(kokoro)
                    .setNumThreads(config.numThreads())
                    .setDebug(false)
                    .build();

            // 未确认: OfflineTtsConfig 是否用 setModel(...)。
            OfflineTtsConfig ttsConfig = OfflineTtsConfig.builder()
                    .setModel(model)
                    .build();

            // 主路径 (规格 A): new OfflineTts(config)。
            // 二选一兼容写法: 若编译报 "无 OfflineTts(OfflineTtsConfig) 构造器",
            //   改用 OfflineTts.create(config) (部分 release 用静态工厂)。
            tts = new OfflineTts(ttsConfig);
            this.sampleRate = info.sampleRate;
            ready.set(true);
            loading.set(false);
            fireReadyListeners();
            LOG.info("[SpeakerAPI] 引擎就绪 | sampleRate={} threads={}",
                    sampleRate, config.numThreads());

            if (config.prewarm()) {
                prewarm();
            }
            return true;
        } catch (Throwable t) {
            LOG.error("[SpeakerAPI] TTS 引擎加载失败: {}", t);
            t.printStackTrace();
            loading.set(false);
            return false;
        }
    }

    /**
     * 运行时切换模型：确保模型存在（缺失则下载），然后重载引擎。
     * 仅在 ttsExecutor 单线程上执行；返回是否成功切换并加载。
     */
    public CompletableFuture<Boolean> loadModel(String modelId) {
        return CompletableFuture.supplyAsync(() -> {
            loading.set(true);
            try {
                ModelManager.ModelInfo info = models.resolve(modelId);
                if (info == null) {
                    LOG.error("[SpeakerAPI] 未知模型 id: {}", modelId);
                    loading.set(false);
                    return false;
                }
                if (!info.present) {
                    boolean ok = models.ensureModel(modelId);
                    info = models.resolve(modelId);
                    if (!ok || info == null || !info.present) {
                        LOG.error("[SpeakerAPI] 模型 '{}' 下载/准备失败, 切换未完成。", modelId);
                        loading.set(false);
                        return false;
                    }
                }
                return loadFromDir(info);
            } catch (Throwable t) {
                LOG.error("[SpeakerAPI] 模型切换失败 [{}]: {}", modelId, t.getMessage());
                loading.set(false);
                return false;
            }
        }, ttsExecutor);
    }

    /** 仅下载模型到本地（不切换、不重载引擎）。 */
    public CompletableFuture<Boolean> ensureModelAsync(String modelId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return models.ensureModel(modelId);
            } catch (Throwable t) {
                LOG.error("[SpeakerAPI] 模型下载失败 [{}]: {}", modelId, t.getMessage());
                return false;
            }
        }, ttsExecutor);
    }

    private void prewarm() {
        try {
            OfflineTts t = tts;
            if (t == null) {
                return;
            }
            // 1 个标点短句预热, 避免首句卡顿。首包目标数百 ms~1s (机器相关)。
            // 走同一反射 generate 入口 (规格 §二 双路径, 防编译死锁)。
            generate(t, "。", resolveSid(config.defaultVoice()), config.speed(), config.defaultVoice());
            LOG.info("[SpeakerAPI] 预热完成。");
        } catch (Throwable t) {
            LOG.error("[SpeakerAPI] 预热失败 (非致命): {}", t);
        }
    }

    private int resolveSid(String voiceId) {
        Integer s = VOICE_SID.get(voiceId);
        return s == null ? 0 : s; // 未确认 sid 索引 (见 VOICE_SID 注释)
    }

    public boolean isReady() {
        return ready.get();
    }

    public boolean isLoading() {
        return loading.get();
    }

    /** 注册引擎就绪监听: 若当前已就绪, 立即于调用线程触发一次; 否则在就绪瞬间触发。 */
    public void addReadyListener(Runnable listener) {
        if (listener == null) {
            return;
        }
        if (ready.get()) {
            try {
                listener.run();
            } catch (Throwable ignored) {
                // 单个监听异常不应影响其余
            }
            return;
        }
        readyListeners.add(listener);
    }

    /** 移除已注册的就绪监听。 */
    public void removeReadyListener(Runnable listener) {
        readyListeners.remove(listener);
    }

    private void fireReadyListeners() {
        for (Runnable r : readyListeners) {
            try {
                r.run();
            } catch (Throwable ignored) {
                // 单个监听异常不应影响其余
            }
        }
        readyListeners.clear();
    }

    /** 若未就绪且未在加载, 重新触发一次后台加载 (供命令手动重试 / 首次下载)。 */
    public void reload() {
        if (ready.get()) {
            return;
        }
        if (!loading.compareAndSet(false, true)) {
            return;
        }
        ttsExecutor.submit(this::load);
    }

    public int sampleRate() {
        return sampleRate;
    }

    /**
     * 异步合成文本为 PCM。永远在 {@link #ttsExecutor} 上执行。
     *
     * @throws IllegalStateException 经失败 future 抛出, 若引擎未就绪。
     */
    public CompletableFuture<AudioData> synthesize(String text) {
        float speed = config == null ? 1.0f : config.speed();
        String voice = config == null ? "zh_female_natural" : config.defaultVoice();
        return synthesize(text, speed, voice);
    }

    public CompletableFuture<AudioData> synthesize(String text, float speed, String voiceId) {
        if (!ready.get()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("TTS 引擎未就绪"));
        }
        final int sid = resolveSid(voiceId);
        return CompletableFuture.supplyAsync(() -> {
            OfflineTts t = tts;
            if (t == null) {
                throw new IllegalStateException("TTS 引擎已释放");
            }
            // 规格 §二 双路径: 反射探测 generate 重载 (主路径 String,int,float;
            // 回退 String,float / String / GenerationConfig+回调), 避免编译死锁。
            return generate(t, text, sid, speed, voiceId);
        }, ttsExecutor);
    }

    // ===== 反射兼容层 (规格 §二: 不硬编 sherpa 跨版本 API, 防编译死锁) =====

    private AudioData generate(OfflineTts t, String text, int sid, float speed, String voiceId) {
        // 1) 主路径: generate(String, int, float)
        Method m = findMethod(t.getClass(), "generate", String.class, int.class, float.class);
        if (m != null) {
            return toAudioData(invoke(m, t, text, sid, speed), voiceId);
        }
        // 2) 回退: generate(String, float) — 忽略 sid
        m = findMethod(t.getClass(), "generate", String.class, float.class);
        if (m != null) {
            return toAudioData(invoke(m, t, text, speed), voiceId);
        }
        // 3) 回退: generate(String)
        m = findMethod(t.getClass(), "generate", String.class);
        if (m != null) {
            return toAudioData(invoke(m, t, text), voiceId);
        }
        // 4) 回退: GenerationConfig + generateWithConfigAndCallback (反射)
        return generateViaConfig(t, text, sid, speed, voiceId);
    }

    private AudioData generateViaConfig(OfflineTts t, String text, int sid, float speed, String voiceId) {
        try {
            Class<?> genCls = Class.forName("com.k2fsa.sherpa.onnx.GenerationConfig");
            Object b = genCls.getMethod("builder").invoke(null);
            reflectiveSet(b, "setSid", sid);
            reflectiveSet(b, "setSpeed", speed);
            reflectiveSet(b, "setSilenceScale", 0.2f);
            Object gen = b.getClass().getMethod("build").invoke(b);
            Method m = t.getClass().getMethod("generateWithConfigAndCallback",
                    String.class, genCls, java.util.function.Consumer.class);
            final AudioData[] holder = { null };
            java.util.function.Consumer<Object> cb = (samples) -> {
                try {
                    holder[0] = toAudioData(samples, voiceId);
                } catch (Exception ignored) {
                    // 回调参数形态不符时忽略
                }
            };
            m.invoke(t, text, gen, cb);
            if (holder[0] == null) {
                throw new IllegalStateException("GenerationConfig 回退未产出音频");
            }
            return holder[0];
        } catch (Exception e) {
            throw new RuntimeException("所有 generate 重载均不可用 (未确认 sherpa API 形状)", e);
        }
    }

    private AudioData toAudioData(Object result, String voiceId) {
        try {
            Method gs = result.getClass().getMethod("getSamples");
            Method gr = result.getClass().getMethod("getSampleRate");
            float[] samples = (float[]) gs.invoke(result);
            int sr = ((Number) gr.invoke(result)).intValue();
            return new AudioData(samples, sr, voiceId);
        } catch (Exception e) {
            throw new RuntimeException("无法从 TTS 结果提取 PCM (未确认返回类结构)", e);
        }
    }

    private static Method findMethod(Class<?> c, String name, Class<?>... params) {
        try {
            return c.getMethod(name, params);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    private static Object invoke(Method m, Object target, Object... args) {
        try {
            return m.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw new RuntimeException(e.getCause());
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    /** 可选 setter 反射探测: 方法或参数类型不匹配则静默跳过 (规格 §二, 不硬编)。 */
    private static void reflectiveSet(Object target, String name, Object value) {
        Class<?> c = target.getClass();
        Class<?> v = value.getClass();
        for (Class<?> p : new Class<?>[]{ v, primitiveOf(v) }) {
            if (p == null) {
                continue;
            }
            try {
                c.getMethod(name, p).invoke(target, value);
                return;
            } catch (Exception ignored) {
                // 尝试下一候选参数类型 (包装类 / 基本类型)
            }
        }
    }

    private static Class<?> primitiveOf(Class<?> w) {
        if (w == Integer.class) return int.class;
        if (w == Float.class) return float.class;
        if (w == Double.class) return double.class;
        if (w == Boolean.class) return boolean.class;
        if (w == Long.class) return long.class;
        return null;
    }

    /** 释放 native 资源 (规格 A: tts.release())。 */
    public void close() {
        ready.set(false);
        OfflineTts t = tts;
        tts = null;
        if (t != null) {
            try {
                t.release();
            } catch (Throwable ignored) {
                // best effort
            }
        }
        ttsExecutor.shutdownNow();
        try {
            ttsExecutor.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    public void shutdown() {
        close();
    }
}
