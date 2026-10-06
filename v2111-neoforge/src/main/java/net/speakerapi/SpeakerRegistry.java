package net.speakerapi;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 注册适配样例（NeoForge 1.21.1 形态）。
 *
 * <p>同一个「注册项」在三个版本里的写法对照：
 * <ul>
 *   <li><b>NeoForge 1.21.1</b>（本文件）：{@code DeferredRegister.create(BuiltInRegistries.SOUND_EVENT, MOD_ID)}
 *       → {@code DeferredHolder<SoundEvent, SoundEvent>}；ID 用
 *       {@code ResourceLocation.fromNamespaceAndPath}。</li>
 *   <li><b>Forge 1.20.1</b>：{@code DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, MOD_ID)}
 *       → {@code RegistryObject<SoundEvent>}；ID 用 {@code new ResourceLocation(...)}。</li>
 *   <li><b>Forge 1.19.2</b>：同上 DeferredRegister + ForgeRegistries，但
 *       {@code SoundEvent} 用构造器 {@code new SoundEvent(id)}。</li>
 * </ul>
 *
 * <p>注册出来的 {@code speakerapi:voice} 是本模组的「语音」占位音效 —— 让 TTS 输出
 * 在本 loader 的音效体系里有一个正式身份（悬浮提示/资源包/调试均可引用）。</p>
 */
public final class SpeakerRegistry {

    // NeoForge 的 DeferredRegister 直接接受编译期可见的 Registry 实例。
    public static final DeferredRegister<SoundEvent> SOUND_EVENTS =
            DeferredRegister.create(BuiltInRegistries.SOUND_EVENT, SpeakerApiMod.MOD_ID);

    /** 注册项本体：DeferredHolder 在 NeoForge 里取代了 Forge 的 RegistryObject。 */
    public static final DeferredHolder<SoundEvent, SoundEvent> VOICE =
            SOUND_EVENTS.register("voice", () -> SoundEvent.createVariableRangeEvent(id("voice")));

    /** 1.20.2+ 起构造器被废弃/私有化，改用静态工厂 {@code fromNamespaceAndPath}。 */
    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(SpeakerApiMod.MOD_ID, path);
    }

    private SpeakerRegistry() {
    }

    /** 挂到 mod 事件总线（由 SpeakerApiMod 构造期调用）。 */
    public static void register(IEventBus modBus) {
        SOUND_EVENTS.register(modBus);
    }
}
