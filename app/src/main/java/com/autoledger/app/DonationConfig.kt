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
    /** 收款码图片的资源名称（如 `"donate_wechat"`）；null 表示该渠道不展示静态收款码。 */
    val qrResName: String? = null,
    /**
     * **动态二维码**：把这个地址现场生成二维码展示给用户扫。
     *
     * 与 [qrResName] 的区别：二维码内容指向**你能随时修改的网页**（如捐赠落地页），
     * 而不是收款码本身 —— 换收款方式只改网页，**所有旧版 App 立刻生效**，不必发新版。
     */
    val qrUrl: String? = null,
    /** 外部跳转链接（如支付宝收钱码链接、捐赠落地页）；留空则该渠道只展示二维码。 */
    val url: String? = null,
    /**
     * 点击渠道按钮后，在弹窗里**展示可点击的链接**（而不是直接跳出 App、也不显示二维码图片）。
     *
     * 用于「扫码支持 → 落地页」这类场景：让用户先看到地址、自己决定何时跳转，
     * 避免"点一下就跳出 App"的突兀感。
     */
    val showLinkInDialog: Boolean = false,
    /** 给用户的一句提示，例如「长按识别或扫码」。 */
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
        // 【主入口】捐赠落地页 —— **不以图片形式展示**：
        // 弹窗里直接给出链接，由用户点击后用**默认浏览器**打开。
        // 落地页上放着当前有效的收款方式，换码只需改网页，旧版 App 依然有效。
        DonationChannel(
            id = "web",
            displayName = "扫码支持",
            url = "https://lklkuu.github.io/autoledger/donate/",
            showLinkInDialog = true,
            hint = "点击链接用浏览器打开，页内含微信与支付宝收款方式",
        ),
        // 【快捷方式】直达微信收款码（少一次点击；若该码失效请优先改落地页）
        DonationChannel(
            id = "wechat",
            displayName = "微信赞赏",
            qrResName = "donate_wechat",
            hint = "长按识别或扫码，金额随意，感谢支持 ❤️",
        ),
        // 【快捷方式】直达支付宝付款页（微信没有个人版远程收款链接，只能扫码）
        DonationChannel(
            id = "alipay",
            displayName = "支付宝",
            url = "https://qr.alipay.com/fkx15892qu3jnvvmrnvyif0",
            hint = "点击后跳转支付宝完成付款",
        ),
    )

    /** 是否已配置捐赠渠道（决定设置页展示收款入口还是说明文案）。 */
    val enabled: Boolean get() = channels.isNotEmpty()
}
