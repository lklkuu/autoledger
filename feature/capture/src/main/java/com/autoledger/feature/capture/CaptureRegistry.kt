package com.autoledger.feature.capture

/**
 * 渠道注册表。
 *
 * 容器在启动时把具体实现的实例塞进来；UI 遍历注册表渲染「采集渠道」卡片，
 * 所以新增渠道会自动出现在设置页，不需要改布局代码。
 */
class CaptureRegistry(private val sources: List<CaptureSource>) {

    fun all(): List<CaptureSource> = sources

    fun find(id: String): CaptureSource? = sources.firstOrNull { it.id == id }

    fun enabled(context: android.content.Context): List<CaptureSource> =
        sources.filter { it.permissionState(context) == PermissionState.GRANTED }
}
