package net.speakerapi.core;

import java.nio.file.Path;

/**
 * 版本适配层必须实现的平台契约。
 *
 * <p>common 是纯 Java + sherpa-onnx 的 API / 引擎层，不允许出现
 * {@code net.minecraft.*} / {@code net.minecraftforge.*} / {@code net.neoforged.*}。
 * 一切与版本相关的能力（配置目录、日志、音量滑块、摄像机位姿、讲述人句柄）
 * 都通过这个接口由版本子项目提供。</p>
 *
 * <p>三个版本子项目各有自己的实现：{@code net.speakerapi.McPlatform}。</p>
 */
public interface Platform {

    /** loader 名称："forge" / "neoforge"。 */
    String loaderName();

    /** MC 版本号："1.19.2" / "1.20.1" / "1.21.1"。 */
    String minecraftVersion();

    /** 游戏运行目录（~config / models 的父目录）。 */
    Path gameDir();

    CoreLogger logger();

    /**
     * 某个 MC 声音分类滑块当前的音量（0.0~1.0）。
     * 版本实现读取 {@code Minecraft.getInstance().options.getSoundSourceVolume(...)}。
     */
    double categoryLevel(SoundCategory category);

    /**
     * 当前听者（摄像机）位姿，用于定位语音的空间化。
     * 拿不到（服务端 / 尚未进入世界）时返回 {@code null}，此时退化为中心单声道。
     */
    ListenerPose listenerPose();

    /**
     * 讲述人搜索起点：返回当前客户端 {@code Minecraft} 实例即可。
     * {@link NarratorHook} 会从这个对象出发，按结构（接口含 say(String) 方法的字段）
     * 找到真正的系统 TTS 句柄 —— 这样在生产环境的 reobf 映射下依然有效。
     */
    Object narratorHolder();
}
