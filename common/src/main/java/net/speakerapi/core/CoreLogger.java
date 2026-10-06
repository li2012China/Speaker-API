package net.speakerapi.core;

/**
 * 日志抽象。common 层禁止依赖任何 loader / MC 内置库（包括 Log4j），
 * 因此日志走这层接口，由各版本适配层用本 loader 的日志实现注入
 * （Forge / NeoForge 均为 Log4j 2）。
 *
 * <p>占位符语义与 Log4j 一致：{@code info("下载进度 {}%", 42)}。</p>
 */
public interface CoreLogger {

    void info(String message, Object... args);

    void warn(String message, Object... args);

    void error(String message, Object... args);

    void error(String message, Throwable t);

    void debug(String message, Throwable t);

    default boolean isDebugEnabled() {
        return false;
    }
}
