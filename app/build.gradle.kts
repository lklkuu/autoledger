import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.autoledger.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.autoledger.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "1.1.2"
    }

    // ---------------------------------------------------------------- release 签名
    // 从仓库根目录的 keystore.properties 读取（该文件已被 .gitignore 排除，绝不入库；
    // 模板见 keystore.example.properties）。
    //
    // 文件不存在或字段不全时保持「未配置签名」状态 —— CI 与刚 clone 的开发者照样能
    // 构建 debug、跑全量单测，不会因为缺少密钥而让整条构建链断掉。
    //
    // ⚠️ keystore 一旦丢失，就无法再对同一个应用做覆盖安装升级（用户只能卸载重装，
    //    本地账本数据随之丢失）。生成后请离线备份到安全的地方。
    val keystorePropsFile = rootProject.file("keystore.properties")
    val keystoreProps = Properties().apply {
        if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
    }
    val hasReleaseKeystore = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
        .all { !keystoreProps.getProperty(it).isNullOrBlank() }

    if (hasReleaseKeystore) {
        signingConfigs {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // 仅当提供了 keystore.properties 时才签名；否则产出未签名包，
            // 交由 `gradle assembleRelease` 的使用者自行签名。
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures { compose = true }

    packaging {
        resources { excludes += setOf("/META-INF/{AL2.0,LGPL2.1}") }
        // 原生库保持 AGP 默认：未压缩 + 页对齐，由系统直接从 APK 映射加载。
        //
        // 不要开 useLegacyPackaging / android:extractNativeLibs="true"：
        // 那会让 libsqlcipher.so 以 Deflate 压缩打包并依赖「安装时解压」，
        // 而 Android 14 不再解压、直接从 APK 加载，压缩的 .so 无法映射 →
        // System.loadLibrary("sqlcipher") 失败 → 加密初始化失败 → 静默降级为明文库。
        // （已在真机 Android 14 / arm64 上复现：DB 头部为 "SQLite format 3"。）
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // Robolectric：在 JVM 上跑真实 Android framework + 真实 Compose 渲染，
    // 用于「无需真机」的 UI 行为验证（渲染断言 + 真实点击）。
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
            // Robolectric 首次运行要下载 android-all-instrumented；默认走 repo1.maven.org 在本机很慢，
            // 指定可达镜像加速（与 core:database 保持一致）。
            all { it.systemProperty("robolectric.dependency.repo.url", "https://maven.aliyun.com/repository/public") }
        }
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:crypto"))
    implementation(project(":core:database"))
    implementation(project(":core:backup"))
    implementation(project(":feature:capture"))
    implementation(project(":feature:classify"))
    implementation(project(":feature:dedup"))
    implementation(project(":feature:stats"))
    implementation(project(":feature:refund"))
    implementation(project(":feature:platform"))
    implementation(project(":feature:transfer"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    // 捐赠落地页地址 → 二维码（纯 JVM 库，不引入 Android 依赖）
    implementation(libs.zxing.core)

    debugImplementation(libs.androidx.compose.ui.tooling)

    // QA: 纯 JVM 单测（R4 的月份窗口裁剪是纯函数，不需要 Robolectric/Room）
    testImplementation(kotlin("test"))

    // Robolectric + Compose UI 测试（无真机的 UI 渲染/交互验证）
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    // ui-test-manifest 提供 androidx.activity.ComponentActivity，Robolectric 版 createComposeRule 依赖它。
    // 它是**测试期**产物，按 Google 官方用法只挂 debug 变体，不进入 release。
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
