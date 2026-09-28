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
    // 只依赖领域层：平台类型是 core:model 的契约，识别引擎是可插拔能力。
    // 刻意**不依赖 core:database**（守住 A1 架构约束，与 feature:dedup 同构）。
    implementation(project(":core:model"))
    implementation(libs.kotlinx.coroutines.core)

    // QA: 纯 JVM 单元测试（kotlin.test + JUnit4 运行时）
    testImplementation(kotlin("test"))
}
