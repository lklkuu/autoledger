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
        versionCode = 7
        versionName = "1.1.5"

        // 体积优化 ③：收敛语言资源。
        // 实测（aapt2 dump configurations，收敛前）：resources.arsc 共 91 个配置，其中
        // 84 个是语言配置，每语言 85 条字符串 = 7,140 条，全部来自 androidx 的
        // 通知模板文案（call_notification_* 等）；本项目自身文案只在 values/ 默认目录
        // （88 条），没有任何 values-xx 目录。
        //
        // 开启后：语言配置 84 → 0，arsc 116,912 → 16,412 B（-100,500 B），
        // universal / arm64 / armv7 三个包各 -100,500 B。
        //
        // ⚠️ aapt2 的 -c zh 不做子语言匹配：zh-rCN / zh-rHK / zh-rTW 也一并被剔除
        //   （实测收敛后语言配置为 0）。影响面仅限 androidx 那 85 条通知模板文案回落到
        //    英文默认值；App 自身中文文案全在默认目录，不受影响。
        resourceConfigurations += setOf("zh")
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
                // 相对路径一律以**仓库根**解析（rootProject.file）：keystore.properties 本身就在根目录，
                // 写 storeFile=signing/autoledger.jks 这类相对值最直观；用 app 模块的 file() 会错误地
                // 解析成 app/signing/…（v1.1.5 发版时实证）。绝对路径写法不受影响。
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // ⚠️ debug 包同样用项目固定钥匙签名（keystore.properties 存在时）：
            // 否则 CI 每次构建用「临时生成的 debug 钥匙」签名 ⇒ APK 与历史版本签名不一致，
            // 用户无法覆盖安装升级（只能卸载重装，账本数据丢失）。
            // 本地不受影响：signing/autoledger.jks 就是本机 ~/.android/debug.keystore 的副本，
            // 与既往本地构建产物的签名完全相同（证书 EC:5A:E7…C9:6F）。
            // keystore.properties 缺失（刚 clone / 密钥未配）时回退默认 debug 签名，构建链不断。
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        release {
            // 仅当提供了 keystore.properties 时才签名；否则产出未签名包，
            // 交由 `gradle assembleRelease` 的使用者自行签名。
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }

            // ---------------------------------------------------------- R8
            // isMinifyEnabled：代码压缩 + 混淆 + 优化。本项目 dex 里 material-icons-extended
            //   一家占可归属字节的 59%（2300 个图标只用 35 个），不开 R8 就是全量打进包。
            // isShrinkResources：资源缩减，删掉没有被任何代码/资源引用的 res 条目（须与
            //   minify 同时开，否则不生效）。
            // proguardFiles：复用仓库既有 app/proguard-rules.pro（已含 Room / SQLCipher /
            //   coroutines / Compose 的 keep 规则），本阶段不新写任何规则。
            //
            // ⚠️ 配套必读：app/src/main/res/raw/keep.xml —— donate_wechat 是通过
            //   resources.getIdentifier("donate_wechat", "drawable", pkg) 按**字符串**动态解析的
            //   （DonationConfig.qrResName → CaptureAndSettings.kt），静态引用链看不到它，
            //   资源缩减会把它当成"无人引用"删掉，导致捐赠弹窗的微信收款码**静默消失**。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // ---------------------------------------------------------------- ABI 分包
    // 只为真机架构出包：SQLCipher 默认打 4 个 ABI，其中 x86 / x86_64 合计约占 APK 的
    // 34%（基线实测 11.6 MB），真机永远加载不到，纯属白打包。
    //
    // reset() 先清空默认全集，再只 include arm64-v8a + armeabi-v7a；
    // isUniversalApk = true 保留一个含全部架构的兜底包 —— 万一分发渠道没按 ABI 挑包，
    // 用户还能装得上（否则会出现「你的设备不支持此 APK」的静默失败）。
    //
    // 产物：app-arm64-v8a-release.apk / app-armeabi-v7a-release.apk /
    //      app-universal-release.apk（另有非 ABI 名的 app-release.apk 由单变体任务产出）。
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = true
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
