// ============================================================================
// 根项目：只做跨项目约定，不应用任何 loader 插件。
// 各 loader 插件在各自子项目的 build.gradle.kts 里应用（见 settings 注释）。
// ============================================================================

plugins {
    base
}

val modVersion = providers.gradleProperty("mod_version").orNull ?: "1.0.0"

allprojects {
    group = "net.speakerapi"
    version = modVersion
}

subprojects {
    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
    }
}
