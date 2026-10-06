package net.speakerapi;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;
// 未确认 FQN: ClientTickEvent 在 1.21.1 的包路径, 以 MDK 源码/映射核对。
//   备选: net.neoforged.neoforge.event.TickEvent.ClientTickEvent
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * 把 Minecraft 游戏事件桥接为语音反馈。
 *
 * <p>已按规格 B 实现/确认:
 * <ul>
 *   <li><b>聊天</b> — {@link ClientChatReceivedEvent} (client bus)。取 {@code getMessage().getString()}
 *       去 § 色码; 按 {@code isSystem()} 区分系统/玩家子类; 命令与坐标由全局正则黑名单拦截
 *       (在 {@link SpeakerAPI#say} 内)。受 {@code read_chat} 控制。</li>
 * </ul>
 *
 * <p>未确认 / 扩展点 (沙箱无网、无 MDK 源码可核对类名的, 一律不写死, 用注释 stub + 运行时自检):
 * <ul>
 *   <li><b>成就</b> — 见下方「成就两套方案」注释。两套均不写死类名, 避免编译错; 确认 MDK 类名后再接。</li>
 *   <li><b>系统吐司</b> — NeoForge <b>无公开吐司事件</b>; 仅可走可选 mixin (见注释), 默认关闭, 无 mixin 不崩溃。</li>
 *   <li><b>低血/天气/时间</b> — {@link ClientTickEvent} 节流轮询 {@code Minecraft.getInstance().player},
 *       只入队不在此合成 (见 onClientTick)。</li>
 * </ul>
 */
public final class GameEventReader {

    // 低血阈值 (心)。未确认: 是否应进配置; 此处先用常量。
    private static final float LOW_HEALTH_HP = 6.0f;
    private long lastStatusCheck = 0;

    @SubscribeEvent
    public void onChat(ClientChatReceivedEvent event) {
        if (!SpeakerAPI.isEnabled() || !SpeakerAPI.readChat()) {
            return;
        }
        Component message = event.getMessage();
        // getString() 已不含 § 样式码; 再防御性去除可能的 §x 色码。
        String raw = stripColorCodes(message.getString());
        // 系统/玩家子类区分: 系统消息归 "system" 源, 玩家聊天归 "chat" 源。
        String source = event.isSystem() ? "system" : "chat";
        SpeakerAPI.say(raw, source, SpeakerAPI.voiceFor(null), SpeakerAPI.Priority.NORMAL);
    }

    private static String stripColorCodes(String s) {
        if (s == null) {
            return "";
        }
        // § 后跟一个格式字符 (0-9 a-f k-o r); 去掉 § 与随后的码字符。
        return s.replaceAll("§[0-9a-fk-or]", "");
    }

    // ====================================================================
    // 成就接入: 两套方案, 均「未确认」需按 MDK 1.21.1 源码核对类名/包路径。
    // 当前不写死任何 Advancement 事件类, 避免编译错; 确认后再取消注释其一。
    //
    // 方案 A (单机 / 集成服务器可用): 监听进度"获得"事件。1.21.1 可能的类名:
    //   net.neoforged.neoforge.event.entity.player.AdvancementEvent.AdvancementEarnEvent
    //   或 net.neoforged.neoforge.event.AdvancementEvent.AdvancementEarnEvent
    //   取 event.getAdvancement().value().name() 的纯文本朗读。
    //   (远程联机时该事件在逻辑服务端触发, 纯客户端收不到 -> 用方案 B)
    //
    // 方案 B (远程联机 / 纯客户端): 拦截 ClientboundUpdateAdvancementsPacket,
    //   用 Minecraft.getInstance().getConnection().getAdvancements() 做差异,
    //   取新增 advancement 的显示名朗读。
    //
    // ---- 运行时自检 (替代口头猜测) ----
    // 启动后执行: 触发一次成就, 看日志是否出现 "[SpeakerAPI] 成就: xxx";
    // 若从未出现, 说明当前接的法子没收到事件 -> 换成另一套方案。
    // 自检片段 (放进 onClientTick 临时调试):
    //   var adv = Minecraft.getInstance().getConnection();
    //   System.out.println("[SpeakerAPI][自检] connection.advancements = " + adv);
    //
    // 方案 A 取消注释示例 (类名确认后):
    // @SubscribeEvent
    // public void onAdvancement(net.neoforged.neoforge.event.entity.player.AdvancementEvent.AdvancementEarnEvent event) {
    //     if (!SpeakerAPI.isEnabled() || !SpeakerAPI.readAchievements()) return;
    //     Component name = event.getAdvancement().value().name();
    //     SpeakerAPI.say(name, "achievement", null, SpeakerAPI.Priority.NORMAL);
    // }
    // ====================================================================

    // ====================================================================
    // 系统吐司: NeoForge 无公开事件。仅可选 mixin, 默认 false 不启用; 无 mixin 时本模组不崩溃。
    // 目标 (小版本敏感, 需按 1.21.1 mapping 核对包/签名):
    //   net.minecraft.client.gui.components.toasts.ToastComponent#show(Toast)
    // 启用条件: 在 mods.toml 或配置里显式打开 mixin; 默认关闭。
    // mixin 示例 (确认映射后取消注释, 并配 mixin 配置文件):
    // @Mixin(ToastComponent.class)
    // abstract class ToastComponentMixin {
    //     @Inject(method = "show", at = @At("HEAD"))
    //     private void onShow(Toast toast, CallbackInfo ci) {
    //         if (SpeakerAPI.isEnabled() && SpeakerAPI.readSystemToast()) {
    //             // 取 toast 的标题/描述文本 -> SpeakerAPI.say(..., "system", ...)
    //         }
    //     }
    // }
    // ====================================================================

    /** 低血/状态: ClientTickEvent.Post 节流轮询, 只入队不合成 (规格 B)。
     *  NeoForge 1.21.1 的 ClientTickEvent 为抽象类, 不可直接注册监听;
     *  必须注册到其具体子类 Pre / Post (此处用 Post, tick 结束后轮询)。 */
    @SubscribeEvent
    public void onClientTick(ClientTickEvent.Post event) {
        if (!SpeakerAPI.isEnabled() || !SpeakerAPI.readLowHealth()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastStatusCheck < 2000) { // 2s 节流
            return;
        }
        lastStatusCheck = now;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            return;
        }
        float hp = player.getHealth();
        if (hp <= LOW_HEALTH_HP) {
            // 只入队; 合成/播放发生在 ttsExecutor / playbackExecutor, 不在此 tick 路径。
            SpeakerAPI.say("血量偏低", "status", null, SpeakerAPI.Priority.ANNOUNCE);
        }
    }
}
