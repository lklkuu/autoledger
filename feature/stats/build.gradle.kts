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
    implementation(libs.kotlinx.coroutines.core)

    // QA: 纯 JVM 单元测试（kotlin.test + JUnit4 运行时）
    testImplementation(kotlin("test"))
}
