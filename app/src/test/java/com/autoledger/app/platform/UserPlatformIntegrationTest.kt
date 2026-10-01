package com.autoledger.app.platform

import android.content.Context
import com.autoledger.core.backup.BackupManager
import com.autoledger.core.database.LedgerDatabaseFactory
import com.autoledger.core.database.repository.RoomLedgerRepository
import com.autoledger.core.model.LedgerRepository
import com.autoledger.core.model.UserPlatform
import com.autoledger.core.model.platform.PlatformCatalog
import com.autoledger.core.model.platform.PlatformContext
import com.autoledger.core.model.platform.PlatformKind
import com.autoledger.core.model.toPlatformEntry
import com.autoledger.app.ui.stores.UserPlatformDraft
import com.autoledger.feature.platform.KeywordPlatformResolver
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 自定义消费平台的**两个闭环**（Robolectric + 真实 Room 文件库 + 真实备份管线）。
 *
 * 为什么必须跑到这一层：`UserPlatformDraftTest` 只证明纯校验逻辑对、
 * `ArchivedPlatformTest` 只证明目录语义对，但**"用户加了平台、杀进程重开还在吗"**
 * 和 **"换机导入备份后还在吗"** 这两件事只有把 Room、备份、目录注入串起来才回答得了。
 * 而这两条恰恰是用户能直接感受到的（"我加的平台怎么没了"）。
 *
 * 必须跑 Robolectric 的另一个理由：`org.json` / `android.util.Base64` 在纯 JVM 的 android.jar 里是 stub，
 * 不跑真实实现的话备份编解码全是空操作 —— 测试会绿，但什么都没验证。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class UserPlatformIntegrationTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val dbName = "user-platform-integration.db"

    private fun openDb() = LedgerDatabaseFactory.create(
        context = context,
        openHelperFactory = null, // 明文：SQLCipher 原生库无法在 JVM 上加载
        databaseName = dbName,
    )

    /**
     * 与 `AppContainer.syncUserPlatformsToCatalog()` **同一语义**（这里不构造整个 AppContainer：
     * 那需要加密库与 Context 全套装配，而本测试要验证的只是"从库里读出来注入目录"这一步）。
     */
    private suspend fun syncCatalog(repo: LedgerRepository) {
        PlatformCatalog.replaceExtras(repo.listUserPlatforms(includeArchived = true).map { it.toPlatformEntry() })
    }

    /** 走真实识别引擎判断这段文本会落到哪个平台。 */
    private fun recognise(text: String): String = KeywordPlatformResolver().resolve(
        PlatformContext(rawText = text, packageName = "sms:inbox", sourceId = "sms:inbox"),
    ).platformId

    private fun jd(archived: Boolean = false) = UserPlatform(
        id = "user:jd-int", displayName = "京东", kind = PlatformKind.ORDER,
        strongKeywords = listOf("京东支付"), mediumKeywords = listOf("京东"),
        packageNames = setOf("com.jingdong.app.mall"), sortOrder = 1000, archived = archived,
    )

    private fun sams() = UserPlatform(
        id = "user:sams-int", displayName = "山姆", kind = PlatformKind.ORDER,
        mediumKeywords = listOf("山姆"), sortOrder = 1001,
    )

    /**
     * 一条**已停用**的平台，刻意用独立品牌（唯品会）而不是复用「京东」。
     *
     * 原因：若归档条目与启用条目共用关键词，「京东商城」这段文本会同时命中**启用中的**京东，
     * 断言就变成"识别到了另一个平台"，测不出"归档被跳过"这件事 ——
     * 测试数据必须让被验证的那条路径成为**唯一**解释。
     */
    private fun archivedVip() = UserPlatform(
        id = "user:vip-archived", displayName = "唯品会", kind = PlatformKind.ORDER,
        mediumKeywords = listOf("唯品会"), sortOrder = 1002, archived = true,
    )

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
        // 目录是进程级单例，不清理会污染同进程内其它用例（R5）
        PlatformCatalog.replaceExtras(emptyList())
    }

    // ------------------------------------------------------------------ ① 持久化闭环

    @Test
    fun `a custom platform survives closing and reopening the database, and is recognised again`() = runBlocking {
        // ① 第一次会话：写平台 → 注入目录 → 立刻能识别
        val db1 = openDb()
        RoomLedgerRepository(db1).also { repo ->
            repo.upsertUserPlatform(jd())
            syncCatalog(repo)
        }
        assertEquals("user:jd-int", recognise("京东支付 59.00 元"), "写成功后立刻生效，不必重启")
        db1.close()

        // ② 模拟「进程被杀」：进程内目录清空（目录是内存缓存，这是它的真实性质）
        PlatformCatalog.replaceExtras(emptyList())
        assertEquals(
            PlatformCatalog.UNKNOWN_ID,
            recognise("京东支付 59.00 元"),
            "目录清空后识别不到 —— 这正说明目录是内存态，重启后必须重新注入",
        )

        // ③ 重开库 + 重新注入（等价于 AppContainer.bootstrap() 里那一步）
        val db2 = openDb()
        val repo2 = RoomLedgerRepository(db2)
        val persisted = repo2.listUserPlatforms(includeArchived = true)
        assertEquals(listOf("user:jd-int"), persisted.map { it.id }, "重启后仍应从库里读得到")
        assertEquals(setOf("com.jingdong.app.mall"), persisted.single().packageNames, "包名不得丢")
        syncCatalog(repo2)
        assertEquals("user:jd-int", recognise("京东支付 59.00 元"), "重启后重新注入，识别恢复")
        db2.close()
    }

    // ------------------------------------------------------------------ ② 换机闭环

    @Test
    fun `a custom platform round trips through a backup, including the archived ones`() = runBlocking {
        val db1 = openDb()
        val repo1 = RoomLedgerRepository(db1)
        repo1.upsertUserPlatform(jd(archived = false))
        repo1.upsertUserPlatform(sams())
        // 停用的一条：它**必须一起进备份**，否则换机后引用它的历史流水会塌成「未知平台」
        repo1.upsertUserPlatform(archivedVip())
        val backup = BackupManager(db1, repo1).exportJson(appVersion = "test", device = "test-device")
        db1.close()

        // 换机：全新设备（库文件删掉重开）
        context.deleteDatabase(dbName)
        val db2 = openDb()
        val repo2 = RoomLedgerRepository(db2)
        syncCatalog(repo2)
        assertNull(PlatformCatalog.find("user:jd-int"), "前置条件：全新设备上本来没有这些平台")

        val outcome = BackupManager(db2, repo2).import(backup, BackupManager.MergeStrategy.REPLACE_ALL)
        assertEquals(3, outcome.userPlatformsUpserted, "三条（含那条停用的）都要导进来")
        assertEquals(
            com.autoledger.core.model.LedgerSchema.BACKUP_VERSION,
            outcome.fileVersion,
            "档案版本随 BACKUP_VERSION 一起推进",
        )

        // 导入后重建目录（BackupManager.import 内部已做过一次；这里显式再同步，语义一致且更直观）
        syncCatalog(repo2)

        assertEquals("京东", PlatformCatalog.displayNameOf("user:jd-int"))
        assertEquals("山姆", PlatformCatalog.displayNameOf("user:sams-int"))
        // 归档条目也在 —— 这是 R7 在「换机」场景下的落点
        assertEquals("唯品会", PlatformCatalog.displayNameOf("user:vip-archived"))

        // 但归档的不进识别候选与选择器
        assertTrue(
            PlatformCatalog.selectable().none { it.id == "user:vip-archived" },
            "停用的平台不该再被指派；实得=${PlatformCatalog.selectable().map { it.id }}",
        )
        // 「唯品会」这段文本只可能命中那条归档条目 ⇒ 落 unknown 就证明它确实被跳过了
        assertEquals(PlatformCatalog.UNKNOWN_ID, recognise("唯品会 12.00 元"), "停用的平台不再被识别")
        assertEquals("user:jd-int", recognise("京东支付 59.00 元"), "启用的平台照旧被识别")

        // 排序：内置在前、自定义在后
        val ids = PlatformCatalog.all().map { it.id }
        assertTrue(
            ids.indexOf("user:jd-int") > ids.indexOf("unionpay"),
            "自定义平台必须排在内置之后，实得=$ids",
        )
        db2.close()
    }

    // ------------------------------------------------------------------ ③ 校验闸门

    @Test
    fun `a platform with no signals never reaches the database`() = runBlocking {
        val db = openDb()
        val repo = RoomLedgerRepository(db)

        val candidates = listOf(
            // 只有名字、没有任何线索：永远识别不出来，必须被拦下
            UserPlatformDraft(displayName = "空壳平台", kind = PlatformKind.ORDER),
            // 只有空白的行也不算线索
            UserPlatformDraft(displayName = "空白关键词", mediumKeywords = " \n  \n"),
            // 正常的
            UserPlatformDraft(displayName = "山姆", kind = PlatformKind.ORDER, mediumKeywords = "山姆"),
        )

        // 与 UserPlatformStore.save 的短路语义一致：校验不过的直接不落库
        val accepted = candidates.filter { it.validate() == null }
        accepted.forEach { repo.upsertUserPlatform(it.toUserPlatform()) }

        val stored = repo.listUserPlatforms(includeArchived = true)
        assertEquals(listOf("山姆"), stored.map { it.displayName }, "只有通过校验的那条进了库")
        assertNotNull(stored.single().id.takeIf { it.startsWith(UserPlatform.ID_PREFIX) }, "新增走 user: 前缀 ID")
        db.close()
    }

    // ------------------------------------------------------------------ ④ 停用即刻生效

    @Test
    fun `archiving takes effect immediately without a restart`() = runBlocking {
        val db = openDb()
        val repo = RoomLedgerRepository(db)
        repo.upsertUserPlatform(jd(archived = false))
        syncCatalog(repo)
        assertEquals("user:jd-int", recognise("京东支付 59.00 元"))

        // 用户点「停用」：软删除 + 重建目录
        val current = repo.listUserPlatforms(includeArchived = true).single()
        repo.upsertUserPlatform(current.copy(archived = true))
        syncCatalog(repo)

        assertEquals(PlatformCatalog.UNKNOWN_ID, recognise("京东支付 59.00 元"), "停用后立刻不再识别")
        assertEquals("京东", PlatformCatalog.displayNameOf("user:jd-int"), "但历史流水的名称仍解析得出（R7）")
        assertEquals(1, repo.listUserPlatforms(includeArchived = true).size, "软删除：行必须还在")
        assertEquals(0, repo.listUserPlatforms(includeArchived = false).size, "但不再出现在启用列表里")
        db.close()
    }
}
