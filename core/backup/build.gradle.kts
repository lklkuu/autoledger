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

    // Robolectric：org.json / Base64 在纯 JVM 的 android.jar 里是 stub，
    // 备份的 JSON 读写必须跑真实实现才有意义（否则 put/optString 全是空操作，测了等于没测）。
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
            all { it.systemProperty("robolectric.dependency.repo.url", "https://maven.aliyun.com/repository/public") }
        }
    }
}

dependencies {
    // api：BackupManager 的公开方法签名（exportJson / import 的 transform 参数）引用了
    // core:model 的 LedgerTransaction，必须 api 暴露，否则属于 API 泄漏（架构审查 A-M3）。
    api(project(":core:model"))
    // api：BackupManager 的公开构造签名直接暴露 RoomLedgerRepository（core:database 类型），
    // 使用方必须在编译期看到该类型，否则属于 API 泄漏（架构审查 A-M3）。
    api(project(":core:database"))
    implementation(project(":core:crypto"))

    implementation(libs.kotlinx.coroutines.android)

    // QA: Robolectric 运行态测试（备份 JSON 序列化 / 旧版本兼容）
    testImplementation(kotlin("test"))
    testImplementation(libs.robolectric)
}
