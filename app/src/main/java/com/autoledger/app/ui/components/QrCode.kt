package com.autoledger.app.ui.components

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * 把文本渲染成二维码 Bitmap（通常是捐赠落地页地址）。
 *
 * ## 为什么需要它
 *
 * 捐赠收款码有两种做法：
 * - **静态**：把收款码图片打包进 App —— 换收款方式必须发新版；
 * - **动态**：二维码内容指向**你能随时修改的网页**（落地页），落地页上放最新收款方式 ——
 *   换码只改网页，**所有旧版 App 立刻生效**。
 *
 * 本函数服务于后者：把落地页 URL 现场画成二维码。
 *
 * ## 参数选择理由
 *
 * - `ERROR_CORRECTION = M`（约 15% 容错）：允许二维码被轻微遮挡/拍歪/屏幕反光仍可识别，
 *   同时不会像 H 那样把码点撑得过密；
 * - `MARGIN = 1`：只留窄白边 —— 默认的 4 会让弹窗里白白浪费一圈空间，
 *   而 1 在绝大多数扫码器下仍满足静区要求；
 * - 尺寸默认 720px：xxhdpi 屏幕上约 240dp，足够清晰又不必生成巨图。
 *
 * 编码失败（内容过长、异常字符）时返回 `null`，由调用方降级为文字提示 —— **绝不抛异常**。
 */
fun qrBitmapOf(text: String, sizePx: Int = 720): Bitmap? {
    if (text.isBlank() || sizePx <= 0) return null
    return runCatching {
        val hints = mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 1,
        )
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        // 逐像素填充：二维码只有黑白两色，直接 setPixel 比再套一层画布更省内存
        for (x in 0 until sizePx) {
            for (y in 0 until sizePx) {
                bmp.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        bmp
    }.getOrNull()
}
