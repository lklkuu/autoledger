plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.autoledger.feature.ai"
    compileSdk = 35

    defaultConfig { minSdk = 26 }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // 只依赖领域层：AI 判定需要的只有 TxnType / AiMode 这两个契约。
    implementation(project(":core:model"))
    // 用 -core 而非 -android：-android 相对 -core 的**唯一增量**是提供 Dispatchers.Main
    // 的实现（以及 MainScope / Handler.asCoroutineDispatcher，经 META-INF/services 注册）。
    // 本模块 main 实测对 Dispatchers.Main / MainScope / asCoroutineDispatcher 的引用为 0，
    // 只用到 Dispatchers.IO / withContext / Flow —— 这些都在 -core 里。
    // app 仍保留 -android，故运行时行为与最终 APK 均不变。
    implementation(libs.kotlinx.coroutines.core)

    // 判定逻辑（模式门控 / 超时 / 回落 / 置信度阈值）全部是纯 JVM 可测的，不依赖 Android。
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.android)
    // 请求/响应体用 org.json 拼装 —— 纯 JVM 的 android.jar 里它是 stub，
    // 不跑 Robolectric 的话 put/optString 全是空操作，测试会「什么都没验证」地通过。
    testImplementation(libs.robolectric)
}
