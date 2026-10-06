package net.speakerapi.core;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.time.Duration;

// 自包含的 bzip2 + tar 解压实现 (无第三方依赖), 避免与 ModrinthApp 启动器自带的
// commons-compress / commons-io 发生 JPMS 分包冲突或缺失依赖 (NoClassDefFoundError)。
import net.speakerapi.internal.bzip2.BZip2InputStream;
import net.speakerapi.internal.tar.TarExtractor;

// 统一走平台注入的日志实现 (Forge/NeoForge 均为 Log4j 2), 保证进度日志稳定进入
// logs/latest.log (System.out 在部分启动器下不可见/无序)。

/**
 * Resolves voice-model directories, reports license / sample rate, and (optionally)
 * fetches missing models from a trusted release URL.
 *
 * <p><b>Packaging rule:</b> model weights and native libs are NEVER bundled in the
 * main jar. Only Kokoro multi-lang + its espeak-ng data ship by default; every
 * other model is an optional download that must pass a license-confirm gate
 * (see {@link Config#requireModelLicenseConfirm()}) before it is loaded.</p>
 */
public final class ModelManager {

    private static final CoreLogger LOG = Core.log();

    /** Resolved view of a model on disk. */
    public static final class ModelInfo {
        public final String id;
        public final Path dir;
        public final String license;
        public final int sampleRate;
        public final boolean present;
        /** 必需文件中本地缺失的 (空列表表示齐全)。 */
        public final List<String> missingFiles;

        ModelInfo(String id, Path dir, String license, int sampleRate, boolean present, List<String> missingFiles) {
            this.id = id;
            this.dir = dir;
            this.license = license;
            this.sampleRate = sampleRate;
            this.present = present;
            this.missingFiles = missingFiles;
        }
    }

    private static final class RegistryEntry {
        final String id;
        final int sampleRate;
        final String license;
        final String downloadUrl;
        final List<String> requiredFiles;

        RegistryEntry(String id, int sampleRate, String license, String downloadUrl, List<String> requiredFiles) {
            this.id = id;
            this.sampleRate = sampleRate;
            this.license = license;
            this.downloadUrl = downloadUrl;
            this.requiredFiles = requiredFiles;
        }
    }

    // Only Kokoro is shipped. Others are optional and must be downloaded + licensed.
    private static final Map<String, RegistryEntry> REGISTRY = new LinkedHashMap<>();

    static {
        REGISTRY.put("kokoro-multilang", new RegistryEntry(
                "kokoro-multilang", 24000, "Apache-2.0",
                "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-multi-lang-v1_0.tar.bz2",
                // 只把「权重 + 词表 + 音色」列为硬依赖。
                // espeak-ng-data / lexicon / dict 为可选前端数据: 不同 Kokoro 构建版可能不含,
                //   由 TtsEngine 按需探测存在性后再挂接, 不列入硬依赖避免 present 永远 false。
                List.of("model.onnx", "tokens.txt", "voices.bin")));

        // ---- Optional, NOT bundled. Each needs its own license confirmation. ----
        REGISTRY.put("matcha-icefall-zh", new RegistryEntry(
                "matcha-icefall-zh", 16000, "see release page (MIT/Apache per model)",
                "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/...",
                List.of("model.onnx", "tokens.txt", "espeak-ng-data")));
        REGISTRY.put("vits-aishell3-zh", new RegistryEntry(
                "vits-aishell3-zh", 22050, "see release page (verify AIShell3 license)",
                "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/...",
                List.of("model.onnx", "tokens.txt", "espeak-ng-data")));
        REGISTRY.put("piper-huayan", new RegistryEntry(
                "piper-huayan", 22050, "see release page (Piper/CC BY 4.0 per voice)",
                "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/...",
                List.of("model.onnx", "tokens.txt", "espeak-ng-data", "model.onnx.json")));
    }

    private final Path baseDir;   // <gameDir>/config/speakerapi
    private final Config config;

