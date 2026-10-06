package net.speakerapi;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * 注册适配样例（Forge 1.20.1 形态）。
 *
 * <p>同一个「注册项」在三个版本里的写法对照：
 * <ul>
 *   <li><b>Forge 1.20.1</b>（本文件）：{@code DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, MOD_ID)}
 *       → {@code RegistryObject<SoundEvent>}；ID 用 {@code new ResourceLocation(...)}；
 *       SoundEvent 用静态工厂 {@code createVariableRangeEvent}。</li>
 *   <li><b>Forge 1.19.2</b>：同样的 DeferredRegister + ForgeRegistries，但 SoundEvent
 *       仍用公开构造器 {@code new SoundEvent(id)}。</li>
 *   <li><b>NeoForge 1.21.1</b>：{@code BuiltInRegistries.SOUND_EVENT} +
 *       {@code DeferredHolder} + {@code ResourceLocation.fromNamespaceAndPath}。</li>
 * </ul>
 */
public final class SpeakerRegistry {

    // Forge 用 IForgeRegistry 作为注册目标 —— 与 NeoForge 的 Registry/ResourceKey 不同。
    public static final DeferredRegister<SoundEvent> SOUND_EVENTS =
            DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, SpeakerApiMod.MOD_ID);

    /** Forge 侧的注册句柄是 RegistryObject（NeoForge 里对应 DeferredHolder）。 */
    public static final RegistryObject<SoundEvent> VOICE =
            SOUND_EVENTS.register("voice", () -> SoundEvent.createVariableRangeEvent(id("voice")));

    /** 1.20.2 之前构造器仍公开/可用，这里是经典写法；1.21 起必须换静态工厂。 */
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
