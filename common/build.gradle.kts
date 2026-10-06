// ============================================================================
// common —— 纯 API / 引擎层
//
// 硬性约束：本项目的源码不得出现
//   import net.minecraft.*        (含 net.minecraft.client / world / core…)
//   import net.minecraftforge.*
//   import net.neoforged.*
// 一切版本相关能力通过 net.speakerapi.core.Platform 由版本子项目注入。
//
// 为什么要 Java 17：1.19.2 / 1.20.1 跑在 JRE 17 上，common 作为被嵌入的字节码
// 必须用最低版本编译，否则 Java 21 产出的 class file (65.0) 在 J17 上直接
// UnsupportedClassVersionError。
// ============================================================================

plugins {
    id("java-library")
}

base.archivesName = "speakerapi-common"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // sherpa-onnx JVM API：仅用于编译 common。
    // 运行期由各版本子项目把同一个 jar 平铺进自己的 mod jar 提供（不打散依赖）。
    compileOnly(files(rootProject.file("libs/sherpa-onnx-jvm.jar")))
}

tasks.withType<Jar>().configureEach {
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
}

// ---------------------------------------------------------------------------
// 对外暴露自己的 jar 作为 "commonJar" 工件；各版本子项目通过 named configuration
// 依赖它，在自己的 jar 任务里 zipTree 平铺进去（三个 jar 各自独立、互不合并）。
// ---------------------------------------------------------------------------
val commonJar: Configuration by configurations.creating {
    isCanBeConsumed = true
    isCanBeResolved = false
}

artifacts {
    add(commonJar.name, tasks.jar)
}