    public ModelManager(Path gameDir, Config config) {
        this.baseDir = gameDir.resolve("config/speakerapi");
        this.config = config;
    }

    public Path modelsDir() {
        return baseDir.resolve("models");
    }

    public Path cacheDir() {
        return baseDir.resolve("cache");
    }

    /** Resolve a model id to its on-disk info (present flag reflects file checks). */
    public ModelInfo resolve(String modelId) {
        RegistryEntry e = REGISTRY.get(modelId);
        if (e == null) {
            return null;
        }
        Path dir = modelsDir().resolve(modelId);
        List<String> missing = new ArrayList<>();
        boolean present = Files.isDirectory(dir);
        if (present) {
            for (String f : e.requiredFiles) {
                if (!exists(dir, f)) {
                    missing.add(f);
                }
            }
            present = missing.isEmpty();
        } else {
            missing.addAll(e.requiredFiles);
        }
        return new ModelInfo(modelId, dir, e.license, e.sampleRate, present, missing);
    }

    private static boolean exists(Path dir, String f) {
        Path p = dir.resolve(f);
        return Files.exists(p) || Files.isDirectory(p);
    }

    public ModelInfo defaultModel() {
        return resolve(config.defaultModel());
    }

    public List<String> knownModelIds() {
        return new ArrayList<>(REGISTRY.keySet());
    }

    /**
     * Make sure a model is available. If missing and auto-download is allowed,
     * stream the trusted release archive (tar.bz2), verify the required files,
     * and leave a copy of the model's LICENSE next to the weights.
     *
     * <p>This method is ONLY ever called from {@link TtsEngine}'s single-threaded
     * executor (never the client tick/render path), so the blocking download does
     * not stall the game. The first run pulls ~334&nbsp;MB once.</p>
     *
     * @return true if the model is ready to load now.
     */
    public boolean ensureModel(String modelId) throws IOException {
        ModelInfo info = resolve(modelId);
        if (info == null) {
            LOG.error("[SpeakerAPI] Unknown model id: {}", modelId);
            return false;
        }
        if (info.present) {
            return true;
        }
        if (!config.allowDownloadMissing()) {
            LOG.error("[SpeakerAPI] Model '{}' missing and auto-download disabled. Place it at {}",
                    modelId, info.dir);
            return false;
        }
        RegistryEntry e = REGISTRY.get(modelId);
        try {
            Files.createDirectories(modelsDir());
            Files.createDirectories(cacheDir());
            Path archive = cacheDir().resolve(modelId + ".tar.bz2");

            // 缓存命中: 归档已存在且非零散残文件 -> 跳过下载, 直接解压复用。
            boolean reusedCache = Files.exists(archive) && Files.size(archive) > 1024L * 1024L;
            if (reusedCache) {
                LOG.info("[SpeakerAPI] 命中本地缓存归档 ({} MB), 跳过下载: {}",
                        String.format("%.1f", Files.size(archive) / (1024.0 * 1024.0)),
                        archive.getFileName());
            } else {
                LOG.info("[SpeakerAPI] 模型 '{}' [{}] 本地缺失, 开始下载 (一次性 ~334MB): {}",
                        modelId, e.license, e.downloadUrl);
                downloadWithFallback(e.downloadUrl, archive);
            }

            // 解压 (缓存损坏时删掉重下一次)。
            try {
                LOG.info("[SpeakerAPI] 开始解压到 {}", info.dir);
                extractTarBz2(archive, info.dir);
            } catch (IOException extractEx) {
                if (reusedCache) {
                    LOG.error("[SpeakerAPI] 缓存归档解压失败, 删除并重新下载: {}", extractEx.getMessage());
                    Files.deleteIfExists(archive);
                    downloadWithFallback(e.downloadUrl, archive);
                    LOG.info("[SpeakerAPI] 开始解压到 {}", info.dir);
                    extractTarBz2(archive, info.dir);
                } else {
                    throw extractEx;
                }
            }

            // 重新解析: 验证必需文件存在。
            info = resolve(modelId);
            if (info != null && info.present) {
                LOG.info("[SpeakerAPI] 模型 '{}' 就绪于 {}", modelId, info.dir);
                return true;
            }
            LOG.error("[SpeakerAPI] 解压后缺少必需文件; 模型不可用: {}", info.dir);
            return false;
        } catch (IOException | InterruptedException ex) {
            LOG.error("[SpeakerAPI] 模型下载/解压失败 [{}]: {}", modelId, ex.getMessage());
            ex.printStackTrace();
            return false;
        }
    }

