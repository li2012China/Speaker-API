package net.speakerapi.core;

/**
 * 与 MC 声音分类同名的分类枚举。common 层不能直接引用
 * {@code net.minecraft.sounds.SoundSource}，故自带一份，由版本适配层做 1:1 映射。
 */
public enum SoundCategory {

    MASTER("master"),
    MUSIC("music"),
    RECORDS("records"),
    WEATHER("weather"),
    BLOCK("block"),
    HOSTILE("hostile"),
    NEUTRAL("neutral"),
    PLAYER("player"),
    AMBIENT("ambient"),
    VOICE("voice");

    private final String id;

    SoundCategory(String id) {
        this.id = id;
    }

    /** 配置/external API 使用的稳定小写名。 */
    public String id() {
        return id;
    }

    /**
     * 宽松解析：忽略大小写与下划线，并把 Forge/Neo 老别名 {@code record} 归一到 {@link #RECORDS}。
     * 解析失败返回 {@code null}（调用方决定是回退默认还是报错）。
     */
    public static SoundCategory fromId(String raw) {
        if (raw == null) {
            return null;
        }
        String n = raw.trim().toLowerCase().replace('_', '-').replace(' ', '-');
        if (n.equals("record")) {
            return RECORDS;
        }
        for (SoundCategory c : values()) {
            if (c.id.equals(n) || c.name().equalsIgnoreCase(n)) {
                return c;
            }
        }
        return null;
    }
}
