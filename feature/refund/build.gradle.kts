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
    // 复用领域模型（LedgerTransaction / TxnType），保证退款流水与现有账目一致
    implementation(project(":core:model"))

    // QA: 纯 JVM 单元测试（kotlin.test + JUnit4 运行时）
    testImplementation(kotlin("test"))
}
