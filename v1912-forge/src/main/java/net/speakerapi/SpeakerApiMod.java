package net.speakerapi;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;

import net.minecraft.commands.Commands;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.FMLPaths;

import net.speakerapi.core.Core;
import net.speakerapi.core.NarratorHook;
import net.speakerapi.core.SpeakerCore;
import net.speakerapi.core.TtsEngine;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Forge 1.19.2 适配层：生命周期 / 事件总线 / 命令 / 注册。
 *
 * <p>与 NeoForge 版本的差异全部集中在这里：
 * <ul>
 *   <li>事件总线：{@link MinecraftForge#EVENT_BUS}（Neo：{@code NeoForge.EVENT_BUS}）</li>
 *   <li>命令事件：{@link RegisterClientCommandsEvent}（Forge 主总线，客户端分发器）。
 *       切勿改用服务端的 {@code RegisterCommandsEvent}：那样命令虽能执行，但
 *       Tab 补全树不会同步到客户端，表现为\u201c补全不完整\u201d。</li>
 *   <li>客户端 setup 里用 {@code enqueueWork} 排队到主线程</li>
 *   <li>注册走 {@link SpeakerRegistry} 的 Forge 形态（DeferredRegister + ForgeRegistries）</li>
 * </ul>
 * 业务逻辑一律在 :common，这里只有接线。
 */
@Mod(SpeakerApiMod.MOD_ID)
public final class SpeakerApiMod {

    public static final String MOD_ID = "speakerapi";
    private static final Logger LOG = LogManager.getLogger(MOD_ID);

    public SpeakerApiMod() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        Core.install(new McPlatform(FMLPaths.GAMEDIR.get()));
        SpeakerRegistry.register(modBus);

        if (FMLEnvironment.dist == Dist.CLIENT) {
            modBus.addListener(this::onClientSetup);
            MinecraftForge.EVENT_BUS.register(new GameEventReader());
            MinecraftForge.EVENT_BUS.register(this);        // 命令
            MinecraftForge.EVENT_BUS.register(new Ticker()); // client tick -> 装讲述人钩子
        }
    }

    private void onClientSetup(FMLClientSetupEvent event) {
        if (FMLEnvironment.dist != Dist.CLIENT) {
            return;
        }
        // Forge 要求把触碰游戏状态的初始化排到主线程
        event.enqueueWork(() -> {
            SpeakerCore.bootstrap(null);
            SpeakerAPI.registerSource("chat", text -> !text.startsWith("/"));
            SpeakerAPI.registerSource("system", text -> true);
            SpeakerAPI.registerSource("achievement", text -> true);
            SpeakerAPI.registerSource("status", text -> true);
        });
        LOG.info("Speaker API ready on 1.19.2/Forge (enabled={}).", SpeakerCore.isEnabled());
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterClientCommandsEvent event) {
        if (FMLEnvironment.dist != Dist.CLIENT) {
            return;
        }
        event.getDispatcher().register(
                Commands.literal("speakerapi")
                        .executes(ctx -> {
                            ctx.getSource().sendSystemMessage(Component.literal(
                                    "\u00a77[SpeakerAPI] \u7528\u6cd5: /speakerapi test|category|systemtts|stop|voices|status|model|download|sayat"));
                            return 1;
                        })
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
        );
    }

    /** Client tick：首个 tick 安装讲述人接管（此时 Minecraft 实例已就绪）。 */
    static final class Ticker {

        @SubscribeEvent
        public void onTick(net.minecraftforge.event.TickEvent.ClientTickEvent event) {
            if (event.phase != net.minecraftforge.event.TickEvent.Phase.START) {
                return;
            }
            if (!NarratorHook.isInstalled()) {
                NarratorHook.tryInstall();
            }
        }
    }
}
