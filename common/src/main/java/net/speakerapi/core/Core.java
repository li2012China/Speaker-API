package net.speakerapi.core;

import java.util.Arrays;

/**
 * common 层的运行时上下文：各版本适配层在模组构造期调用 {@link #install(Platform)}
 * 完成注入。所有 core 内部组件通过这里取日志与平台能力，避免直接依赖 loader / MC。
 */
public final class Core {

    private static volatile Platform platform;

    private Core() {
    }

    /** 由版本适配层注入（幂等：重复注入以最后一次为准）。 */
    public static void install(Platform p) {
        platform = p;
    }

    /** 当前平台；未注入时为 {@code null}（早期 static 上下文需要判空）。 */
    public static Platform platform() {
        return platform;
    }

    /**
     * core 统一日志出口。未注入平台时退化到 stdout，保证 core 在纯 JVM 单测里也能跑。
     */
    public static CoreLogger log() {
        return LOGGER;
    }

    private static final CoreLogger LOGGER = new CoreLogger() {

        @Override
        public void info(String message, Object... args) {
            emit("INFO", message, null, args);
        }

        @Override
        public void warn(String message, Object... args) {
            emit("WARN", message, null, args);
        }

        @Override
        public void error(String message, Object... args) {
            emit("ERROR", message, null, args);
        }

        @Override
        public void error(String message, Throwable t) {
            emit("ERROR", message, t, new Object[0]);
        }

        @Override
        public void debug(String message, Throwable t) {
            Platform p = platform;
            CoreLogger target = p != null ? p.logger() : null;
            if (target != null && target.isDebugEnabled()) {
                target.debug(message, t);
            }
        }

        private void emit(String level, String message, Throwable t, Object[] args) {
            Platform p = platform;
            CoreLogger target = p != null ? p.logger() : null;
            if (target != null) {
                switch (level) {
                    case "WARN" -> target.warn(message, args);
                    case "ERROR" -> {
                        if (t != null) {
                            target.error(message, t);
                        } else {
                            target.error(message, args);
                        }
                    }
                    default -> target.info(message, args);
                }
                return;
            }
            // 兜底：平台尚未注入
            String text = "[SpeakerAPI] " + level + " " + render(message, args);
            System.out.println(text);
            if (t != null) {
                t.printStackTrace(System.out);
            }
        }

        private String render(String message, Object[] args) {
            if (args == null || args.length == 0) {
                return message;
            }
            StringBuilder sb = new StringBuilder(message);
            // 逐个替换 Log4j 风格 {} 占位符
            for (Object a : args) {
                int idx = sb.indexOf("{}");
                if (idx < 0) {
                    sb.append(" | ").append(a);
                } else {
                    sb.replace(idx, idx + 2, String.valueOf(a));
                }
            }
            if (Arrays.asList(args).isEmpty()) {
                return sb.toString();
            }
            return sb.toString();
        }
    };
}
