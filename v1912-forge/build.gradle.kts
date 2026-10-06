// ============================================================================
// v1912-forge —— 1.19.2 + ForgeGradle 6 + Java 17
//
// 注意：这里用的是 ForgeGradle **6**（不是 1.19/1.20 MDK 常见的 FG5）。
// 原因：FG5 源码里硬校验 Gradle 版本，遇到 Gradle 8 直接抛
// "Versions Gradle 8.0 and newer are not supported. Use ForgeGradle 6 or newer"；
// 而同一聚合构建必须与 :v2111-neoforge 的 ModDevGradle 共用 Gradle 8.10.2。
//
// 产物 speakerapi-1.19.2-forge.jar 内含 :common + 本平台 sherpa native，
// 与另外两个版本的 jar 完全独立。
// ============================================================================

plugins {
    id("net.minecraftforge.gradle") version "6.0.54"
}

base.archivesName = "speakerapi-1.19.2-forge"

val minecraftVersion = providers.gradleProperty("v1912_minecraft_version").orNull ?: "1.19.2"
val forgeVersion = providers.gradleProperty("v1912_forge_version").orNull ?: "1.19.2-43.3.9"
val javaToolchainVersion = (providers.gradleProperty("v1912_java_toolchain").orNull ?: "17").toInt()

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(javaToolchainVersion))
    }
}

minecraft {
    // official = Mojang 官方映射（不同于 FG5 时代的 'snapshot'/'stable'）
    mappings("official", minecraftVersion)

    runs {
        create("client") {
            workingDirectory(project.file("run"))
            property("forge.logging.markers", "REGISTRIES")
            property("forge.logging.console.level", "debug")
            mods {
                create("speakerapi") {
                    source(sourceSets.getByName("main"))
                }
            }
        }
    }
}

repositories {
    mavenCentral()
    flatDir { dirs(rootProject.file("libs")) }
}

// ---- 按运行 OS/arch 只引一个 sherpa native（绝不打万能包）----
val osName = System.getProperty("os.name").lowercase()
val osArch = System.getProperty("os.arch").lowercase()
val nativeClassifier = when {
    osName.contains("win") && (osArch.contains("amd64") || osArch.contains("x86_64")) -> "win-x64"
    osName.contains("win") && osArch.contains("aarch64") -> "win-arm64"
    osName.contains("linux") && osArch.contains("aarch64") -> "linux-aarch64"
    osName.contains("linux") -> "linux-x64"
    osName.contains("mac") && osArch.contains("aarch64") -> "osx-aarch64"
    osName.contains("mac") -> "osx-x64"
    else -> "linux-x64"
}

val sherpaJvmJar = rootProject.file("libs/sherpa-onnx-jvm.jar")
val sherpaNativeJar = rootProject.file("libs/sherpa-onnx-native-lib-${nativeClassifier}.jar")

val commonJar: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}

dependencies {
    minecraft("net.minecraftforge:forge:${forgeVersion}")

    implementation(project(":common"))
    commonJar(project(path = ":common", configuration = "commonJar"))

    // sherpa-onnx 走本地 libs（Maven Central 无 com/k2fsa 组，JitPack 构建失败）
    implementation(files(sherpaJvmJar))
    implementation(files(sherpaNativeJar))
}

// ===== 打包：common + sherpa 平铺进来，产出自包含的独立 jar =====
tasks.named<Jar>("jar") {
    dependsOn(":common:jar")
    from({ commonJar.resolve().map(::zipTree) }) {
        exclude("META-INF/**")
    }
    from(zipTree(sherpaJvmJar)) {
        exclude("META-INF/**")
    }
    if (sherpaNativeJar.exists()) {
        from(zipTree(sherpaNativeJar)) {
            exclude("META-INF/**")
        }
    } else {
        doFirst {
            logger.warn("[SpeakerAPI] 缺少平台 native jar: ${sherpaNativeJar.name}；出声需要该 sherpa native。")
        }
    }
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
}
