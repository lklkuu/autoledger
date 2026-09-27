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
    implementation(project(":core:model"))
    // api：BackupManager 的公开构造签名直接暴露 RoomLedgerRepository（core:database 类型），
    // 使用方必须在编译期看到该类型，否则属于 API 泄漏（架构审查 A-M3）。
    api(project(":core:database"))
    implementation(project(":core:crypto"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
}
