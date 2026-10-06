package net.speakerapi.core;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;

/**
 * 接管 Minecraft / 其他模组通过 {@code com.mojang.text2speech.Narrator} 发出的
 * 「系统讲述人」语音调用，强制改走本模组的离线 TTS。
 *
 * <p>本类位于 common 层，因此<b>全部通过反射</b>操作：只认
 * {@code Object 持有者 + 字段名}，不 import 任何 MC / loader / text2speech 类。
 * 由版本适配层用 {@code Minecraft.getInstance().getNarrator()} 拿到持有者传入
 * （1.19.2 / 1.20.1 / 1.21.1 均为 GameNarrator，内部字段名 {@code narrator}）。</p>
 *
 * <p>原理：把持有者内部那个「真·系统 TTS」实例换成一个动态代理。每一次
 * {@code say(String, boolean)} 在「已开启替换 + 模组启用 + 引擎就绪」时重定向到
 * {@link SpeakerCore#sayNarrator(String)}；否则透明转发给原系统语音 ——
 * 接管失败也绝不影响游戏正常发声，不会丢失无障碍能力。</p>
 *
 * <p>之所以用 JDK 动态代理而不是 implements 接口：避免对 text2speech 库产生编译期依赖
 * （该库随 MC 分发，未必在模组编译 classpath 上），也让 common 保持零 MC 依赖。</p>
 */
public final class NarratorHook {

    private static volatile boolean installed = false;

    private NarratorHook() {
    }

    /** 是否已成功接管（供命令 / 日志查询）。 */
    public static boolean isInstalled() {
        return installed;
    }

    /**
     * 尝试从 {@link Platform#narratorHolder()} 接管。
     * 幂等；任何异常都吞掉并回退到原系统语音。
     */
    public static void tryInstall() {
        Platform p = Core.platform();
        if (p == null) {
            return;
        }
        try {
            install(p.narratorHolder());
        } catch (Throwable t) {
            Core.log().error("[SpeakerAPI] 接管系统讲述人失败, 回退到原系统语音: {}", t.getMessage());
            Core.log().debug("[SpeakerAPI] 接管失败详情", t);
        }
    }

    /**
     * 把代理装到 {@code holder}（或其直接成员）内部的系统 TTS 字段上。
     *
     * <p><b>为什么必须按「结构」而不是按字段名查找</b>：Forge / NeoForge 发布构建会
     * 对 mod jar 做 reobf，游戏侧成员名同时被重映射。此时 {@code getNarrator()} 方法名、
     * {@code narrator} 字段名在生产环境都变了，纯 name-based 反射会静默失效。
     * 因此这里扫描一个类里<b>「类型为接口、且接口上有 say(String, ...) 方法」</b>的成员，
     * 这个特征在任何映射表下都成立。</p>
     *
     * @param holder 起始对象（一般是 Minecraft 实例，或直接的 GameNarrator）
     * @return 是否安装成功
     */
    public static boolean install(Object holder) {
        if (installed || holder == null) {
            return false;
        }
        try {
            Field target = findTargetField(holder.getClass());
            Object owner = holder;

            if (target == null) {
                // holder 自身不含目标字段时，往它的成员里找一层（Minecraft -> GameNarrator）
                for (Field f : holder.getClass().getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) {
                        continue;
                    }
                    Object value = read(holder, f);
                    if (value == null) {
                        continue;
                    }
                    Field inner = findTargetField(value.getClass());
                    if (inner != null) {
                        owner = value;
                        target = inner;
                        break;
                    }
                }
            }
            if (target == null) {
                return false;
            }
            return installField(owner, target);
        } catch (Throwable t) {
            Core.log().error("[SpeakerAPI] 接管系统讲述人失败, 回退到原系统语音: {}", t.getMessage());
            Core.log().debug("[SpeakerAPI] 接管失败详情", t);
            return false;
        }
    }

    /** 在该类型的所有实例字段里找一个「系统 TTS 接口」字段。 */
    private static Field findTargetField(Class<?> type) {
        Class<?> current = type;
        while (current != null && current != Object.class) {
            for (Field f : current.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || !f.getType().isInterface()) {
                    continue;
                }
                if (isTtsInterface(f.getType())) {
                    f.setAccessible(true);
                    return f;
                }
            }
            current = current.getSuperclass();
        }
        return null;
    }

    /** 判定：接口里有名为 say、首个参数为 String 的方法 —— com.mojang.text2speech.Narrator 的形态。 */
    private static boolean isTtsInterface(Class<?> ifc) {
        try {
            for (Method m : ifc.getMethods()) {
                if ("say".equals(m.getName())
                        && m.getParameterCount() >= 1
                        && String.class.equals(m.getParameterTypes()[0])) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            return false;
        }
        return false;
    }

    private static Object read(Object owner, Field f) {
        try {
            f.setAccessible(true);
            return f.get(owner);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 替换一个已定位的字段。幂等；已是代理则跳过。 */
    private static boolean installField(Object owner, Field field) throws IllegalAccessException {
        Object original = field.get(owner);
        if (original == null || Proxy.isProxyClass(original.getClass())) {
            return false;
        }
        Class<?> narratorIfc = field.getType();
        Object proxy = Proxy.newProxyInstance(
                narratorIfc.getClassLoader(),
                new Class<?>[]{narratorIfc},
                new RedirectHandler(original));

        // 目标字段通常是 final；多数 JDK 上 set 可直接写入，极个别情况需先剥掉 final 位。
        try {
            field.set(owner, proxy);
        } catch (IllegalAccessException iae) {
            try {
                Field modifiers = Field.class.getDeclaredField("modifiers");
                modifiers.setAccessible(true);
                modifiers.setInt(field, field.getModifiers() & ~Modifier.FINAL);
                field.set(owner, proxy);
            } catch (Throwable inner) {
                throw iae;
            }
        }

        installed = true;
        Platform p = Core.platform();
        Core.log().info("[SpeakerAPI] 已接管系统讲述人(Narrator) —— 游戏/模组调用系统讲述人时将强制改用本模组 TTS"
                        + " (replaceSystemTts={}, loader={}, mc={})",
                SpeakerCore.isReplaceSystemTts(), p != null ? p.loaderName() : "none",
                p != null ? p.minecraftVersion() : "none");
        return true;
    }

    /** 把 Narrator 接口方法调用重定向到本模组 TTS，或透明转发给原系统语音。 */
    private static final class RedirectHandler implements InvocationHandler {

        private final Object original;

        RedirectHandler(Object original) {
            this.original = original;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            try {
                // say(String message, boolean interrupt) —— 唯一的「发声」入口。
                if ("say".equals(name) && args != null && args.length >= 1 && args[0] instanceof String text) {
                    if (SpeakerCore.isReplaceSystemTts() && SpeakerCore.isEnabled() && SpeakerCore.isEngineReady()) {
                        SpeakerCore.sayNarrator(text);
                        return null; // 接口方法返回 void
                    }
                    // 未开启替换 / 引擎未就绪：走原系统讲述人，保证无障碍不丢失。
                }
                // clear / destroy / active / toString / hashCode / equals 等一律转发给原对象。
                return method.invoke(original, args);
            } catch (InvocationTargetException e) {
                throw e.getCause() != null ? e.getCause() : e;
            }
        }
    }
}
