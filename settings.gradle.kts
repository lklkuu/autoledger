pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "AutoLedger"

// ---------- core 层（无业务方依赖） ----------
include(":core:model")      // 纯 JVM：领域模型 + SPI 契约 + 版本号
include(":core:crypto")     // Android：Keystore / AES-GCM / SQLCipher 工厂
include(":core:database")   // Android：Room 实体 / DAO / Migration
include(":core:backup")     // Android：导出、导入、版本迁移管线

// ---------- feature 层（可插拔能力插件） ----------
include(":feature:capture")  // 采集渠道插件
include(":feature:classify") // 消费分类插件
include(":feature:dedup")    // 转账识别 + 跨渠道去重（纯 JVM）
include(":feature:stats")    // 统计维度插件
include(":feature:refund")   // 退款分摊与抵扣原路回退引擎（纯 JVM）
include(":feature:transfer") // 换机数据迁移（设备直连 + 加密重封装）

// ---------- 宿主 ----------
include(":app")
