package com.autoledger.core.database.sync

import com.autoledger.core.database.SyncOutboxEntity

/**
 * 云同步预留接口（工程要求 3）。
 *
 * 设计要点：
 * 1. **本地永远是唯一真源**。写路径先落本地库 + 登记 outbox op，同步只是「尽力而为」的上行搬运。
 * 2. 接口是幂等的：同一个 opId 重复 push 必须产生同样的结果，网络重试才安全。
 * 3. 现在默认注册 [NoopCloudSyncClient]，App 不发一个字节到网络上；
 *    将来要接入 WebDAV / S3 / 自建服务，只需实现这个接口并在容器里替换一行注册代码。
 */
interface CloudSyncClient {

    val id: String

    /** 是否具备上行条件（例如用户已登录 / 已授权 / 网络可用） */
    suspend fun isReady(): Boolean

    /** 推送一批变更。返回成功消费的 opId 集合。 */
    suspend fun push(ops: List<SyncOutboxEntity>): PushResult

    /** 拉取远端变更，用于在本地重放 */
    suspend fun pull(sinceMillis: Long): PullResult

    data class PushResult(val succeededOpIds: List<String>, val failed: List<Pair<String, String>>)

    data class PullResult(val ops: List<SyncOutboxEntity>, val serverTimeMillis: Long)
}

/** 出厂默认：什么都不做，保证「不同步也能完全正常使用」。 */
object NoopCloudSyncClient : CloudSyncClient {
    override val id: String = "noop"
    override suspend fun isReady(): Boolean = false
    override suspend fun push(ops: List<SyncOutboxEntity>): CloudSyncClient.PushResult =
        CloudSyncClient.PushResult(emptyList(), emptyList())
    override suspend fun pull(sinceMillis: Long): CloudSyncClient.PullResult =
        CloudSyncClient.PullResult(emptyList(), System.currentTimeMillis())
}
