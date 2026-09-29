package com.autoledger.app

/**
 * 捐赠渠道配置（**预留接口**）。
 *
 * ## 当前状态
 *
 * [channels] 为空 ⇒ 设置页展示「支持开发者」卡片时使用说明文案而不是收款入口。
 *
 * ## 后续接入微信 / 支付宝只需两步（无需改 UI 代码）
 *
 * 1. 把收款码图片放进 `app/src/main/res/drawable/`（例如 `donate_wechat.png`、`donate_alipay.png`）；
 * 2. 在下方 [channels] 里加一条，`qrResName` 填图片的**资源名**（不带扩展名）。
 *
 * 之后设置页会自动出现对应的收款码入口（点击弹窗展示大图）。
 *
 * ## 为什么用「资源名」而不是 Drawable 引用
 *
 * 配置对象要能被单元测试直接断言（纯 Kotlin，不依赖 Android 资源系统）；
 * 资源名到 `@DrawableRes` 的解析放在 UI 层用 `resources.getIdentifier` 完成，
 * 解析不到时该渠道自动降级为文字说明，**不会崩溃**。
 */
data class DonationChannel(
    val id: String,
    val displayName: String,
    /** 收款码图片的资源名称（如 `"donate_wechat"`）；null 表示该渠道只展示文字说明。 */
    val qrResName: String? = null,
    /** 外部跳转链接（如支付宝转账链接）；可与收款码并存。 */
    val url: String? = null,
    /** 给用户的一句提示，例如「请备注你的昵称」。 */
    val hint: String? = null,
)

object DonationConfig {

    /** 仓库地址：「关于」卡片与捐赠卡片都用它。 */
    const val GITHUB_REPO_URL = "https://github.com/lklkuu/autoledger"

    /** 应用名（与 `strings.xml` 的 `app_name` 保持一致，用于「关于」卡片与分享文案）。 */
    const val APP_DISPLAY_NAME = "打工人小账本"

    /**
     * 捐赠渠道。
     *
     * 预留的 id 约定：`wechat`（微信）、`alipay`（支付宝）。
     * 填好收款码资源名后，设置页会自动展示对应入口。
     */
    val channels: List<DonationChannel> = emptyList()

    /** 是否已配置捐赠渠道（决定设置页展示收款入口还是说明文案）。 */
    val enabled: Boolean get() = channels.isNotEmpty()
}
