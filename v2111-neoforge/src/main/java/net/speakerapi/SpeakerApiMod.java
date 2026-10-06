package net.speakerapi;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;

import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.common.NeoForge;

import net.speakerapi.core.Core;
import net.speakerapi.core.NarratorHook;
import net.speakerapi.core.SpeakerCore;
import net.speakerapi.core.TtsEngine;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * NeoForge 1.21.1 适配层：生命周期 / 事件总线 / 客户端命令 / 注册。
 *
 * <p>这里只做「接线」：注入 {@link McPlatform}、注册 {@link SpeakerRegistry}、
 * 让 common 层 {@link SpeakerCore#bootstrap} 跑起来、装讲述人接管钩子。
 * 全部业务逻辑都在 :common。</p>
 */
@Mod(SpeakerApiMod.MOD_ID)
public final class SpeakerApiMod {

    public static final String MOD_ID = "speakerapi";
    private static final Logger LOG = LogManager.getLogger(MOD_ID);

    public SpeakerApiMod(IEventBus modBus) {
        // 1) 注入平台实现（之后 gameDir 可由 Core.platform().gameDir() 取得）
        Core.install(new McPlatform(FMLPaths.GAMEDIR.get()));

        // 2) 注册：DeferredRegister 需要挂到 mod 事件总线
        SpeakerRegistry.register(modBus);

        // 3) 仅客户端接线（side=CLIENT，专服不会加载本类）
        if (FMLEnvironment.dist == Dist.CLIENT) {
            modBus.addListener(this::onClientSetup);
            NeoForge.EVENT_BUS.register(new GameEventReader());
            NeoForge.EVENT_BUS.register(this); // onRegisterClientCommands
            // 首个 client tick 安装讲述人接管（此时 Minecraft.getInstance() 已非空）
            NeoForge.EVENT_BUS.addListener((ClientTickEvent.Pre e) -> {
                if (!NarratorHook.isInstalled()) {
                    NarratorHook.tryInstall();
                }
            });
        }
    }

    private void onClientSetup(FMLClientSetupEvent event) {
        if (FMLEnvironment.dist != Dist.CLIENT) {
            return;
        }
        SpeakerCore.bootstrap(null); // null => 用 Core.platform().gameDir()

        SpeakerAPI.registerSource("chat", text -> !text.startsWith("/"));
        SpeakerAPI.registerSource("system", text -> true);
        SpeakerAPI.registerSource("achievement", text -> true);
        SpeakerAPI.registerSource("status", text -> true);

        LOG.info("Speaker API ready on 1.21.1/NeoForge (enabled={}).", SpeakerAPI.isEnabled());
    }

    @SubscribeEvent
    public void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        if (!FMLEnvironment.dist.isClient()) {
            return;
        }
        event.getDispatcher().register(
                Commands.literal("speakerapi")
                        .then(Commands.literal("test").then(
                                Commands.argument("text", StringArgumentType.greedyString()).executes(ctx -> {
                                    String raw = StringArgumentType.getString(ctx, "text").trim();
                                    if (raw.isEmpty()) {
                                        ctx.getSource().sendSystemMessage(
                                                Component.literal("用法: /speakerapi test <文字>"));
                                        return 1;
                                    }
                                    if (!SpeakerAPI.isEnabled()) {
                                        ctx.getSource().sendSystemMessage(
                                                Component.literal("§c[SpeakerAPI] 已禁用"));
                                        return 1;
                                    }
                                    TtsEngine engine = TtsEngine.instance();
                                    if (!engine.isReady()) {
                                        if (!engine.isLoading()) {
                                            engine.reload();
                                            ctx.getSource().sendSystemMessage(Component.literal(
                                                    "§e[SpeakerAPI] 模型缺失, 已开始后台下载 (~334MB, 仅首次)"
                                                            + " — 请稍候重试"));
                                        } else {
                                            ctx.getSource().sendSystemMessage(Component.literal(
                                                    "§e[SpeakerAPI] 模型加载中… (首次需下载模型, 详见日志进度)"));
                                        }
                                        return 1;
                                    }
                                    SpeakerAPI.say(raw, "cmd", SpeakerAPI.voiceFor(null), SpeakerAPI.Priority.NORMAL);
                                    ctx.getSource().sendSystemMessage(Component.literal(
                                            "§7[SpeakerAPI] 已入队[" + SpeakerAPI.getCategory().name() + "]： " + raw));
                                    return 1;
                                })
                        ))
                        .then(Commands.literal("category").then(
                                Commands.argument("name", StringArgumentType.word()).executes(ctx -> {
                                    String n = StringArgumentType.getString(ctx, "name").trim();
                                    if (SpeakerAPI.setCategory(n)) {
                                        ctx.getSource().sendSystemMessage(Component.literal(
                                                "§a[SpeakerAPI] 已绑定到声音分类: " + SpeakerAPI.getCategory().name()));
                                    } else {
                                        ctx.getSource().sendSystemMessage(Component.literal(
                                                "§c[SpeakerAPI] 未知分类: " + n
                                                + " (可选: master/music/records/weather/block/hostile/neutral/player/ambient/voice)"));
                                    }
                                    return 1;
                                })
                        ))
                        .then(Commands.literal("systemtts").then(
                                Commands.argument("state", StringArgumentType.word()).executes(ctx -> {
                                    String s = StringArgumentType.getString(ctx, "state").trim().toLowerCase();
                                    boolean on;
                                    if ("on".equals(s) || "true".equals(s) || "1".equals(s)) {
                                        on = true;
                                    } else if ("off".equals(s) || "false".equals(s) || "0".equals(s)) {
                                        on = false;
                                    } else {
                                        ctx.getSource().sendSystemMessage(Component.literal(
                                                "§c[SpeakerAPI] 用法: /speakerapi systemtts <on|off>"));
                                        return 1;
                                    }
                                    SpeakerAPI.setReplaceSystemTts(on);
                                    String note = NarratorHook.isInstalled()
                                            ? "" : " (尚未接管, 已记录, 接管后即时生效)";
                                    ctx.getSource().sendSystemMessage(Component.literal(
                                            "§a[SpeakerAPI] 系统讲述人替换已" + (on ? "开启" : "关闭") + note));
                                    return 1;
                                })
                        ))
                        .then(Commands.literal("stop").executes(ctx -> {
                            int n = SpeakerAPI.activeCount();
                            SpeakerAPI.stopAll();
                            ctx.getSource().sendSystemMessage(Component.literal(
                                    "§a[SpeakerAPI] 已停止 " + n + " 条正在进行的朗读"));
                            return 1;
                        }))
                        .then(Commands.literal("voices").executes(ctx -> {
                            StringBuilder sb = new StringBuilder("§7[SpeakerAPI] 可用嗓音: ");
                            for (net.speakerapi.core.VoiceProfile v : SpeakerAPI.availableVoices()) {
                                sb.append("\n  §e").append(v.id()).append("§r — ").append(v.displayName());
                            }
                            ctx.getSource().sendSystemMessage(Component.literal(sb.toString()));
                            return 1;
                        }))
                        .then(Commands.literal("status").executes(ctx -> {
                            for (String line : net.speakerapi.core.SpeakerCore.statusLines()) {
                                ctx.getSource().sendSystemMessage(Component.literal(line));
                            }
                            return 1;
                        }))
                        .then(Commands.literal("model").then(
                                Commands.argument("id", StringArgumentType.string()).executes(ctx -> {
                                    String id = StringArgumentType.getString(ctx, "id").trim();
                                    if (!SpeakerAPI.isKnownModel(id)) {
                                        ctx.getSource().sendSystemMessage(Component.literal(
                                                "§c[SpeakerAPI] 未知模型: " + id + " (可用: "
                                                        + String.join(", ", SpeakerAPI.knownModelIds()) + ")"));
                                        return 1;
                                    }
                                    ctx.getSource().sendSystemMessage(Component.literal(
                                            "§e[SpeakerAPI] 正在切换并加载模型: " + id + " …"));
                                    Minecraft mc = Minecraft.getInstance();
                                    SpeakerAPI.selectModel(id).whenComplete((ok, ex) -> {
                                        mc.execute(() -> {
                                            if (ex != null || !ok) {
                                                ctx.getSource().sendSystemMessage(Component.literal(
                                                        "§c[SpeakerAPI] 模型 " + id + " 切换失败 (可能下载失败, 见日志)"));
                                            } else {
                                                ctx.getSource().sendSystemMessage(Component.literal(
                                                        "§a[SpeakerAPI] 已切换并加载模型: " + id));
                                            }
                                        });
                                    });
                                    return 1;
                                })
                        ))
                        .then(Commands.literal("download").then(
                                Commands.argument("id", StringArgumentType.string()).executes(ctx -> {
                                    String id = StringArgumentType.getString(ctx, "id").trim();
                                    if (!SpeakerAPI.isKnownModel(id)) {
                                        ctx.getSource().sendSystemMessage(Component.literal(
                                                "§c[SpeakerAPI] 未知模型: " + id + " (可用: "
                                                        + String.join(", ", SpeakerAPI.knownModelIds()) + ")"));
                                        return 1;
                                    }
                                    ctx.getSource().sendSystemMessage(Component.literal(
                                            "§e[SpeakerAPI] 开始下载模型: " + id + " (后台进行, 详见日志进度)"));
                                    Minecraft mc = Minecraft.getInstance();
                                    SpeakerAPI.downloadModel(id).whenComplete((ok, ex) -> {
                                        mc.execute(() -> {
                                            if (ex != null || !ok) {
                                                ctx.getSource().sendSystemMessage(Component.literal(
                                                        "§c[SpeakerAPI] 模型 " + id + " 下载失败 (见日志)"));
                                            } else {
                                                ctx.getSource().sendSystemMessage(Component.literal(
                                                        "§a[SpeakerAPI] 模型 " + id + " 已下载就绪 (用 /speakerapi model "
                                                                + id + " 切换)"));
                                            }
                                        });
                                    });
                                    return 1;
                                })
                        ))
                        .then(Commands.literal("sayat").then(
                                Commands.argument("x", DoubleArgumentType.doubleArg())
                                .then(Commands.argument("y", DoubleArgumentType.doubleArg())
                                .then(Commands.argument("z", DoubleArgumentType.doubleArg())
                                .then(Commands.argument("text", StringArgumentType.greedyString()).executes(ctx -> {
                                    double x = DoubleArgumentType.getDouble(ctx, "x");
                                    double y = DoubleArgumentType.getDouble(ctx, "y");
                                    double z = DoubleArgumentType.getDouble(ctx, "z");
                                    String raw = StringArgumentType.getString(ctx, "text").trim();
                                    if (raw.isEmpty()) {
                                        ctx.getSource().sendSystemMessage(Component.literal(
                                                "§c[SpeakerAPI] 用法: /speakerapi sayat <x> <y> <z> <文字>"));
                                        return 1;
                                    }
                                    if (!SpeakerAPI.isEngineReady()) {
                                        ctx.getSource().sendSystemMessage(Component.literal(
                                                "§e[SpeakerAPI] 引擎未就绪, 用 /speakerapi test 触发加载"));
                                        return 1;
                                    }
                                    BlockPos pos = new BlockPos((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
                                    SpeakerAPI.sayForBlock(pos, raw);
                                    ctx.getSource().sendSystemMessage(Component.literal(
                                            "§7[SpeakerAPI] 已在该方块绑定语音: " + raw));
                                    return 1;
                                }))))))
                        .executes(ctx -> {
                            ctx.getSource().sendSystemMessage(Component.literal(
                                    "§7[SpeakerAPI] 用法: /speakerapi test|category|systemtts|stop|voices|status|model|download|sayat"));
                            return 1;
                        })
        );
    }
}
