package net.speakerapi.core;

/**
 * 一个会随时间返回最新世界坐标的「绑定源」。
 *
 * <p>把语音绑定到实体 / 方块时使用：每次写入音频分段前都会调用
 * {@link #position()} 取当前坐标，因此语音会<b>持续跟随</b>移动中的实体
 * （而不只是在调用瞬间拍一张快照）。方块是静止的，但用同一个接口即可，
 * 实现上返回一个常量坐标。</p>
 *
 * <p>返回 {@code null} 表示「本刻不可闻」——例如实体已被移除：
 * 此时不会崩溃，而是退化为居中（双声道均等）播放，整句话仍继续。</p>
 */
@FunctionalInterface
public interface PositionProvider {

    /** 当前时刻的世界坐标；返回 null 表示本刻静默/居中。 */
    Pos3 position();
}
