package net.speakerapi.core;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;

/**
 * 音频输出层（common）：默认实现使用 {@link javax.sound.sampled.SourceDataLine}
 * ——不依赖任何 loader / OpenAL 内部音频 API。合成采样率（16/24/44.1k）线性重采样到
 * 输出设备目标频率。
 *
 * <p>空间化所需的听者位姿由 {@link Platform#listenerPose()} 提供（纯 core 记录
 * {@link ListenerPose}），因此本类不含任何 MC 类型。</p>
 *
 * <p>关键：每个 {@code say()} 调用通过 {@link #openAsync()} 开<b>一条</b>独占的
 * {@link SourceDataLine}，整句话的所有分段都写同一条线，播完（{@link Session#close()}）
 * 才关。这样把音频端点的 open/close 频率从「每条短句一次」降到「每句话一次」。
 * 在 HDMI/DP 这类音视频共用一根线的显示器上，频繁开关音频端点会让显卡/显示器
 * 重新协商，表现为「屏幕闪烁」——集中到每句话只开关一次即可消除。</p>
 *
 * <p>不同 {@code say()} 调用各自开自己的线，由系统 mixer 自然混音 => 跨消息仍然
 * 并发、互不排队。同一句话内部由各分段顺序写同一条线，连续朗读不重叠。</p>
 */
public final class AudioPlayer {

