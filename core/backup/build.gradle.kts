plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.autoledger.core.backup"
    compileSdk = 35

    defaultConfig { minSdk = 26 }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // api：BackupManager 的公开方法签名（exportJson / import 的 transform 参数）引用了
    // core:model 的 LedgerTransaction，必须 api 暴露，否则属于 API 泄漏（架构审查 A-M3）。
    api(project(":core:model"))
    // api：BackupManager 的公开构造签名直接暴露 RoomLedgerRepository（core:database 类型），
    // 使用方必须在编译期看到该类型，否则属于 API 泄漏（架构审查 A-M3）。
    api(project(":core:database"))
    implementation(project(":core:crypto"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
}
