plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.autoledger.feature.classify"
    compileSdk = 35

    defaultConfig { minSdk = 26 }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // 只依赖 core:model 的契约（RuleSource / Classifier / ClassifierRule），
    // 不再依赖 Room，保证本模块是纯逻辑、可 JVM 单测、可独立演进。
    implementation(project(":core:model"))
    // ⚠️ 不要改成 implementation：本模块 main 源码**不使用**协程 ——
    //   · `suspend` 是 Kotlin **语言关键字**，编译产物是 stdlib 的 kotlin.coroutines.Continuation，
    //     与 kotlinx-coroutines-core 无关（这是 Kotlin 里最常见的依赖误判）；
    //   · 万一 main 需要协程类型（如 Flow），由 core:model 的 api 传递提供；
    //   · 只有测试直接使用 runBlocking / Flow 造夹具。
    testImplementation(libs.kotlinx.coroutines.core)

    testImplementation(kotlin("test"))
}
