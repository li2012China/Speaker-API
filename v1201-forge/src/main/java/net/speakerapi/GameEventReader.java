package net.speakerapi;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;

import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * 把 Minecraft 游戏事件桥接为语音反馈（Forge 1.19.2 / 1.20.1 形态）。
 *
 * <p>公共逻辑与 NeoForge 版本完全一致，唯一差异是事件类的包路径与 tick 的 phase 判定：
 * <ul>
 *   <li>聊天：{@link ClientChatReceivedEvent}（Forge 在 {@code net.minecraftforge.client.event}）</li>
 *   <li>状态轮询：{@link TickEvent.ClientTickEvent} + {@code phase == START}
 *       （NeoForge 1.21 拆成了 Pre / Post 两个具体子类）</li>
 * </ul>
 */
public final class GameEventReader {

    private static final float LOW_HEALTH_HP = 6.0f;
    private long lastStatusCheck = 0;

    @SubscribeEvent
    public void onChat(ClientChatReceivedEvent event) {
        if (!SpeakerAPI.isEnabled() || !SpeakerAPI.readChat()) {
            return;
        }
        Component message = event.getMessage();
        String raw = stripColorCodes(message.getString());
        String source = event.isSystem() ? "system" : "chat";
        SpeakerAPI.say(raw, source, SpeakerAPI.voiceFor(null), SpeakerAPI.Priority.NORMAL);
    }

    private static String stripColorCodes(String s) {
        if (s == null) {
            return "";
        }
        return s.replaceAll("§[0-9a-fk-or]", "");
    }

    /** 低血/状态：tick 节流轮询，只入队不合成。 */
    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START) {
            return;
        }
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
        if (player.getHealth() <= LOW_HEALTH_HP) {
            SpeakerAPI.say("血量偏低", "status", null, SpeakerAPI.Priority.ANNOUNCE);
        }
    }
}
