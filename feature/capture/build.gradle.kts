plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.autoledger.feature.capture"
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
    implementation(project(":core:crypto"))

    // 本模块对 androidx.core 的全部用法都是 base 包的类（NotificationCompat / ContextCompat），
    // 无任何 -ktx 扩展 ⇒ 直接依赖 base 构件。
    implementation(libs.androidx.core)
    // 用 -core 而非 -android：-android 相对 -core 的**唯一增量**是提供 Dispatchers.Main
    // 的实现（以及 MainScope / Handler.asCoroutineDispatcher，经 META-INF/services 注册）。
    // 本模块 main 实测对 Dispatchers.Main / MainScope / asCoroutineDispatcher 的引用为 0，
    // 只用到 Dispatchers.IO / withContext / Flow —— 这些都在 -core 里。
    // app 仍保留 -android，故运行时行为与最终 APK 均不变。
    implementation(libs.kotlinx.coroutines.core)

    // NotificationParser 是纯逻辑（Regex + BigDecimal），可 JVM 单测
    testImplementation(kotlin("test"))
}
