package net.speakerapi.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Self-contained TOML reader for the Speaker API config file.
 *
 * <p>Only the small subset used by the mod is parsed: {@code [section]} headers,
 * {@code key = value} entries, and string / number / boolean / string-array
 * values. This keeps the mod dependency-free; swap for NeoForge's
 * {@code ForgeConfigSpec} if you prefer managed config UI later.</p>
 */
public final class Config {

    private final Map<String, Map<String, Object>> data = new HashMap<>();
    private Path file;

    private Config() {
    }

    public static Config load(Path file) {
        Config c = new Config();
        c.file = file;
        c.parse(file);
        return c;
    }

    private void parse(Path file) {
        if (!Files.exists(file)) {
            return;
        }
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            String section = "";
            for (String raw : lines) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                if (line.startsWith("[") && line.endsWith("]")) {
                    section = line.substring(1, line.length() - 1).trim();
                    data.computeIfAbsent(section, k -> new HashMap<>());
                    continue;
                }
                int eq = line.indexOf('=');
                if (eq < 0) {
                    continue;
                }
                String key = line.substring(0, eq).trim();
                String val = line.substring(eq + 1).trim();
                data.computeIfAbsent(section, k -> new HashMap<>()).put(key, parseValue(val));
            }
        } catch (IOException e) {
            // Any failure means "use defaults". The engine will still run.
            System.err.println("[SpeakerAPI] Failed to read config " + file + ", using defaults: " + e.getMessage());
        }
    }

    private Object parseValue(String v) {
        if (v.startsWith("[") && v.endsWith("]")) {
            String inner = v.substring(1, v.length() - 1).trim();
            List<String> list = new ArrayList<>();
            if (!inner.isEmpty()) {
                for (String part : inner.split(",")) {
                    String p = part.trim();
                    if (p.startsWith("\"") && p.endsWith("\"")) {
                        p = p.substring(1, p.length() - 1);
                    }
                    if (!p.isEmpty()) {
                        list.add(p);
                    }
                }
            }
            return list;
        }
        if (v.startsWith("\"") && v.endsWith("\"")) {
            return v.substring(1, v.length() - 1);
        }
        if ("true".equalsIgnoreCase(v) || "false".equalsIgnoreCase(v)) {
            return Boolean.parseBoolean(v);
        }
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException ignored) {
            // fall through
        }
        try {
            return Double.parseDouble(v);
        } catch (NumberFormatException ignored) {
            // fall through
        }
        return v;
    }

    private Object get(String section, String key) {
        Map<String, Object> s = data.get(section);
        return s == null ? null : s.get(key);
    }

    public boolean getBool(String section, String key, boolean def) {
        Object v = get(section, key);
        return v instanceof Boolean ? (Boolean) v : def;
    }

    public long getLong(String section, String key, long def) {
        Object v = get(section, key);
        if (v instanceof Long) {
            return (Long) v;
        }
        if (v instanceof Double) {
            return (long) (double) (Double) v;
        }
        return def;
    }

    public double getDouble(String section, String key, double def) {
        Object v = get(section, key);
        if (v instanceof Double) {
            return (Double) v;
        }
        if (v instanceof Long) {
            return (Long) v;
        }
        return def;
    }

    public String getString(String section, String key, String def) {
        Object v = get(section, key);
        return v instanceof String ? (String) v : def;
    }

    @SuppressWarnings("unchecked")
    public List<String> getStringList(String section, String key, List<String> def) {
        Object v = get(section, key);
        return v instanceof List ? (List<String>) v : def;
    }

    // ---- Typed domain accessors (mirror speakerapi-common.toml) ----

    public boolean enabled() {
        return getBool("general", "enabled", true);
    }

    /**
     * 是否接管系统「讲述人」(Windows Narrator / SAPI 语音): 游戏本体与其他模组调用系统 TTS 时,
     * 强制改用本模组的离线 Kokoro 语音。引擎未就绪或本模组被禁用时透明回退到原系统语音。
     * 默认开启 (true)。
     */
    public boolean replaceSystemTts() {
        return getBool("general", "replace_system_tts", true);
    }

    public String defaultVoice() {
        return getString("general", "default_voice", "zh_female_natural");
    }

    public float speed() {
        return (float) getDouble("general", "speed", 1.0);
    }

    public float volume() {
        return (float) getDouble("general", "volume", 1.0);
    }

    public int maxTextLength() {
        return (int) getLong("general", "max_text_length", 200);
    }

    public boolean readChat() {
        return getBool("general", "read_chat", false);
    }

    public boolean readSystemToast() {
        return getBool("general", "read_system_toast", true);
    }

    public boolean readAchievements() {
        return getBool("general", "read_achievements", false);
    }

    /** 低血量语音提醒 (由 ClientTickEvent 节流轮询, 规格 B)。 */
    public boolean readLowHealth() {
        return getBool("general", "read_low_health", false);
    }

    public List<String> filterRegex() {
        return getStringList("general", "filter_regex",
                Arrays.asList("^/", "坐标\\d+", "token=.+"));
    }

    public int numThreads() {
        return (int) getLong("performance", "num_threads", 4);
    }

    public boolean prewarm() {
        return getBool("performance", "prewarm", true);
    }

    public int cacheSize() {
        return (int) getLong("performance", "cache_size", 200);
    }

    public int resampleToHz() {
        return (int) getLong("performance", "resample_to_hz", 44100);
    }

    public String defaultModel() {
        return getString("models", "default", "kokoro-multilang");
    }

    // ---- Audio routing (规格: 绑定到 MC 声音分类 + 不排队) ----

    /**
     * TTS 输出绑定的 MC 声音分类名 (master/music/records/weather/block/
     * hostile/neutral/player/ambient/voice)。语音音量会再乘以该分类的 MC 滑块。
     */
    public String audioCategory() {
        return getString("audio", "category", "voice");
    }

    /** 是否把 MC 所选分类的滑块音量也乘到语音上 (关掉则只用本 mod 的 volume)。 */
    public boolean applyCategoryVolume() {
        return getBool("audio", "apply_category_volume", true);
    }

    public boolean allowDownloadMissing() {
        return getBool("models", "allow_download_missing", true);
    }

    public List<String> extraModelDirs() {
        return getStringList("models", "extra_model_dirs",
                Collections.singletonList("config/speakerapi/models"));
    }

    public boolean requireModelLicenseConfirm() {
        return getBool("models", "require_model_license_confirm", true);
    }

    /**
     * 模型下载镜像策略。
     *
     * <ul>
     *   <li>{@code "auto"}（默认）：优先走 GitHub 加速镜像，失败自动回退官方直链。
     *       实测官方直链在国内仅约 0.1&nbsp;MB/s（333MB 要 ~55 分钟），
     *       而加速镜像可达 2.5&nbsp;MB/s（~2 分钟）。</li>
     *   <li>{@code "none"} 或空串：只用官方 GitHub Releases 直链。</li>
     *   <li>自定义前缀：如 {@code "https://ghfast.top/"}，会拼在原 URL 前面。</li>
     *   <li>含 {@code {url}} 占位符：用 {@code {url}} 表示原始地址，可拼成任意代理形态。</li>
     * </ul>
     */
    public String modelMirror() {
        return getString("models", "mirror", "auto").trim();
    }

    /**
     * 设置并持久化默认模型 id：写入配置文件的 {@code [models] default} 段，重启后仍然生效。
     * 写入失败（无文件权限等）时仅更新内存值，不抛异常。
     */
    public void setDefaultModel(String id) {
        data.computeIfAbsent("models", k -> new HashMap<>()).put("default", id);
        saveDefault(id);
    }

    private void saveDefault(String id) {
        if (file == null || !Files.exists(file)) {
            return;
        }
        try {
            List<String> lines = new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8));
            boolean inModels = false;
            boolean updated = false;
            for (int i = 0; i < lines.size(); i++) {
                String trimmed = lines.get(i).trim();
                if (trimmed.startsWith("[")) {
                    inModels = trimmed.equals("[models]");
                    continue;
                }
                if (inModels && trimmed.startsWith("default") && trimmed.contains("=")) {
                    lines.set(i, "default = \"" + id + "\"");
                    updated = true;
                    break;
                }
            }
            if (!updated) {
                int idx = -1;
                for (int i = 0; i < lines.size(); i++) {
                    if (lines.get(i).trim().equals("[models]")) {
                        idx = i;
                        break;
                    }
                }
                if (idx >= 0) {
                    lines.add(idx + 1, "default = \"" + id + "\"");
                } else {
                    lines.add("");
                    lines.add("[models]");
                    lines.add("default = \"" + id + "\"");
                }
            }
            Files.write(file, lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("[SpeakerAPI] 写入默认模型到配置失败: " + e.getMessage());
        }
    }
}
