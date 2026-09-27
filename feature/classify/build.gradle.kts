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
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(kotlin("test"))
}
