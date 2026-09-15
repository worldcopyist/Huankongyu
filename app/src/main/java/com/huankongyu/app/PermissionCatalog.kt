package com.huankongyu.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/** How scary a runtime permission looks to the user. */
enum class PermissionRisk(val label: String) {
    Low("低风险"),
    Medium("中风险"),
    High("高风险")
}

data class AppPermission(
    val permission: String,
    val title: String,
    val risk: PermissionRisk,
    /** Why the companion / tools need it — shown in the pre-request dialog. */
    val reason: String,
    /** Device tool names that become usable after grant. */
    val tools: List<String>
)

/**
 * Runtime permissions the app uses, ordered for a guided auto-request flow.
 * INTERNET / package visibility are install-time and not listed here.
 */
object PermissionCatalog {

    fun all(): List<AppPermission> {
        val media = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            listOf(
                AppPermission(
                    Manifest.permission.READ_MEDIA_IMAGES,
                    "照片与视频",
                    PermissionRisk.Medium,
                    "用于更换头像，以及在你明确要求时统计相册大致数量。",
                    listOf("device_count_media_images")
                )
            )
        } else {
            listOf(
                AppPermission(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    "存储空间",
                    PermissionRisk.Medium,
                    "用于更换头像与读取相册图片。",
                    listOf("device_count_media_images")
                )
            )
        }
        val visualSelected = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            listOf(
                AppPermission(
                    Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
                    "仅访问所选照片",
                    PermissionRisk.Low,
                    "Android 14+ 允许你只授权部分照片，降低隐私暴露。",
                    emptyList()
                )
            )
        } else emptyList()

        return buildList {
            add(
                AppPermission(
                    Manifest.permission.POST_NOTIFICATIONS,
                    "通知",
                    PermissionRisk.Low,
                    "用于在角色回复、任务完成时提醒你。",
                    listOf("device_get_notification_permission")
                )
            )
            add(
                AppPermission(
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    "大致位置",
                    PermissionRisk.Medium,
                    "用于在你询问「在哪」时回答大致位置。",
                    listOf("device_get_location")
                )
            )
            add(
                AppPermission(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    "精确位置",
                    PermissionRisk.High,
                    "用于坐标级定位。仅在你明确需要精确定位时使用，不会持续上传。",
                    listOf("device_get_location")
                )
            )
            add(
                AppPermission(
                    Manifest.permission.READ_CALENDAR,
                    "日历",
                    PermissionRisk.High,
                    "用于在你问「今天有什么安排」时读取本机日程摘要。",
                    listOf("device_get_calendar_today")
                )
            )
            add(
                AppPermission(
                    Manifest.permission.CAMERA,
                    "相机",
                    PermissionRisk.High,
                    "用于确认前后摄像头是否可用；不会在你不知情时拍照。",
                    listOf("device_list_cameras", "device_camera_status")
                )
            )
            addAll(media)
            addAll(visualSelected)
        }
    }

    fun missing(context: Context): List<AppPermission> =
        all().filter {
            ContextCompat.checkSelfPermission(context, it.permission) != PackageManager.PERMISSION_GRANTED
        }

    fun missingPermissions(context: Context): Array<String> =
        missing(context).map { it.permission }.toTypedArray()

    fun isGranted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
