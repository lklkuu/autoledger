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
     * ## 当前配置
     *
     * - **微信**：走收款码图片（`donate_wechat.png`），弹窗展示让用户扫码。
     *   微信**没有**个人版远程收款链接，只能扫码，这是平台限制。
     * - **支付宝**：走「收钱码链接」直接跳转，无需图片（见下方注释模板）。
     *
     * ## 支付宝收钱码链接怎么拿
     *
     * 用任意扫码工具扫一下你自己的支付宝收款码，解析出来的
     * `https://qr.alipay.com/xxxxxxxx` 就是它 —— 直接填进 `url`。
     *
     * ## 新增自定义渠道
     *
     * 复制一条改改即可；`qrResName` 对应 `res/drawable/<名字>.png`（**只能小写字母、数字、下划线**）。
     */
    val channels: List<DonationChannel> = listOf(
        DonationChannel(
            id = "wechat",
            displayName = "微信赞赏",
            qrResName = "donate_wechat",
            hint = "长按识别或扫码，金额随意，感谢支持 ❤️",
        ),
        // 支付宝：走收钱码链接跳转（无需图片）。
        // 拿到链接后把下面三条注释解开、替换成你的地址即可（微信没有等价能力，只能扫码）。
        // DonationChannel(
        //     id = "alipay",
        //     displayName = "支付宝",
        //     url = "https://qr.alipay.com/fkx000000000000",
        //     hint = "点击后跳转支付宝完成付款",
        // ),
    )

    /** 是否已配置捐赠渠道（决定设置页展示收款入口还是说明文案）。 */
    val enabled: Boolean get() = channels.isNotEmpty()
}
