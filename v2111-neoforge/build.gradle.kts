// ============================================================================
// v2111-neoforge —— 1.21.1 + ModDevGradle + Java 21
//
// 约定：本子项目只写「适配层」。产物 speakerapi-1.21.1-neoforge.jar 里会平铺
// :common 的 classes/resources 与本平台 sherpa native，因此它是完全独立的 jar，
// 与 v1912 / v1201 的 jar 互不依赖、互不合并。
// ============================================================================

plugins {
    id("net.neoforged.moddev") version "1.0.11"
}

base.archivesName = "speakerapi-1.21.1-neoforge"

val minecraftVersion = providers.gradleProperty("v2111_minecraft_version").orNull ?: "1.21.1"
val neoForgeVersion = providers.gradleProperty("v2111_neoforge_version").orNull ?: "21.1.251"
val javaToolchainVersion = (providers.gradleProperty("v2111_java_toolchain").orNull ?: "21").toInt()

repositories {
    mavenCentral()
    flatDir { dirs(rootProject.file("libs")) }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(javaToolchainVersion))
    }
}

neoForge {
    version = neoForgeVersion

    runs {
        register("client") {
            client()
        }
    }

    mods {
        register("speakerapi") {
            sourceSet(sourceSets["main"])
        }
    }
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

// common 的 jar 作为独立嵌入来源（编译时 + 打包时两条需求分开声明）
val commonJar: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}

dependencies {
    implementation(project(":common"))
    commonJar(project(path = ":common", configuration = "commonJar"))

    // sherpa-onnx 走本地 libs（Maven Central 无 com/k2fsa 组，JitPack 构建失败）
    implementation(files(sherpaJvmJar))
    implementation(files(sherpaNativeJar))
}

// ===== 打包：common + sherpa 平铺进来，产出自包含的独立 jar =====
tasks.named<Jar>("jar") {
    // common 的 jar 是本任务的输入之一，必须显式声明依赖
    dependsOn(":common:jar")
    // (1) common 的 classes + resources（含默认配置文件模板）
    from({ commonJar.resolve().map(::zipTree) }) {
        exclude("META-INF/**")
    }
    // (2) sherpa JVM API
    from(zipTree(sherpaJvmJar)) {
        exclude("META-INF/**")
    }
    // (3) 本平台 sherpa native 资源（缺失时只告警不失败）
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