    /** 实测可用的 GitHub Release 加速镜像（按此顺序尝试；国内官方直链常只有 0.1 MB/s）。 */
    private static final List<String> ACCEL_MIRRORS = List.of(
            "https://ghfast.top/",
            "https://gh-proxy.com/");

    /**
     * 按 {@link Config#modelMirror()} 展开候选下载地址，官方直链永远作为兜底。
     *
     * <ul>
     *   <li>{@code auto}（默认）→ 各加速镜像 + 官方直链</li>
     *   <li>{@code none} / 空串 → 仅官方直链</li>
     *   <li>含 {@code {url}} → 占位符替换后的地址 + 官方直链</li>
     *   <li>其它 → 作为前缀拼接后的地址 + 官方直链</li>
     * </ul>
     */
    private List<URI> resolveDownloadUris(String rawUrl) {
        String m = config.modelMirror();
        List<String> urls = new ArrayList<>();
        if (m == null || m.isEmpty() || "none".equalsIgnoreCase(m)) {
            urls.add(rawUrl);
        } else if ("auto".equalsIgnoreCase(m)) {
            for (String prefix : ACCEL_MIRRORS) {
                urls.add(prefix + rawUrl);
            }
            urls.add(rawUrl);
        } else if (m.contains("{url}")) {
            urls.add(m.replace("{url}", rawUrl));
            urls.add(rawUrl);
        } else {
            urls.add(m + rawUrl);
            urls.add(rawUrl);
        }
        List<URI> out = new ArrayList<>();
        for (String u : urls) {
            try {
                out.add(URI.create(u));
            } catch (IllegalArgumentException ignored) {
                LOG.warn("[SpeakerAPI] 忽略非法的模型地址: {}", u);
            }
        }
        return out;
    }

    /** 依次尝试候选地址（含加速镜像），全部失败才抛出最后一个异常。 */
    private void downloadWithFallback(String rawUrl, Path target) throws IOException, InterruptedException {
        List<URI> candidates = resolveDownloadUris(rawUrl);
        IOException last = null;
        for (int i = 0; i < candidates.size(); i++) {
            URI uri = candidates.get(i);
            try {
                if (candidates.size() > 1) {
                    LOG.info("[SpeakerAPI] 尝试下载地址 {}/{}: {}", i + 1, candidates.size(), uri);
                }
                downloadWithProgress(uri, target);
                return;
            } catch (IOException ex) {
                last = ex;
                LOG.warn("[SpeakerAPI] 该地址下载失败, 换下一个: {}", ex.getMessage());
                // 清掉半截文件, 避免下一个地址复用残片。
                Files.deleteIfExists(target.resolveSibling(target.getFileName() + ".part"));
            }
        }
        if (last != null) {
            throw last;
        }
        throw new IOException("没有可用的模型下载地址: " + rawUrl);
    }

