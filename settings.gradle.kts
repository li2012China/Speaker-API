// ============================================================================
// Speaker API — 多版本多 Loader 聚合构建
//
// 每个 MC 版本是一个独立 Gradle 子项目，各自应用自己的 toolchain 插件并
// 各自产出独立 jar（不合并）：
//   :common         纯 API / 引擎层，禁止 import net.minecraft.*、
//                   net.minecraftforge.*、net.neoforged.*
//   :v1912-forge    1.19.2 + ForgeGradle + Java 17
//   :v1201-forge    1.20.1 + ForgeGradle + Java 17
//   :v2111-neoforge  1.21.1 + ModDevGradle + Java 21
//
// 为什么 1.19.2 也用 ForgeGradle 6：FG5 在源码里硬校验 Gradle 版本，遇到
// Gradle 8 直接抛 "Versions Gradle 8.0 and newer are not supported. Use
// ForgeGradle 6 or newer"，而 ModDevGradle(1.0.11) 必须跑在 Gradle 8 上。
// 同一聚合构建只能有一个 Gradle 版本，故统一 FG6 + Gradle 8.10.2。
// ============================================================================

pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://maven.minecraftforge.net") { name = "MinecraftForge" }
        // NeoForge 官方发行仓库（ModDevGradle 插件与 neoforge 工件都在这里）
        maven("https://maven.neoforged.net/releases") { name = "NeoForged" }
    }
}

rootProject.name = "SpeakerAPI"

include(":common")
include(":v1912-forge")
include(":v1201-forge")
include(":v2111-neoforge")
