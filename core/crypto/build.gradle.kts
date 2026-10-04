plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.autoledger.core.crypto"
    compileSdk = 35

    defaultConfig { minSdk = 26 }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // Robolectric：AiKeyVault 的读写落在 SharedPreferences + android.util.Base64 上，
    // 两者在纯 JVM 的 android.jar 里都是 stub，不跑真实实现的话
    // save/load 会「什么都没验证」地通过（同 core:backup / core:database 的理由）。
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
            all { it.systemProperty("robolectric.dependency.repo.url", "https://maven.aliyun.com/repository/public") }
        }
    }
}

dependencies {
    api(libs.androidx.sqlite.framework)
    api(libs.sqlcipher.android)

    // SqliteHeader 是纯函数（不依赖 Android），可在 JVM 上单测，
    // 用它把「数据库是否真的加密」这个防回归判据钉死。
    testImplementation(kotlin("test"))
    // AiKeyVault 的往返 / 损坏密文护栏需要真实 SharedPreferences 与 Base64。
    testImplementation(libs.robolectric)
}
