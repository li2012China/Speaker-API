package net.speakerapi;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * 注册适配样例（Forge 1.19.2 形态）。
 *
 * <p>同一个「注册项」在三个版本里的写法对照：
 * <ul>
 *   <li><b>Forge 1.19.2</b>（本文件）：{@code DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, MOD_ID)}
 *       → {@code RegistryObject<SoundEvent>}；ID 用 {@code new ResourceLocation(...)}；
 *       SoundEvent 用构造器 {@code new SoundEvent(id)}。</li>
 *   <li><b>Forge 1.20.1</b>：同样的 DeferredRegister + ForgeRegistries，但改用静态工厂
 *       {@code SoundEvent.createVariableRangeEvent}。</li>
 *   <li><b>NeoForge 1.21.1</b>：{@code BuiltInRegistries.SOUND_EVENT} +
 *       {@code DeferredHolder} + {@code ResourceLocation.fromNamespaceAndPath}。</li>
 * </ul>
 */
public final class SpeakerRegistry {

    public static final DeferredRegister<SoundEvent> SOUND_EVENTS =
            DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, SpeakerApiMod.MOD_ID);

    /** Forge 侧的注册句柄是 RegistryObject。 */
    public static final RegistryObject<SoundEvent> VOICE =
            SOUND_EVENTS.register("voice", () -> new SoundEvent(id("voice")));

    /** 老写法：直接 new（1.20.2+ 起该构造器被资源加载走 blockbench 式封装修饰，改用静态工厂）。 */
    public static ResourceLocation id(String path) {
        return new ResourceLocation(SpeakerApiMod.MOD_ID, path);
    }

    private SpeakerRegistry() {
    }

    /** 挂到 mod 事件总线（由 SpeakerApiMod 构造期调用）。 */
    public static void register(IEventBus modBus) {
        SOUND_EVENTS.register(modBus);
    }
}
