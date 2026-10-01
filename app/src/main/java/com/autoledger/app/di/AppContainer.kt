package com.autoledger.app.di

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.autoledger.app.R
import com.autoledger.app.NotifyOnRecordDefault
import com.autoledger.app.UserSettings
import com.autoledger.app.ui.MainActivity
import com.autoledger.app.ui.nav.Destination
import com.autoledger.core.backup.BackupManager
import com.autoledger.core.crypto.CryptoBox
import com.autoledger.core.crypto.KeystoreKeyProvider
import com.autoledger.core.crypto.PassphraseVault
import com.autoledger.core.crypto.SqlCipherSupport
import com.autoledger.core.database.LedgerDatabase
import com.autoledger.core.database.LedgerDatabaseFactory
import com.autoledger.core.database.RoomRuleSource
import com.autoledger.core.database.repository.RoomLedgerRepository
import com.autoledger.core.database.DefaultSeed
import com.autoledger.core.database.sync.CloudSyncClient
import com.autoledger.core.database.sync.NoopCloudSyncClient
import com.autoledger.core.model.LedgerRepository
import com.autoledger.core.model.MetricProvider
import com.autoledger.core.model.RawEnvelope
import com.autoledger.feature.capture.CaptureDispatcher
import com.autoledger.feature.capture.CaptureRegistry
import com.autoledger.feature.capture.CaptureSource
import com.autoledger.feature.capture.IngestPipeline
import com.autoledger.feature.capture.bill.BillImportCaptureSource
import com.autoledger.feature.capture.manual.ManualCaptureSource
import com.autoledger.feature.capture.notify.NotificationCaptureSource
import com.autoledger.feature.capture.sms.SmsCaptureSource
import com.autoledger.feature.classify.AmountHeuristicClassifier
import com.autoledger.feature.classify.CompositeClassifier
import com.autoledger.feature.classify.CorrectionLearner
import com.autoledger.feature.classify.DefaultRulePack
import com.autoledger.feature.classify.KeywordClassifier
import com.autoledger.feature.classify.MemoryClassifier
import com.autoledger.feature.dedup.DefaultTransferDetector
import com.autoledger.feature.dedup.LedgerDuplicateResolver
import com.autoledger.feature.dedup.TransferPairMatcher
import com.autoledger.feature.stats.CategoryShareMetric
import com.autoledger.feature.stats.PlatformShareMetric
import com.autoledger.core.model.platform.PlatformCatalog
import com.autoledger.core.model.platform.PlatformResolver
import com.autoledger.core.model.toPlatformEntry
import com.autoledger.feature.platform.KeywordPlatformResolver
import com.autoledger.feature.stats.MerchantTopMetric
import com.autoledger.feature.stats.MetricRegistry
import com.autoledger.feature.stats.MonthlyTrendMetric
import com.autoledger.feature.stats.TimeCostMetric
import com.autoledger.app.notif.NotificationAccessGate
import com.autoledger.app.notif.NotificationPermissionGate
import com.autoledger.app.notif.PermissionIntroGate
import com.autoledger.app.notif.SmsAccessGate
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 依赖装配中心（手动 DI）。
 *
 * 选用手动装配而不是 Hilt： codebase 多模块 + KSP(Room) + Compose 编译链路已经够复杂，
 * 再叠 Hilt 只会增加"某两个插件版本不搭就整个工程编译不过"的概率，
 * 而这里的**装配清单本身就是一份可读性最好的架构文档** —— 谁依赖谁、可以替换谁，一目了然。
 */
class AppContainer(context: Context) {

    /** 启动错误：后台初始化失败时记录到这里，由 UI 渲染成友好错误页，而不是让进程崩溃。 */
    data class StartupError(val message: String, val cause: Throwable? = null)

    private val _startupError = MutableStateFlow<StartupError?>(null)
    val startupError: StateFlow<StartupError?> = _startupError.asStateFlow()

    /**
     * 存储状态告知（**非阻塞**）：当"放弃加密、改用明文"时，必须让用户知道，
     * 绝不再做「静默降级」——当初正是静默降级把"加密从未生效"掩盖了很久。
     */
    data class StorageNotice(val title: String, val message: String)

    private val _storageNotice = MutableStateFlow<StorageNotice?>(null)
    val storageNotice: StateFlow<StorageNotice?> = _storageNotice.asStateFlow()

