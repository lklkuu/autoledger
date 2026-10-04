plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(17)) }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core:model"))
    // ⚠️ 不要改成 implementation：本模块 main 源码**不使用**协程 ——
    //   · `suspend` 是 Kotlin **语言关键字**，编译产物是 stdlib 的 kotlin.coroutines.Continuation，
    //     与 kotlinx-coroutines-core 无关（这是 Kotlin 里最常见的依赖误判）；
    //   · 万一 main 需要协程类型（如 Flow），由 core:model 的 api 传递提供；
    //   · 只有测试直接使用 runBlocking / Flow 造夹具。
    testImplementation(libs.kotlinx.coroutines.core)

    // QA: 纯 JVM 单元测试（kotlin.test + JUnit4 运行时）
    testImplementation(kotlin("test"))
}
