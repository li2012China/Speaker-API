package net.speakerapi;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import net.speakerapi.core.Pos3;
import net.speakerapi.core.SoundCategory;
import net.speakerapi.core.SpeakerCore;

/**
 * 版本侧 API shim（三个 jar 各有一份，对外包名为 {@code net.speakerapi}）。
 *
 * <p>它做的唯一一件事：把 MC 类型
 * （{@link Component} / {@link Vec3} / {@link BlockPos} / {@link Entity} / {@link SoundSource}）
 * 翻译成 core 侧类型（{@code String} / {@link Pos3} / {@link SoundCategory}），
 * 然后转发给 {@link SpeakerCore}。所有引擎逻辑都在 common，不含任何版本代码。</p>
 *
 * <p>其它模组按老用法调用即可（跨版本签名一致）：
 * <pre>
 *   SpeakerAPI.say(Component.literal("你好"), "mymod", VoiceProfile.ZH_FEMALE_NATURAL, SpeakerAPI.Priority.NORMAL);
 *   SpeakerAPI.sayAt("小心脚下", "mymod", null, SpeakerAPI.Priority.HIGH, blockPos);
 * </pre>
 */
public final class SpeakerAPI {

    private SpeakerAPI() {
    }

    public enum Priority {
        LOW,
        NORMAL,
        HIGH,
        ANNOUNCE;

        static SpeakerCore.Priority core(Priority p) {
            return p == null ? SpeakerCore.Priority.NORMAL : SpeakerCore.Priority.valueOf(p.name());
        }
    }

    // ===== 嗓音 / 状态 =====

    public static net.speakerapi.core.VoiceProfile voiceFor(String id) {
        return SpeakerCore.voiceFor(id);
    }

    public static List<net.speakerapi.core.VoiceProfile> availableVoices() {
        return SpeakerCore.availableVoices();
    }

    public static List<String> availableVoiceIds() {
        return SpeakerCore.availableVoiceIds();
    }

    public static boolean isEnabled() {
        return SpeakerCore.isEnabled();
    }

    public static void setEnabled(boolean on) {
        SpeakerCore.setEnabled(on);
    }

    public static boolean isEngineReady() {
        return SpeakerCore.isEngineReady();
    }

    public static void addEngineReadyListener(Runnable listener) {
        SpeakerCore.addEngineReadyListener(listener);
    }

    public static void removeEngineReadyListener(Runnable listener) {
        SpeakerCore.removeEngineReadyListener(listener);
    }

    public static void stopAll() {
        SpeakerCore.stopAll();
    }

    public static int activeCount() {
        return SpeakerCore.activeCount();
    }

    // ===== 分类 =====

    /** 当前绑定分类（MC 枚举）。 */
    public static SoundSource getCategory() {
        return McPlatform.toSource(SpeakerCore.getCategory());
    }

    public static void setCategory(SoundSource src) {
        SpeakerCore.setCategory(McPlatform.toCategory(src));
    }

    /** 按名字设置；非法名返回 false（支持 master/music/records/weather/block/…）。 */
    public static boolean setCategory(String name) {
        return SpeakerCore.setCategory(name);
    }

    public static double categoryVolume(SoundSource src) {
        return SpeakerCore.categoryVolume(McPlatform.toCategory(src));
    }

    // ===== 系统讲述人接管 =====

    public static boolean isReplaceSystemTts() {
        return SpeakerCore.isReplaceSystemTts();
    }

    public static void setReplaceSystemTts(boolean on) {
        SpeakerCore.setReplaceSystemTts(on);
    }

    public static SpeakHandle narratorSay(String text) {
        return new SpeakHandle(SpeakerCore.narratorSay(text));
    }

    public static SpeakHandle sayNarrator(String text) {
        return new SpeakHandle(SpeakerCore.sayNarrator(text));
    }

    // ===== SayOptions（MC 类型版） =====

    /** 一次朗读的参数集（版本侧形态：接受 MC 类型）。 */
    public static final class SayOptions {

        public final String text;
        public final String sourceId;
        public final net.speakerapi.core.VoiceProfile voice;
        public final Priority priority;
        public final SoundSource category;
        public final Float volumeOverride;
        public final Vec3 position;
        public final net.speakerapi.core.PositionProvider follow;
        public final Consumer<String> onSentenceStart;
        public final Consumer<String> onSentenceDone;
        public final Runnable onComplete;

