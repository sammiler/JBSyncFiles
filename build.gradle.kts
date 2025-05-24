import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.0.0"
    id("org.jetbrains.intellij.platform") version "2.3.0" // 或者你使用的 2.x 版本
}
java {
    toolchain {
        // ★★★ 明确指定使用 Java 21 Toolchain ★★★
        languageVersion = JavaLanguageVersion.of(21)
    }
}
group = "com.example"
version = "4.1.0-SNAPSHOT"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        create("IC", "2025.1") // 目标平台
        // testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
        // bundledPlugin("com.intellij.java")
        bundledPlugin("org.jetbrains.plugins.terminal")
    }
    // implementation("...")
}

// ★★★ 添加强制 SLF4J 版本的代码块 ★★★
configurations.all {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.slf4j" && requested.name == "slf4j-api") {
            useVersion("2.0.9") // 强制使用 SLF4J 2.0.9 (或更新的 2.x 版本)
        }
    }
}
// ★★★ --- 代码块结束 --- ★★★

intellijPlatform {
    instrumentCode.set(false) // 保持禁用插桩
    pluginConfiguration {
        name = "SyncFiles"
        id = "syncfiles"
        version = project.version.toString()


        ideaVersion {
            sinceBuild = "251" // 或 "243" 根据你的需求
            untilBuild = "251.*"
            // untilBuild = "251.*" // 可省略
        }
    }
    // buildSearchableOptions = true
}

tasks {
    withType<JavaCompile> {
        sourceCompatibility = "21"
        targetCompatibility = "21"
        options.compilerArgs.add("-Xlint:deprecation")
    }
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_21)
    }
    publishPlugin {
        token.set(System.getenv("PUBLISH_TOKEN"))
    }
    // runPluginVerifier { ... }
    // publishPlugin { ... }
}