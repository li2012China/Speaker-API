package net.speakerapi.core;

/**
 * Identifies a voice: which model produces it, its native sample rate, and a
 * human-readable label. Other mods pass a {@code VoiceProfile} to
 * {@link SpeakerAPI#say}.
 */
public final class VoiceProfile {

    // Built-in presets mapped onto the shipped Kokoro multi-lang package.
    public static final VoiceProfile ZH_FEMALE_NATURAL =
            new VoiceProfile("zh_female_natural", "中文·自然女声", 24000, "kokoro-multilang", true);
    public static final VoiceProfile ZH_MALE_NATURAL =
            new VoiceProfile("zh_male_natural", "中文·自然男声", 24000, "kokoro-multilang", false);

    private final String id;
    private final String displayName;
    private final int sampleRate;
    private final String modelId;
    private final boolean isDefault;

    public VoiceProfile(String id, String displayName, int sampleRate, String modelId, boolean isDefault) {
        this.id = id;
        this.displayName = displayName;
        this.sampleRate = sampleRate;
        this.modelId = modelId;
        this.isDefault = isDefault;
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    /** Native sample rate of the underlying model (e.g. 24000 for Kokoro). */
    public int sampleRate() {
        return sampleRate;
    }

    /** Model registry id this voice belongs to. */
    public String modelId() {
        return modelId;
    }

    public boolean isDefault() {
        return isDefault;
    }

    @Override
    public String toString() {
        return displayName + " (" + id + ")";
    }
}