        private SayOptions(Builder b) {
            this.text = b.text;
            this.sourceId = b.sourceId;
            this.voice = b.voice;
            this.priority = b.priority;
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

        SpeakerCore.SayOptions toCore() {
            SpeakerCore.SayOptions.Builder cb = SpeakerCore.SayOptions.builder()
                    .text(text)
                    .sourceId(sourceId)
                    .voice(voice)
                    .priority(Priority.core(priority))
                    .category(McPlatform.toCategory(category))
                    .volume(volumeOverride)
                    .onSentenceStart(onSentenceStart)
                    .onSentenceDone(onSentenceDone)
                    .onComplete(onComplete);
            if (follow != null) {
                cb.at(follow);
            } else {
                cb.at(toPos3(position));
            }
            return cb.build();
        }

        public static final class Builder {

            private String text;
            private String sourceId;
            private net.speakerapi.core.VoiceProfile voice;
            private Priority priority;
            private SoundSource category;
            private Float volumeOverride;
            private Vec3 position;
            private net.speakerapi.core.PositionProvider follow;
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

            public Builder voice(net.speakerapi.core.VoiceProfile v) {
                this.voice = v;
                return this;
            }

            public Builder priority(Priority p) {
                this.priority = p;
                return this;
            }

            public Builder category(SoundSource c) {
                this.category = c;
                return this;
            }

            public Builder category(String c) {
                SoundCategory core = SoundCategory.fromId(c);
                this.category = core == null ? null : McPlatform.toSource(core);
                return this;
            }

            public Builder volume(Float v) {
                this.volumeOverride = v;
                return this;
            }

            public Builder at(Vec3 pos) {
                this.position = pos;
                return this;
            }

            public Builder at(BlockPos pos) {
                this.position = pos == null ? null : center(pos);
                return this;
            }

            public Builder at(Entity e) {
                this.position = e == null ? null : e.position();
                return this;
            }

            /**
             * 绑定到实体：朗读期间语音<b>持续跟随</b>实体移动（每段实时取坐标，从眼部高度发声）。
             * 实体被移除时退化为居中播放，整句话仍继续。
             */
            public Builder bindTo(Entity e) {
                this.follow = e == null ? null : () -> {
                    net.minecraft.world.phys.Vec3 p = e.getEyePosition();
                    return new Pos3(p.x, p.y, p.z);
                };
                return this;
            }

            /**
             * 绑定到方块：语音从方块中心持续传来（方块静止，等价于固定坐标，但用统一接口）。
             */
            public Builder bindTo(BlockPos pos) {
                this.follow = pos == null ? null : () -> new Pos3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
                return this;
            }

            /** 绑定到任意坐标源（供外部模组自定义跟随逻辑）。 */
            public Builder bindTo(net.speakerapi.core.PositionProvider provider) {
                this.follow = provider;
                return this;
            }

            /** 复制已有 SayOptions 的全部字段（在其基础上叠加绑定）。 */
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

    /** 句柄包装（version 形态）。 */
    public static final class SpeakHandle {

        private final SpeakerCore.SpeakHandle delegate;

        SpeakHandle(SpeakerCore.SpeakHandle delegate) {
            this.delegate = delegate;
        }

        public boolean cancel() {
            return delegate.cancel();
        }

        public boolean isDone() {
            return delegate.isDone();
        }

        public boolean isActive() {
            return delegate.isActive();
        }

        public CompletableFuture<Void> future() {
            return delegate.future();
        }
    }

    // ===== 入口 =====

    /** 规范入口：版本侧 SayOptions -> core SayOptions。 */
    public static SpeakHandle say(SayOptions o) {
        return new SpeakHandle(SpeakerCore.say(o.toCore()));
    }

    /** Speak a chat {@link Component} from a registered source. */
    public static CompletableFuture<Void> say(Component text, String sourceId,
                                             net.speakerapi.core.VoiceProfile voice, Priority priority) {
        return SpeakerCore.say(text == null ? "" : text.getString(), sourceId, voice, Priority.core(priority));
    }

    /** 同上，显式指定本次语音绑定的 MC 声音分类。 */
    public static CompletableFuture<Void> say(Component text, String sourceId,
                                             net.speakerapi.core.VoiceProfile voice,
                                             Priority priority, SoundSource cat) {
        return say(SayOptions.builder()
                .text(text == null ? "" : text.getString())
                .sourceId(sourceId)
                .voice(voice)
                .priority(priority)
                .category(cat)
                .build()).future();
    }

    public static CompletableFuture<Void> say(String raw, String sourceId,
                                             net.speakerapi.core.VoiceProfile voice, Priority priority) {
        return SpeakerCore.say(raw, sourceId, voice, Priority.core(priority));
    }

    public static CompletableFuture<Void> say(String raw, String sourceId,
                                             net.speakerapi.core.VoiceProfile voice,
                                             Priority priority, SoundSource cat) {
        return say(SayOptions.builder()
                .text(raw)
                .sourceId(sourceId)
                .voice(voice)
                .priority(priority)
                .category(cat)
                .build()).future();
    }

    /** 在指定世界坐标空间化朗读。 */
    public static SpeakHandle say(String raw, String sourceId, net.speakerapi.core.VoiceProfile voice,
                                  Priority priority, SoundSource cat, Vec3 position) {
        return say(SayOptions.builder()
                .text(raw)
                .sourceId(sourceId)
                .voice(voice)
                .priority(priority)
                .category(cat)
                .at(position)
                .build());
    }

    /** 绑定到方块中心方向朗读。 */
    public static SpeakHandle sayAt(String raw, String sourceId, net.speakerapi.core.VoiceProfile voice,
                                    Priority priority, BlockPos pos) {
        return say(SayOptions.builder()
                .text(raw)
                .sourceId(sourceId)
                .voice(voice)
                .priority(priority)
                .at(pos)
                .build());
    }

    /** 绑定到实体位置方向朗读（位置在调用时快照）。 */
    public static SpeakHandle sayAt(String raw, String sourceId, net.speakerapi.core.VoiceProfile voice,
                                    Priority priority, Entity entity) {
        return say(SayOptions.builder()
                .text(raw)
                .sourceId(sourceId)
                .voice(voice)
                .priority(priority)
                .at(entity)
                .build());
    }

    // ===== 绑定（跟随）便捷入口 =====

    /** 把语音绑定到实体：朗读期间语音持续跟随实体移动（从眼部高度发声）。 */
    public static SpeakHandle sayForEntity(Entity entity, String text) {
        return say(SayOptions.builder().text(text).bindTo(entity).build());
    }

    /** 同上，但在已有参数（voice / priority / category / 回调）基础上叠加实体绑定。 */
    public static SpeakHandle sayForEntity(Entity entity, SayOptions base) {
        return say(SayOptions.builder().from(base).bindTo(entity).build());
    }

    /** 把语音绑定到方块：语音从方块中心持续传来（方块静止，等价于固定方向）。 */
    public static SpeakHandle sayForBlock(BlockPos pos, String text) {
        return say(SayOptions.builder().text(text).bindTo(pos).build());
    }

    /** 同上，但在已有参数基础上叠加方块绑定。 */
    public static SpeakHandle sayForBlock(BlockPos pos, SayOptions base) {
        return say(SayOptions.builder().from(base).bindTo(pos).build());
    }

    // ===== 配置透传 / 源注册 =====

    public static boolean readChat() {
        return SpeakerCore.readChat();
    }

    public static boolean readSystemToast() {
        return SpeakerCore.readSystemToast();
    }

    public static boolean readAchievements() {
        return SpeakerCore.readAchievements();
    }

    public static boolean readLowHealth() {
        return SpeakerCore.readLowHealth();
    }

    /** Register a named source with an optional text filter. */
    public static void registerSource(String id, java.util.function.Predicate<String> filter) {
        SpeakerCore.registerSource(id, filter);
    }

    /** 本地模型状态快照（只读，供外部/命令展示）。 */
    public static List<net.speakerapi.core.ModelManager.ModelInfo> listModels() {
        return SpeakerCore.listModels();
    }

    /** 所有已知模型 id。 */
    public static List<String> knownModelIds() {
        return SpeakerCore.knownModelIds();
    }

    /** 是否为已知（受支持）模型。 */
    public static boolean isKnownModel(String id) {
        return SpeakerCore.isKnownModel(id);
    }

    /** 选择并加载模型（设为默认 + 下载缺失 + 重载引擎）。 */
    public static CompletableFuture<Boolean> selectModel(String id) {
        return SpeakerCore.selectModel(id);
    }

    /** 仅下载模型到本地。 */
    public static CompletableFuture<Boolean> downloadModel(String id) {
        return SpeakerCore.downloadModel(id);
    }

    // ===== 内部工具 =====

    /** 方块 -> 中心坐标（不用 Vec3.atCenterOf 以兼容 1.19~1.21 全部映射实现）。 */
    private static Vec3 center(BlockPos pos) {
        return new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
    }

    private static Pos3 toPos3(Vec3 v) {
        return v == null ? null : new Pos3(v.x, v.y, v.z);
    }
}
