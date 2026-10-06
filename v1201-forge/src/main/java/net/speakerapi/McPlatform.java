package net.speakerapi;

import java.nio.file.Path;

import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

import net.speakerapi.core.CoreLogger;
import net.speakerapi.core.ListenerPose;
import net.speakerapi.core.Platform;
import net.speakerapi.core.Pos3;
import net.speakerapi.core.SoundCategory;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Forge 1.20.1 的 {@link Platform} 实现：把 MC 侧的能力（日志、音量滑块、摄像机
 * 位姿、讲述人句柄）喂给 common 层。三个版本子项目各有一份同名实现，
 * 差异只在这里被消化 —— common 永远看不到 {@code net.minecraft.*}。
 */
final class McPlatform implements Platform {

    private final Path gameDir;
    private final Logger logger = LogManager.getLogger("speakerapi");

    McPlatform(Path gameDir) {
        this.gameDir = gameDir;
    }

    @Override
    public String loaderName() {
        return "forge";
    }

    @Override
    public String minecraftVersion() {
        return "1.20.1";
    }

    @Override
    public Path gameDir() {
        return gameDir;
    }

    @Override
    public CoreLogger logger() {
        return log4j();
    }

    @Override
    public double categoryLevel(SoundCategory category) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.options == null) {
            return 1.0;
        }
        SoundSource src = toSource(category);
        if (src == null) {
            return 1.0;
        }
        return mc.options.getSoundSourceVolume(src);
    }

    @Override
    public ListenerPose listenerPose() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            return null;
        }
        net.minecraft.world.entity.Entity cam = mc.getCameraEntity();
        if (cam == null) {
            return null;
        }
        Vec3 p = cam.position();
        Vec3 f = cam.getViewVector(1.0F);
        return new ListenerPose(new Pos3(p.x, p.y, p.z), new Pos3(f.x, f.y, f.z));
    }

    @Override
    public Object narratorHolder() {
        // 直接返回 Minecraft 实例作为搜索起点：NarratorHook 会按结构找到
        // 真正的系统 TTS 句柄，不依赖 getNarrator()/narrator 这些会被 reobf 改掉的名字。
        return Minecraft.getInstance();
    }

    // ===== core SoundCategory <-> MC SoundSource =====

    /** core 分类 -> MC 声音分类。注意枚举名差异：BLOCKS / PLAYERS。 */
    static SoundSource toSource(SoundCategory cat) {
        if (cat == null) {
            return SoundSource.VOICE;
        }
        return switch (cat) {
            case MASTER -> SoundSource.MASTER;
            case MUSIC -> SoundSource.MUSIC;
            case RECORDS -> SoundSource.RECORDS;
            case WEATHER -> SoundSource.WEATHER;
            case BLOCK -> SoundSource.BLOCKS;
            case HOSTILE -> SoundSource.HOSTILE;
            case NEUTRAL -> SoundSource.NEUTRAL;
            case PLAYER -> SoundSource.PLAYERS;
            case AMBIENT -> SoundSource.AMBIENT;
            case VOICE -> SoundSource.VOICE;
        };
    }

    /** MC 声音分类 -> core 分类。 */
    static SoundCategory toCategory(SoundSource src) {
        if (src == null) {
            return SoundCategory.VOICE;
        }
        return switch (src) {
            case MASTER -> SoundCategory.MASTER;
            case MUSIC -> SoundCategory.MUSIC;
            case RECORDS -> SoundCategory.RECORDS;
            case WEATHER -> SoundCategory.WEATHER;
            case BLOCKS -> SoundCategory.BLOCK;
            case HOSTILE -> SoundCategory.HOSTILE;
            case NEUTRAL -> SoundCategory.NEUTRAL;
            case PLAYERS -> SoundCategory.PLAYER;
            case AMBIENT -> SoundCategory.AMBIENT;
            case VOICE -> SoundCategory.VOICE;
            default -> SoundCategory.MASTER;
        };
    }

    /** Log4j 2 -> core 日志接口。三个版本（Forge/NeoForge）都用它。 */
    private static CoreLogger log4j() {
        return LOG;
    }

    private static final CoreLogger LOG = new CoreLogger() {

        @Override
        public void info(String message, Object... args) {
            logger0().info(message, args);
        }

        @Override
        public void warn(String message, Object... args) {
            logger0().warn(message, args);
        }

        @Override
        public void error(String message, Object... args) {
            logger0().error(message, args);
        }

        @Override
        public void error(String message, Throwable t) {
            logger0().error(message, t);
        }

        @Override
        public void debug(String message, Throwable t) {
            logger0().debug(message, t);
        }

        @Override
        public boolean isDebugEnabled() {
            return logger0().isDebugEnabled();
        }

        private Logger logger0() {
            return LogManager.getLogger("speakerapi");
        }
    };
}
