plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(17)) }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xjvm-default=all")
    }
}

dependencies {
    // api：LedgerRepository 的公开契约 observeSince/observeRawCount 直接返回 Flow，
    // 使用方（core:database / feature:* / app）在编译期必须能解析 kotlinx-coroutines 类型，
    // 继续用 implementation 会造成 API 泄漏（编译期提示 cannot access class ...Flow）。
    api(libs.kotlinx.coroutines.core)

    // TxnExtras 用 JSON 承载流水扩展属性（标签等）。
    // 仅用 JsonElement API（parseToJsonElement/buildJsonObject），无需 @Serializable 编译器插件；
    // 纯 Kotlin 实现，JVM 与 Android 通用，避免 org.json 在单测里被 android.jar stub 的坑。
    // 类型不出现在公开签名里，故用 implementation（非 api）。
    implementation(libs.kotlinx.serialization.json)

    // QA: 纯 JVM 单元测试（kotlin.test + JUnit4 运行时）
    testImplementation(kotlin("test"))
}
