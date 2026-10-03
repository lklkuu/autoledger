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
}

dependencies {
    api(libs.androidx.sqlite.framework)
    api(libs.sqlcipher.android)

    // SqliteHeader 是纯函数（不依赖 Android），可在 JVM 上单测，
    // 用它把「数据库是否真的加密」这个防回归判据钉死。
    testImplementation(kotlin("test"))
}