    /** Stream a URL to a file with fine-grained progress logging (~每 5MB 或 30 秒). */
    private void downloadWithProgress(URI uri, Path target) throws IOException, InterruptedException {
        Path part = target.resolveSibling(target.getFileName() + ".part");
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        HttpRequest req = HttpRequest.newBuilder().uri(uri).GET()
                .header("User-Agent", "SpeakerAPI/1.0.0")
                .build();
        LOG.info("[SpeakerAPI] 开始下载模型归档: {}", uri);
        HttpResponse<InputStream> resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IOException("下载返回 HTTP " + resp.statusCode() + " for " + uri);
        }
        long total = resp.headers().firstValueAsLong("content-length").orElse(-1L);
        if (total > 0) {
            LOG.info("[SpeakerAPI] 归档大小: {} MB", String.format("%.1f", total / (1024.0 * 1024.0)));
        } else {
            LOG.info("[SpeakerAPI] 归档大小: 未知 (服务器未返回 Content-Length), 仅显示已下载量");
        }
        final AtomicLong done = new AtomicLong(0L);
        long lastLog = 0L;
        long startWall = System.currentTimeMillis();
        long lastLogWall = startWall;
        final long stepBytes = 5L * 1024 * 1024; // 每 5MB 一条进度
        final long stepMillis = 30_000L;         // 速度慢时至少每 30 秒一条, 避免看起来"卡死"
        final boolean[] slowHinted = {false};
        try (InputStream in = new BufferedInputStream(resp.body());
             OutputStream out = Files.newOutputStream(part)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                long d = done.addAndGet(n);
                boolean finished = (total > 0 && d >= total);
                long nowWall = System.currentTimeMillis();
                if (finished || d - lastLog >= stepBytes || nowWall - lastLogWall >= stepMillis) {
                    lastLog = d;
                    lastLogWall = nowWall;
                    long elapsedSec = (System.currentTimeMillis() - startWall) / 1000L;
                    double mb = d / (1024.0 * 1024.0);
                    double speed = elapsedSec > 0 ? mb / elapsedSec : 0.0;
                    if (total > 0) {
                        LOG.info("[SpeakerAPI] 下载进度 {}%  ({}/{} MB, {} MB/s)",
                                String.format("%.1f", d * 100.0 / total),
                                String.format("%.1f", mb),
                                String.format("%.1f", total / (1024.0 * 1024.0)),
                                String.format("%.1f", speed));
                    } else {
                        LOG.info("[SpeakerAPI] 已下载 {} MB ({} MB/s)",
                                String.format("%.1f", mb), String.format("%.1f", speed));
                    }
                    // 慢速时给一次可操作的提示（只提示一次, 避免刷屏）。
                    if (!slowHinted[0] && !finished && elapsedSec >= 20L && speed < 0.3) {
                        slowHinted[0] = true;
                        LOG.warn("[SpeakerAPI] 下载速度偏慢 ({} MB/s)。可在 speakerapi-common.toml 的 [models] 段"
                                        + " 设置 mirror=\"auto\" 走加速镜像; 或手动把已下好的模型目录复制到 {}",
                                String.format("%.2f", speed), modelsDir());
                    }
                }
            }
        }
        Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
        long secs = (System.currentTimeMillis() - startWall) / 1000L;
        LOG.info("[SpeakerAPI] 下载完成: {}  用时 {}s", target.getFileName(), secs);
    }

    /** Decompress a .tar.bz2 into {@code dest}, stripping a single top-level dir. */
    private void extractTarBz2(Path archive, Path dest) throws IOException {
        Files.createDirectories(dest);
        try (InputStream fi = Files.newInputStream(archive);
             InputStream bi = new BufferedInputStream(fi);
             BZip2InputStream bz = new BZip2InputStream(bi)) {
            TarExtractor.extract(bz, dest);
        }
    }

    /** Print the resolved model line to the game log (required by the spec). */
    public void logResolved(ModelInfo info) {
        if (info == null) {
            LOG.info("[SpeakerAPI] Model: <unresolved>");
            return;
        }
        LOG.info("[SpeakerAPI] Model: {} | license: {} | sampleRate: {} | present: {} | dir: {}",
                info.id, info.license, info.sampleRate, info.present, info.dir);
    }
}