    // 后台协程异常兜底：任何未捕获异常都转为可见错误，绝不带崩进程。
    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        android.util.Log.e("AutoLedger", "后台任务异常", throwable)
        _startupError.value = StartupError(throwable.message ?: "发生未知错误", throwable)
    }

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + exceptionHandler)

    /** 只保留 applicationContext，避免误持有 Activity */
    val applicationContext: Context = context.applicationContext

    // ---------------- 基础设施 ----------------
    // 惰性初始化：Keystore 密钥生成 + 口令保险箱解封都有系统级 IO，
    // 若在 Application.onCreate（主线程）里同步执行，冷启动可能 ANR、Keystore 异常时直接崩。
    private val passphraseVault by lazy { PassphraseVault(applicationContext) }
    val cryptoBox: CryptoBox by lazy { CryptoBox(KeystoreKeyProvider().masterKey()) }

    /** true 时降级为明文库。只有用户在设置页明确允许才会变 */
    @Volatile var plaintextFallback = false
        private set

    /** 当前数据库是否处于整库加密状态（供设置页展示真实的隐私状态）。 */
    @Volatile var isEncryptedAtRest: Boolean = false
        private set

    val database: LedgerDatabase by lazy { openDatabase() }

    /**
     * 打开数据库，带**明确的降级策略**（不再静默）：
     * - 已决定明文 → 直接明文库，并发布告知。
     * - 需要加密：**最多尝试 [MAX_ENCRYPTION_ATTEMPTS] 次**；
     *   每次成功后还要过「真的加密了吗」的运行期自检（防回归护栏）。
     * - 曾加密过（有旧数据）→ 任何失败都必须显式报错，绝不降级（否则旧数据读不到 = 判死刑）。
     * - 连续失败达上限 → 放弃加密、转明文，并**明确告知用户**：数据是明文存储，但原文已脱敏。
     */
    private fun openDatabase(): LedgerDatabase {
        val wantEncryption = !PassphraseVault.isPlaintextFallback(applicationContext)
        val hadEncryptedData = PassphraseVault.hasStoredPassphrase(applicationContext)

        if (!wantEncryption) {
            isEncryptedAtRest = false
            _storageNotice.value = PLAINTEXT_NOTICE
            return LedgerDatabaseFactory.create(applicationContext, null)
        }

        var lastError: Throwable? = null
        var failures = 0
        repeat(MAX_ENCRYPTION_ATTEMPTS) { attempt ->
            try {
                // 必须传 context：SQLCipher 要先 System.loadLibrary("sqlcipher")
                val factory = SqlCipherSupport.openHelperFactory(applicationContext, passphraseVault.passphrase())
                val db = LedgerDatabaseFactory.create(applicationContext, factory)

                // 防回归护栏：光"没抛异常"不算数，必须验证文件头确实被加密了
                if (SqlCipherSupport.isPlaintextDatabase(applicationContext)) {
                    throw IllegalStateException("数据库文件实际为明文 —— SQLCipher 未真正接管（疑似静默降级）")
                }

                isEncryptedAtRest = true
                PassphraseVault.clearEncryptionFailures(applicationContext)
                _storageNotice.value = null
                return db
            } catch (e: Throwable) {
                lastError = e
                failures = PassphraseVault.recordEncryptionFailure(applicationContext)
                android.util.Log.w("AutoLedger", "加密初始化失败（第 ${attempt + 1}/$MAX_ENCRYPTION_ATTEMPTS 次）", e)
                if (hadEncryptedData) {
                    // 曾经加密过：降级会读不到旧数据，必须显式报错，绝不静默破坏数据。
                    throw DatabaseUnavailableException("无法解密本地数据（数据未被改动）：${e.message}", e)
                }
            }
        }

        // 连续失败已达上限：放弃加密。但**必须明确告知用户**，不再是静默降级。
        android.util.Log.e("AutoLedger", "加密连续失败 $failures 次，放弃加密转为明文存储", lastError)
        PassphraseVault.setPlaintextFallback(applicationContext, true)
        isEncryptedAtRest = false
        _storageNotice.value = PLAINTEXT_NOTICE
        return LedgerDatabaseFactory.create(applicationContext, null)
    }

    val repository: RoomLedgerRepository by lazy { RoomLedgerRepository(database) }

    /** 退款持久化（单事务落库 + 幂等 + CAS）；订单/退款子系统入口。 */
    val refundRepository: com.autoledger.core.database.repository.RefundRepository by lazy {
        com.autoledger.core.database.repository.RefundRepository(database, repository)
    }

    /** 设置：Room 单行存储，金额以「分」存，随账本备份导出。惰性初始化避免启动期触碰数据库。 */
    val settings: UserSettings by lazy { UserSettings(database.settingsDao(), appScope).also { it.init() } }

    /** 极简导航状态；放在容器里是为了让旋转／重建 Activity 后仍停在同一屏 */
    val nav = com.autoledger.app.ui.nav.NavState()

    /** 通知使用权引导状态机：负责"何时提示、何时闭嘴" */
    val notificationAccess: NotificationAccessGate by lazy { NotificationAccessGate(applicationContext) }

    /** 短信读取权限引导（需求 1）：未授权时每次打开都提示。 */
    val smsAccess: SmsAccessGate by lazy { SmsAccessGate(applicationContext) }

    /**
     * 「通知发送」权限（`POST_NOTIFICATIONS`）引导 —— **Android 13 / API 33+** 才需要。
     * v1.1.3 只声明未请求 ⇒ 新装用户收不到「已自动记一笔账」等通知，本门控补上运行时请求。
     */
    val notifyPermission: NotificationPermissionGate by lazy { NotificationPermissionGate(applicationContext) }

    /** 首次启动权限说明（需求 2）：一次性说明各项权限用途。 */
    val permissionIntro: PermissionIntroGate by lazy { PermissionIntroGate(applicationContext) }

    // ---------------- UI 偏好（设备本地，不进备份） ----------------
    // 主题模式（夜间黑皮肤）+ 自定义背景图路径。背景图是本地文件路径，换机后失效，
    // 故用 SharedPreferences 而非 Room（不进账本备份）。
    private val uiPrefs = applicationContext.getSharedPreferences("autoledger_ui", Context.MODE_PRIVATE)
    private val _darkTheme = MutableStateFlow(uiPrefs.getBoolean("dark_theme", false))
    val darkTheme: StateFlow<Boolean> = _darkTheme.asStateFlow()
    private val _backgroundImagePath = MutableStateFlow<String?>(uiPrefs.getString("background_image", null))
    val backgroundImagePath: StateFlow<String?> = _backgroundImagePath.asStateFlow()

    /**
     * 「记账时弹通知」开关：开启后每次自动记录一笔账都发一条系统通知。设备本地偏好，不进备份。
     *
     * **默认「开」**（新用户装上就该看到「已自动记一笔账」，否则补 `POST_NOTIFICATIONS` 的收益为 0）。
     * ⚠️ 默认值的解析走三态，**绝不覆盖用户明确关掉的开关**：
     * 只有用户**拨动过**开关才会落盘 [KEY_NOTIFY_ON_RECORD]（`setNotifyOnRecord` 总是 `putBoolean`），
     * 故用 [android.content.SharedPreferences.contains] 区分「键不存在 = 从没设置过」（跟随默认 `true`）
     * 与「键存在且为 false = 用户主动关过」（保持 `false`）。见 [NotifyOnRecordDefault.resolve]。
     */
    private val _notifyOnRecord = MutableStateFlow(
        NotifyOnRecordDefault.resolve(
            stored = if (uiPrefs.contains(KEY_NOTIFY_ON_RECORD)) {
                uiPrefs.getBoolean(KEY_NOTIFY_ON_RECORD, false)
            } else {
                null
            },
        ),
    )
    val notifyOnRecord: StateFlow<Boolean> = _notifyOnRecord.asStateFlow()

    fun setDarkTheme(enabled: Boolean) {
        _darkTheme.value = enabled
        uiPrefs.edit().putBoolean("dark_theme", enabled).apply()
    }

    fun setBackgroundImagePath(path: String?) {
        _backgroundImagePath.value = path
        if (path == null) uiPrefs.edit().remove("background_image").apply()
        else uiPrefs.edit().putString("background_image", path).apply()
    }

    fun setNotifyOnRecord(enabled: Boolean) {
        _notifyOnRecord.value = enabled
        // 总是显式落盘（开 / 关都写），这样"用户主动关过"与"从没设置过"才能被 contains 区分开。
        uiPrefs.edit().putBoolean(KEY_NOTIFY_ON_RECORD, enabled).apply()
    }

    val backupManager: BackupManager by lazy { BackupManager(database, repository) }

    /** 云同步：现在是无操作的占位实现，换掉这一行即可接入真实后端 */
    val cloudSyncClient: CloudSyncClient = NoopCloudSyncClient

    // ---------------- 能力插件 ----------------
    val captureSources: List<CaptureSource> = listOf(
        NotificationCaptureSource(),
        SmsCaptureSource(),
        BillImportCaptureSource(),
        ManualCaptureSource(),
    )
    val captureRegistry = CaptureRegistry(captureSources)

    // 注：原先的 channelNames（采集方式 → 中文名）已删除。
    // 采集方式（sourceId：通知 / 短信 / 账单导入 / 手动）是**技术追溯**字段，不是业务维度；
    // 业务维度只有「消费平台」与「商户」两个，见 core:model 的 PlatformCatalog。

    // 这些装配依赖 database/repository，必须惰性：否则会在 Application 构造期（主线程）触发
    // 加密建库，Keystore/SQLCipher 任何异常都会导致"一打开就闪退"。
    /** 规则读写入口：分类引擎只依赖 RuleSource 契约，实现可替换。 */
    val ruleSource by lazy { RoomRuleSource(database.classifierRuleDao()) }

    val classifier by lazy {
        CompositeClassifier(
            listOf(
                MemoryClassifier(ruleSource),
                KeywordClassifier(ruleSource),
                AmountHeuristicClassifier(),
            )
        )
    }

    val transferDetector = DefaultTransferDetector()
    val duplicateResolver by lazy { LedgerDuplicateResolver(repository) }
    val correctionLearner by lazy { CorrectionLearner(ruleSource) }
    val pairMatcher = TransferPairMatcher

    /** 消费平台识别引擎（纯 JVM）。 */
    val platformResolver: PlatformResolver = KeywordPlatformResolver()

    val ingestPipeline: IngestPipeline by lazy {
        IngestPipeline(
            repository = repository as LedgerRepository,
            duplicateResolver = duplicateResolver,
            transferDetector = transferDetector,
            classifier = classifier,
            cryptoBox = cryptoBox,
            platformResolver = platformResolver,
        )
    }

    val metricRegistry: MetricRegistry by lazy {
        val providers: List<MetricProvider> = listOf(
            TimeCostMetric { settings.wage.value },
            CategoryShareMetric(),
            MerchantTopMetric(),
            MonthlyTrendMetric(),
            PlatformShareMetric(),
        )
        MetricRegistry(providers)
    }

    fun bootstrap() {
        // 通知采集诊断：记录监听服务状态 + 最近收到的通知，供采集箱排查「为什么没记录」
        com.autoledger.feature.capture.notify.NotificationDiag.init(applicationContext)
        appScope.launch {
            try {
                // 后台预热：在 IO 线程先把加密库与设置建好，避免 UI 首次触碰时主线程兜底加载。
                runCatching { database }
                runCatching { settings }
                // 出厂分类与规则只在首次生效：upsert 是按主键覆盖，重复启动不会累加
                repository.upsertCategories(DefaultSeed.categories())
                ruleSource.upsertRules(DefaultRulePack.rules())
                // ⚠️ 必须在 startCaptureLoop() **之前**：目录注入是"进程内缓存"，
                // 晚一步的话，采集循环启动到注入完成之间的那个窗口里到达的通知
                // 会用**不含用户自定义平台**的目录去识别，那几笔会落成 unknown（设计风险 R6）。
                syncUserPlatformsToCatalog()
                startCaptureLoop()
            } catch (e: Throwable) {
                // 初始化失败不崩进程，转为可见错误；用户可据此判断是加密库/存储问题。
                _startupError.value = StartupError(e.message ?: "初始化失败", e)
            }
        }
    }

    /**
     * 把「用户自定义消费平台」注入进程内目录（`PlatformCatalog`）。
     *
     * **含归档条目**：归档只表示「今后不再识别与指派」，历史流水的 `platformId` 仍指向它，
     * 展示时必须查得到（否则那些流水会显示「未知平台」= 用户以为数据坏了，见 R7）。
     * 「归档不进识别候选」由识别层显式跳过 `archived` 实现，不是靠不注册它。
     *
     * 用 [PlatformCatalog.replaceExtras]（原子整体替换）而不是「先清空再逐个注册」：
     * 后者存在一个「自定义平台全部消失」的窗口，而采集循环可能正在并发识别。
     */
    suspend fun syncUserPlatformsToCatalog() {
        val platforms = runCatching { repository.listUserPlatforms(includeArchived = true) }
            .getOrDefault(emptyList())
        PlatformCatalog.replaceExtras(platforms.map { it.toPlatformEntry() })
    }

    /** 供 UI 在错误页点击「重试」时调用 */
    fun retryInit() {
        if (_startupError.value != null) {
            _startupError.value = null
            bootstrap()
        }
    }

    /** 订阅采集总线：任何渠道来的信封都在这里进流水线 */
    private fun startCaptureLoop() {
        appScope.launch {
            CaptureDispatcher.subscribe { envelope ->
                val outcome = runCatching { ingestPipeline.ingest(envelope) }.getOrNull()
                // 有结果 = 确实写入了流水（自动入账 / 待确认 / 合并），按开关决定是否提醒。
                if (outcome != null) notifyRecorded(envelope)
            }
        }
    }

    /** 通知 id 自增序号，避免后一条覆盖前一条。 */
    private val recordNotifySeq = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * 「记账时弹通知」：开启后每自动记录一笔账发一条系统通知。
     *
     * 采集是后台路径：无通知权限 / 渠道创建失败一律**静默跳过**，绝不因此崩溃。
     */
    private fun notifyRecorded(envelope: RawEnvelope) {
        if (!_notifyOnRecord.value) return
        val ctx = applicationContext
        val manager = NotificationManagerCompat.from(ctx)
        if (!manager.areNotificationsEnabled()) return
        runCatching {
            ensureRecordChannel(ctx)
            val amountText = envelope.amountHint
                ?.let { "¥${"%.2f".format(kotlin.math.abs(it) / 100.0)}" }
                ?: "金额待确认"
            val who = envelope.counterpartyHint?.takeIf { it.isNotBlank() } ?: "一笔新流水"

            // 点击通知 → 打开 App 并落到「账单」页：流水可点开修正（商户/平台/金额/日期），
            // 形成「自动记账 → 核对 → 修正」的闭环，而不是点开只停在首页。
            val openIntent = Intent(ctx, MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_OPEN_DESTINATION, Destination.MONTHLY.name)
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                )
            }
            val contentIntent = PendingIntent.getActivity(
                ctx,
                0,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            val notification = NotificationCompat.Builder(ctx, RECORD_CHANNEL_ID)
                .setSmallIcon(R.drawable.notify_small_icon)
                .setLargeIcon(BitmapFactory.decodeResource(ctx.resources, R.drawable.notify_large_icon))
                .setContentTitle("已自动记一笔账")
                .setContentText("$who · $amountText · 点按查看")
                .setContentIntent(contentIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
            manager.notify(RECORD_NOTIFY_BASE_ID + recordNotifySeq.getAndIncrement(), notification)
        }
    }

    /** Android 8+ 必须先把通知渠道建好，否则 notify 会被系统静默丢弃。 */
    private fun ensureRecordChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (mgr.getNotificationChannel(RECORD_CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(
                    RECORD_CHANNEL_ID,
                    "记账提醒",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply { description = "每次自动记录一笔账时提醒" },
            )
        }
    }

    companion object {
        /** 加密初始化最多尝试次数；超过则放弃加密并明确告知用户（不再静默降级）。 */
        private const val MAX_ENCRYPTION_ATTEMPTS = 3

        /** 「记账提醒」通知渠道 id（Android 8+ 必需）。 */
        private const val RECORD_CHANNEL_ID = "autoledger_record"

        /**
         * 「记账时弹通知」开关在 [android.content.SharedPreferences] 里的键。
         * **只有用户拨动过开关才会落盘** ⇒ 用它 + `contains` 区分"从没设置过"与"主动关过"。
         */
        private const val KEY_NOTIFY_ON_RECORD = "notify_on_record"

        /** 提醒通知 id 起始值，避免与其它通知 id 冲突。 */
        private const val RECORD_NOTIFY_BASE_ID = 10_000

        /**
         * 放弃加密后的告知。措辞如实：库文件本身不加密，但通知/短信原文仍经 Keystore 密封存储。
         */
        private val PLAINTEXT_NOTICE = StorageNotice(
            title = "本机数据以明文存储",
            message = "加密初始化连续失败 3 次，已放弃加密：数据库文件本身不再加密。\n\n" +
                "但通知 / 短信原文仍经系统密钥（Keystore）密封后存储，不会以明文出现在数据库里；" +
                "商户名、金额、分类等账目字段则为明文。\n\n" +
                "建议你：重要场景下改用应用内「导出 JSON（加密）」备份，并把备份文件另行保管。",
        )
    }
}

/** 加密存储/数据库不可用。携带可读原因，供 UI 展示与排查。 */
class DatabaseUnavailableException(message: String, cause: Throwable?) : RuntimeException(message, cause)
