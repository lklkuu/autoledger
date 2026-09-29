# 捐赠落地页（GitHub Pages）

## 这是什么

一个**纯静态**单页，用来承接 App 内「支持开发者」的二维码跳转。

```
App 内二维码（内容是本页地址）
        ↓ 用户扫码
   打开本页
        ↓
   选择微信扫码 或 点支付宝按钮跳转
```

## 为什么需要它（而不是把收款码直接打包进 App）

| | 收款码直接进 App | 落地页方案 |
|---|---|---|
| 换收款方式 | 必须发新版 App | **只改本页，所有旧版立刻生效** |
| 码失效/换绑账户 | 用户一直扫到失效码 | 改本页 1 分钟恢复 |
| 合规空间 | 个人静态码直接暴露在 App 里 | 可在本页换成经营码或加说明 |

## 部署步骤（GitHub Pages，免费）

1. 确认本目录（`docs/donate/`）已在仓库里；
2. 打开仓库 **Settings → Pages**；
3. **Source** 选 `Deploy from a branch`，**Branch** 选 `main`，目录选 **`/docs`**，保存；
4. 等 1–2 分钟，访问地址为：
   ```
   https://lklkuu.github.io/autoledger/donate/
   ```
5. 把这个地址填进 App 的 `DonationConfig`：
   ```kotlin
   DonationChannel(
       id = "web",
       displayName = "扫码支持",
       qrUrl = "https://lklkuu.github.io/autoledger/donate/",  // ← 填这里
       hint = "扫码打开支持页面",
   )
   ```

## 配置支付宝收款链接

`index.html` 里已留好位置（搜索 `支付宝链接待配置`）：

```html
<a class="btn disabled" id="alipayBtn" href="#" rel="noopener">支付宝链接待配置</a>
```

改成：

```html
<a class="btn" id="alipayBtn" href="https://qr.alipay.com/你的链接" rel="noopener">打开支付宝</a>
```

要点：
- **去掉 `disabled` 这个 class**（否则按钮点不动）；
- 改掉按钮文字；
- 页面里的脚本会自动隐藏那块占位说明，不用手动删。

> **怎么拿收钱码链接**：用任意扫码工具扫你自己的支付宝收款码，解析出来的
> `https://qr.alipay.com/xxxxxxxx` 就是它。

## 更新微信收款码

直接替换 `wechat.png` 即可（建议正方形、≥600×600）。

## ⚠️ 为什么没有「一键唤起微信扫一扫」按钮

曾经放过一个指向 `weixin://dl/scan` 的按钮，**已移除**。原因：

微信自 2020 年起**主动拦截非官方来源的 scheme 调用**。实际表现**不是"没反应"**，而是：

> 浏览器成功唤起了微信 → 微信校验来源后拒绝执行 → 弹出「**对不起，当前页面无法访问**」

也就是说，用户会撞上一个微信错误页，**比不放按钮更糟**。

个人开发者也没有合规的替代路径：

| 方案 | 为何不可行 |
|---|---|
| `weixin://` scheme | 微信主动封锁（且违反其《运营规范》） |
| 微信 JS-SDK | 只能在**微信内**的网页使用，我们在外部浏览器 |
| `wx-open-launch-weapp` | 需认证服务号 + 小程序 + 域名备案 |
| 微信 H5 支付 | 需商户资质 |
| 微信收款码链接 | 微信**没有**个人版收款链接（支付宝才有） |

因此改用微信扫一扫**原生支持**的路径：**截图保存 → 微信扫一扫 → 相册选图**。
这也是页面上「三步完成」引导的由来。

## 本地预览

```bash
python -m http.server 8000 --directory docs/donate
# 浏览器打开 http://localhost:8000
```

## 文件清单

| 文件 | 说明 |
|---|---|
| `index.html` | 落地页本体（内联 CSS，无外部依赖、无 CDN，离线可用） |
| `wechat.png` | 微信赞赏码（与 App 内 `res/drawable/donate_wechat.png` 同一张） |

## 隐私说明

本页是**纯静态**页面：不含任何分析脚本、不上报访问数据、不设置 Cookie。
唯一的外部请求是用户点击支付宝按钮时跳转到支付宝。