    // 播放线程池: 每个播放任务独立守护线程。不同 say() 的任务并发执行 (各自一条线)。
    private final ExecutorService playbackExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "speakerapi-playback");
        t.setDaemon(true);
        return t;
    });

    private final int targetHz;
    private volatile boolean muted = false;

    /** 定位音频的距离衰减上限 (方块)。超过此距离按最小增益 (MIN_ATTEN) 处理。 */
    private static final double MAX_DISTANCE = 24.0;
    /** 近场不衰减半径 (方块)。此距离内为满音量。 */
    private static final double NEAR_DISTANCE = 2.0;
    private static final double MIN_ATTEN = 0.08;

    /** 居中时的恒功率增益 sqrt(2)/2。 */
    private static final float CENTER_GAIN = 0.707f;

    public AudioPlayer(int targetHz) {
        this.targetHz = targetHz;
    }

    public void setMuted(boolean m) {
        this.muted = m;
    }

    /**
     * 异步开一条新的播放线 (在播放线程上, 不阻塞调用方)。
     * @param position 世界坐标; 非 null 时开<b>立体声</b>线并做空间化 (声像+距离衰减),
     *                使语音听起来像从那个方块/实体方向传来; 为 null 则普通单声道全局播放。
     *                无可用线时返回 null。
     */
    public CompletableFuture<Session> openAsync(Pos3 position) {
        return CompletableFuture.supplyAsync(() -> openFixed(position), playbackExecutor);
    }

    /** 兼容旧调用: 无定位 (全局单声道)。 */
    public CompletableFuture<Session> openAsync() {
        return openAsync(null);
    }

    /**
     * 异步开一条「跟随」播放线：每段写入前实时从 {@code follow} 取坐标（实体/方块随移动更新），
     * 因此语音会持续从源当前方向传来。无可用线时返回 null。
     */
    public CompletableFuture<Session> openFollowAsync(PositionProvider follow) {
        return CompletableFuture.supplyAsync(() -> openFollow(follow), playbackExecutor);
    }

    /** 开一条固定坐标的 SourceDataLine（失败 / 静音时返回 null）。 */
    private Session openFixed(Pos3 position) {
        if (muted) {
            return null;
        }
        boolean positional = position != null;
        try {
            AudioFormat fmt;
            DataLine.Info info;
            if (positional) {
                // 立体声 16-bit: 左右声道交织, 由空间化算法填入声像。
                fmt = new AudioFormat((float) targetHz, 16, 2, true, false);
                info = new DataLine.Info(SourceDataLine.class, fmt);
                if (!AudioSystem.isLineSupported(info)) {
                    // 设备不支持立体声: 退化为单声道 (居中, 不做声像, 但仍保留距离衰减)。
                    Core.log().warn("[SpeakerAPI] 设备不支持立体声, 定位播放退化为单声道居中。");
                    positional = false;
                    fmt = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED,
                            targetHz, 16, 1, 2, targetHz, false);
                    info = new DataLine.Info(SourceDataLine.class, fmt);
                }
            } else {
                fmt = new AudioFormat((float) targetHz, 16, 1, true, false);
                info = new DataLine.Info(SourceDataLine.class, fmt);
                if (!AudioSystem.isLineSupported(info)) {
                    fmt = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED,
                            targetHz, 16, 1, 2, targetHz, false);
                    info = new DataLine.Info(SourceDataLine.class, fmt);
                }
            }
            SourceDataLine line = (SourceDataLine) AudioSystem.getLine(info);
            line.open(fmt);
            line.start();
            return new Session(line, positional, null, position);
        } catch (Throwable t) {
            Core.log().error("[SpeakerAPI] 打开音频线失败: {}", t);
            return null;
        }
    }

    /** 开一条跟随坐标的 SourceDataLine（失败 / 静音时返回 null）。 */
    private Session openFollow(PositionProvider follow) {
        if (muted || follow == null) {
            return null;
        }
        boolean positional = true;
        try {
            AudioFormat fmt = new AudioFormat((float) targetHz, 16, 2, true, false);
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, fmt);
            if (!AudioSystem.isLineSupported(info)) {
                // 设备不支持立体声: 退化为单声道居中（仍跟随, 只是不做声像）。
                Core.log().warn("[SpeakerAPI] 设备不支持立体声, 跟随播放退化为单声道居中。");
                positional = false;
                fmt = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED,
                        targetHz, 16, 1, 2, targetHz, false);
                info = new DataLine.Info(SourceDataLine.class, fmt);
            }
            SourceDataLine line = (SourceDataLine) AudioSystem.getLine(info);
            line.open(fmt);
            line.start();
            return new Session(line, positional, follow, null);
        } catch (Throwable t) {
            Core.log().error("[SpeakerAPI] 打开跟随音频线失败: {}", t);
            return null;
        }
    }

    /** 一条独占了 SourceDataLine 的播放会话: 整句话写它, 播完 close。 */
    public final class Session {

        private final SourceDataLine line;
        private final boolean positional;
        /** 固定源的世界坐标快照 (开线时确定); 仅 follow 为 null 时使用。 */
        private final Pos3 fixedPos;
        /** 跟随源: 每段写入前实时取坐标; 为 null 表示使用 fixedPos 快照。 */
        private final PositionProvider follow;

        private Session(SourceDataLine line, boolean positional, PositionProvider follow, Pos3 fixedPos) {
            this.line = line;
            this.positional = positional;
            this.follow = follow;
            this.fixedPos = fixedPos;
        }

        /** 是否空间化会话 (立体声 + 声像/距离衰减)。 */
        public boolean isPositional() {
            return positional;
        }

        /**
         * 把一段音频写进这条线 (阻塞到数据被线的缓冲接受, 不等待播完)。
         * 定位会话会按当前听者位姿实时计算左右声像与距离衰减：
         * 跟随会话每段重新取源坐标, 因此语音持续从源当前方向传来。
         */
        public void write(AudioData audio, float volume) {
            try {
                float[] resampled = resample(audio.samples(), audio.sampleRate(), targetHz);
                byte[] pcm;
                if (positional) {
                    Pos3 src = (follow != null) ? follow.position() : fixedPos;
                    if (src != null) {
                        float[] gains = stereoGains(src);
                        pcm = toPcm16Stereo(resampled, volume, gains[0], gains[1]);
                    } else {
                        // 源暂时不可闻 (如实体已移除): 居中播放, 整句话仍继续。
                        pcm = toPcm16Stereo(resampled, volume, CENTER_GAIN, CENTER_GAIN);
                    }
                } else {
                    pcm = toPcm16(resampled, volume);
                }
                line.write(pcm, 0, pcm.length);
            } catch (Throwable t) {
                Core.log().error("[SpeakerAPI] 写入音频失败: {}", t);
            }
        }

        /** 排空缓冲并关闭这条线。 */
        public void close() {
            try {
                line.drain();
                line.stop();
                line.close();
            } catch (Throwable t) {
                // 忽略关闭异常
            }
        }
    }

    /**
     * 计算某世界坐标相对当前听者（摄像机）的立体声增益 [左, 右]。
     * 采用恒功率声像 (constant-power pan): 正前方两声道均等, 偏右则右声道渐强、左声道渐弱;
     * 同时叠加距离衰减 (近场满音量, 远场按 MIN_ATTEN 收束)。
     * 无听者位姿 (服务端 / 尚未进入世界 / 平台未注入) 时退化为居中 {0.707, 0.707}。
     */
    static float[] stereoGains(Pos3 source) {
        try {
            Platform p = Core.platform();
            if (p == null) {
                return new float[]{CENTER_GAIN, CENTER_GAIN};
            }
            ListenerPose listener = p.listenerPose();
            if (listener == null) {
                return new float[]{CENTER_GAIN, CENTER_GAIN};
            }
            Pos3 lp = listener.position();
            Pos3 fwd = listener.forward(); // 含俯仰的单位前向
            // right = normalize(fwd × up), up=(0,1,0) -> 恒为水平向量 (-fz, 0, fx)。
            double rx = -fwd.z();
            double rz = fwd.x();
            double rlen = Math.hypot(rx, rz);
            if (rlen < 1e-6) {
                rx = 1.0;
                rz = 0.0;
            } else {
                rx /= rlen;
                rz /= rlen;
            }
            double dx = source.x() - lp.x();
            double dy = source.y() - lp.y();
            double dz = source.z() - lp.z();
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            // 水平方向沿 right 的分量 / 距离 => 方向声像系数 dir ∈ [-1,1] (dy 对水平 right 贡献为 0)。
            double pan = dx * rx + dz * rz;
            double dir = dist > 1e-6 ? pan / dist : 0.0;
            // 恒功率: dir=-1 -> 全左, dir=0 -> 均等(0.707), dir=1 -> 全右。
            double angle = (dir * 0.5 + 0.5) * (Math.PI / 2);
            float gL = (float) Math.cos(angle);
            float gR = (float) Math.sin(angle);
            // 距离衰减。
            double atten;
            if (dist <= NEAR_DISTANCE) {
                atten = 1.0;
            } else if (dist >= MAX_DISTANCE) {
                atten = MIN_ATTEN;
            } else {
                atten = MIN_ATTEN + (1.0 - MIN_ATTEN) * (1.0 - (dist - NEAR_DISTANCE) / (MAX_DISTANCE - NEAR_DISTANCE));
            }
            return new float[]{gL * (float) atten, gR * (float) atten};
        } catch (Throwable t) {
            return new float[]{CENTER_GAIN, CENTER_GAIN};
        }
    }

    /** Linear-interpolation resample. Good enough for speech; cheap and dependency-free. */
    private static float[] resample(float[] in, int srcHz, int dstHz) {
        if (srcHz == dstHz) {
            return in;
        }
        double ratio = (double) dstHz / srcHz;
        int outLen = (int) Math.ceil(in.length * ratio);
        float[] out = new float[outLen];
        for (int i = 0; i < outLen; i++) {
            double pos = i / ratio;
            int i0 = (int) pos;
            int i1 = Math.min(i0 + 1, in.length - 1);
            double frac = pos - i0;
            out[i] = (float) (in[i0] * (1 - frac) + in[i1] * frac);
        }
        return out;
    }

    private static byte[] toPcm16(float[] samples, float volume) {
        ByteBuffer buf = ByteBuffer.allocate(samples.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (float s : samples) {
            float c = Math.max(-1f, Math.min(1f, s * volume));
            buf.putShort((short) (c * 32767f));
        }
        return buf.array();
    }

    /** 立体声 16-bit LE 交织 PCM: [L0,R0,L1,R1,...], 各声道乘对应增益。 */
    private static byte[] toPcm16Stereo(float[] samples, float volume, float gainL, float gainR) {
        ByteBuffer buf = ByteBuffer.allocate(samples.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float s : samples) {
            float c = Math.max(-1f, Math.min(1f, s * volume));
            buf.putShort((short) (Math.max(-1f, Math.min(1f, c * gainL)) * 32767f));
            buf.putShort((short) (Math.max(-1f, Math.min(1f, c * gainR)) * 32767f));
        }
        return buf.array();
    }

    public void shutdown() {
        playbackExecutor.shutdownNow();
    }
}
