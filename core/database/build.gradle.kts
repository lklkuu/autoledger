plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.autoledger.core.database"
    compileSdk = 35

    defaultConfig { minSdk = 26 }

    // Room schema 导出：app 只读这里我们暴露出来的实体类，但它需要看得到 RoomDatabase 本身，
    // 否则 app 侧引用 LedgerDatabase 时会报 cannot access supertype。所以 Room 用 api 暴露。
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    ksp { arg("room.schemaLocation", "$projectDir/schemas") }

    // Robolectric：在 JVM 上跑真实 Android framework + 真实 Room 库，
    // 用于「无需真机」的运行态验证（DAO SQL / Flow 语义 / 事务）。
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
            // Robolectric 首次运行要下载 android-all-instrumented；默认走 repo1.maven.org，
            // 在本机较慢/不可达，指定可达镜像加速。
            all { it.systemProperty("robolectric.dependency.repo.url", "https://maven.aliyun.com/repository/public") }
        }
    }
}

dependencies {
    // api：RoomLedgerRepository 实现了 core-model 里的 LedgerRepository 接口，
    // 它的继承在编译期对使用方可见，不能再藏成 implementation
    api(project(":core:model"))
    implementation(project(":core:crypto"))

    // 用 -core 而非 -android：-android 相对 -core 的**唯一增量**是提供 Dispatchers.Main
    // 的实现（以及 MainScope / Handler.asCoroutineDispatcher，经 META-INF/services 注册）。
    // 本模块 main 实测对 Dispatchers.Main / MainScope / asCoroutineDispatcher 的引用为 0，
    // 只用到 Dispatchers.IO / withContext / Flow —— 这些都在 -core 里。
    // app 仍保留 -android，故运行时行为与最终 APK 均不变。
    implementation(libs.kotlinx.coroutines.core)

    api(libs.androidx.room.runtime)
    api(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Robolectric 运行态测试
    testImplementation(kotlin("test"))
    testImplementation(libs.robolectric)
}
