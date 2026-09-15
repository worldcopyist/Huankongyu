package com.huankongyu.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/** One planner-requested call into an app-owned device capability. */
data class DeviceToolCall(
    val name: String,
    val argumentsJson: String = "{}"
)

/**
 * Built-in device tools the planner can request. These run on-device with the
 * user's granted permissions — never through an external server.
 */
object DeviceTools {

    /** Catalog advertised to the planner (name → description + args schema). */
    fun catalog(): List<String> = listOf(
        "device_get_location：获取手机当前定位。参数 {}。需要位置权限。返回纬度、经度、粗略地址与定位方式；未授权时说明需要用户在设置中开启位置权限，不要编造坐标。",
        "device_get_battery：读取电量与充电状态。参数 {}。无需额外运行时权限，任何情况下都可调用。",
        "device_list_cameras：列出前后置摄像头及是否可用。参数 {}。无需相机权限即可列举；若需说明能否拍照会检查相机权限。",
        "device_camera_status：检查相机权限与前后摄像头硬件是否可用。参数 {}。",
        "device_get_device_info：机型、系统版本、Shizuku 概况。参数 {}。无需额外权限。",
        "device_get_calendar_today：读取今天日程摘要（最多 5 条）。参数 {}。需要日历权限；未授权时如实说明。",
        "device_get_notification_permission：查询通知权限是否已授予。参数 {}。",
        "device_count_media_images：统计本机相册图片大致数量。参数 {}。需要存储/照片权限；未授权时说明原因。",
        "device_permission_status：汇总本应用当前已授予/未授予的系统权限清单。参数 {}。",
        "device_get_weather：查询天气。参数 {\"city\":\"城市名可选\"}。有城市名用城市，否则用手机定位；优先用此工具，不要只靠 web_search 查天气。"
    )

    fun knownNames(): Set<String> = setOf(
        "device_get_location",
        "device_get_battery",
        "device_list_cameras",
        "device_camera_status",
        "device_get_device_info",
        "device_get_calendar_today",
        "device_get_notification_permission",
        "device_count_media_images",
        "device_permission_status",
        "device_get_weather"
    )

    /** Parses planner `device_tool_calls` entries; ignores unknown tools. */
    fun parseCalls(raw: String?): List<DeviceToolCall> {
        if (raw.isNullOrBlank()) return emptyList()
        val array = runCatching {
            org.json.JSONArray(
                if (raw.trimStart().startsWith("[")) raw else "[$raw]"
            )
        }.getOrNull() ?: return emptyList()
        val calls = mutableListOf<DeviceToolCall>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val name = obj.optString("name").trim()
            if (name !in knownNames()) continue
            val args = obj.opt("arguments")
            val argsJson = when (args) {
                null, org.json.JSONObject.NULL -> "{}"
                is org.json.JSONObject -> args.toString()
                is String -> args.ifBlank { "{}" }
                else -> "{}"
            }
            calls += DeviceToolCall(name, argsJson)
        }
        return calls.take(3)
    }

    /** Runs one tool and returns a short factual string for the reply model. */
    suspend fun execute(context: Context, call: DeviceToolCall): String {
        return runCatching {
            when (call.name) {
                "device_get_location" -> getLocationSummary(context)
                "device_get_battery" -> getBatterySummary(context)
                "device_list_cameras" -> listCamerasSummary(context)
                "device_camera_status" -> cameraStatusSummary(context)
                "device_get_device_info" -> getDeviceInfoSummary(context)
                "device_get_calendar_today" -> getCalendarTodaySummary(context)
                "device_get_notification_permission" -> notificationPermissionSummary(context)
                "device_count_media_images" -> countMediaImagesSummary(context)
                "device_permission_status" -> permissionStatusSummary(context)
                "device_get_weather" -> getWeatherSummary(context, call.argumentsJson)
                else -> "未知设备工具：${call.name}"
            }
        }.getOrElse { "设备工具 ${call.name} 执行失败：${it.message?.take(100) ?: "未知错误"}" }
    }

    suspend fun getLocationSummary(context: Context): String {
        val hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!hasFine && !hasCoarse) {
            return "定位失败：未授予位置权限。请在系统设置中允许本应用使用定位后重试。"
        }
        val manager = context.getSystemService(LocationManager::class.java)
            ?: return "定位失败：系统位置服务不可用。"

        val location = requestBestLocation(context, manager, hasFine)
            ?: return "定位失败：暂时没有拿到坐标。请打开系统定位，并到室外或窗边稍后再试。"

        val coords = "%.5f,%.5f".format(java.util.Locale.US, location.latitude, location.longitude)
        val accuracy = if (location.hasAccuracy()) "，精度约 ${location.accuracy.toInt()} 米" else ""
        val provider = location.provider ?: "unknown"
        val place = runCatching {
            android.location.Geocoder(context, java.util.Locale.SIMPLIFIED_CHINESE)
                .getFromLocation(location.latitude, location.longitude, 1)
                ?.firstOrNull()
                ?.let { addr ->
                    listOfNotNull(addr.adminArea, addr.locality, addr.subLocality, addr.thoroughfare)
                        .filter { it.isNotBlank() }.distinct().joinToString("")
                }
        }.getOrNull()?.takeIf { it.isNotBlank() }
        return if (place != null) {
            "当前位置：$place（坐标 $coords，来源 $provider$accuracy）"
        } else {
            "当前位置坐标 $coords（来源 $provider$accuracy）"
        }
    }

    /** Exposed for WeatherClient: returns (lat, lon) or null. */
    suspend fun requestBestLocationForWeather(context: Context): Pair<Double, Double>? {
        val hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!hasFine && !hasCoarse) return null
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        val location = requestBestLocation(context, manager, hasFine) ?: return null
        return location.latitude to location.longitude
    }

    suspend fun getWeatherSummary(context: Context, argumentsJson: String): String {
        val city = runCatching {
            val args = org.json.JSONObject(argumentsJson.ifBlank { "{}" })
            args.optString("city").trim().takeIf { it.isNotBlank() && !it.equals("null", true) }
                ?: extractCityFromText(args.optString("user_message"))
        }.getOrNull()
        return runCatching {
            WeatherClient.weatherFor(context, city).summary()
        }.getOrElse {
            "天气查询失败：${it.message?.take(120) ?: "未知错误"}。不要编造天气；可建议用户允许位置权限，或直接说出城市名。"
        }
    }

    /** Very small city extractor for Chinese weather questions. */
    fun extractCityFromText(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val cities = listOf(
            "北京", "上海", "广州", "深圳", "杭州", "成都", "重庆", "武汉", "西安", "南京",
            "天津", "苏州", "青岛", "长沙", "郑州", "厦门", "昆明", "大连", "哈尔滨", "沈阳",
            "香港", "澳门", "台北"
        )
        return cities.firstOrNull { text.contains(it) }
            ?: Regex("([\\u4e00-\\u9fa5]{1,4})(?:市|县|区)?(?:今天|现在|的)?(?:天气|气温|热不热|冷不冷)")
                .find(text)?.groupValues?.get(1)
    }

    private suspend fun requestBestLocation(
        context: Context,
        manager: LocationManager,
        hasFine: Boolean
    ): android.location.Location? {
        // 1) Last known from every provider — often enough for weather and much more reliable
        //    than getCurrentLocation on MIUI/HyperOS, which frequently returns null.
        @Suppress("MissingPermission")
        val lastKnown = manager.allProviders
            .filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
            .mapNotNull { p -> runCatching { manager.getLastKnownLocation(p) }.getOrNull() }
            .maxByOrNull { it.time }

        // 2) Try a short fresh fix; keep last known if GPS never wakes up.
        val fresh = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val providers = buildList {
                if (manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) add(LocationManager.NETWORK_PROVIDER)
                if (hasFine && manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) add(LocationManager.GPS_PROVIDER)
                if (manager.isProviderEnabled(LocationManager.PASSIVE_PROVIDER)) add(LocationManager.PASSIVE_PROVIDER)
            }
            var result: android.location.Location? = null
            for (provider in providers) {
                val loc = withTimeoutOrNull(4_000) {
                    suspendCancellableCoroutine<android.location.Location?> { cont ->
                        try {
                            manager.getCurrentLocation(provider, null, context.mainExecutor) { l ->
                                if (cont.isActive) cont.resume(l)
                            }
                        } catch (t: Throwable) {
                            if (cont.isActive) cont.resume(null)
                        }
                    }
                }
                if (loc != null) {
                    result = loc
                    break
                }
            }
            result
        } else {
            null
        }

        val chosen = when {
            fresh != null && lastKnown != null ->
                if (fresh.time >= lastKnown.time) fresh else lastKnown
            fresh != null -> fresh
            else -> lastKnown
        } ?: return null

        // Weather / chat only need approximate coords; 24h-old last-known is still useful.
        return chosen
    }

    fun getBatterySummary(context: Context): String {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val sticky = context.registerReceiver(null, filter) ?: return "无法读取电量信息"
        val level = sticky.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = sticky.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val status = sticky.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val plugged = sticky.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val pct = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL ||
            plugged != 0
        val pctText = if (pct >= 0) "$pct%" else "未知"
        val chargeText = if (charging) "正在充电" else "未在充电"
        return "电量 $pctText，$chargeText"
    }

    fun listCamerasSummary(context: Context): String {
        val manager = context.getSystemService(CameraManager::class.java)
            ?: return "无法访问摄像头服务"
        val ids = runCatching { manager.cameraIdList }.getOrDefault(emptyArray())
        if (ids.isEmpty()) return "设备没有可用的摄像头"
        val parts = ids.map { id ->
            val facing = runCatching {
                manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)
            }.getOrNull()
            val label = when (facing) {
                CameraCharacteristics.LENS_FACING_FRONT -> "前置"
                CameraCharacteristics.LENS_FACING_BACK -> "后置"
                CameraCharacteristics.LENS_FACING_EXTERNAL -> "外接"
                else -> "未知朝向"
            }
            "$label(id=$id)"
        }
        val cameraPerm = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        return "摄像头：${parts.joinToString("、")}；相机权限" +
            (if (cameraPerm) "已授予，可拍摄" else "未授予，目前只能描述设备有无摄像头，不能拍照")
    }

    fun getDeviceInfoSummary(context: Context): String {
        val shizuku = com.huankongyu.app.shizuku.ShizukuClient.deviceContextLine()
        return "机型 ${Build.MANUFACTURER} ${Build.MODEL}，Android ${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）。$shizuku"
    }

    fun cameraStatusSummary(context: Context): String {
        val perm = PermissionCatalog.isGranted(context, Manifest.permission.CAMERA)
        val cams = runCatching {
            context.getSystemService(CameraManager::class.java)?.cameraIdList?.size ?: 0
        }.getOrDefault(0)
        return if (perm) {
            "相机权限已授予；设备检测到 $cams 个摄像头。"
        } else {
            "相机权限未授予。硬件有约 $cams 个摄像头，但目前不能打开或拍照。请用户在系统设置中允许「相机」。"
        }
    }

    fun notificationPermissionSummary(context: Context): String {
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            PermissionCatalog.isGranted(context, Manifest.permission.POST_NOTIFICATIONS)
        } else {
            true
        }
        return if (granted) "通知权限已授予。" else "通知权限未授予，角色回复时可能不会弹出系统通知。"
    }

    suspend fun getCalendarTodaySummary(context: Context): String {
        if (!PermissionCatalog.isGranted(context, Manifest.permission.READ_CALENDAR)) {
            return "日历权限未授予，无法读取今天的日程。请用户允许「日历」权限后再试，不要编造安排。"
        }
        return todayCalendarBrief(context)
    }

    private suspend fun todayCalendarBrief(context: Context): String {
        val now = java.time.ZonedDateTime.now()
        val start = now.toLocalDate().atStartOfDay(now.zone).toInstant().toEpochMilli()
        val end = now.toLocalDate().plusDays(1).atStartOfDay(now.zone).toInstant().toEpochMilli()
        val uri = android.provider.CalendarContract.Instances.CONTENT_URI.buildUpon().also { b ->
            android.content.ContentUris.appendId(b, start)
            android.content.ContentUris.appendId(b, end)
        }.build()
        return runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(
                    android.provider.CalendarContract.Instances.TITLE,
                    android.provider.CalendarContract.Instances.BEGIN
                ),
                null, null, "${android.provider.CalendarContract.Instances.BEGIN} ASC"
            )?.use { c ->
                val items = buildList {
                    while (c.moveToNext() && size < 5) {
                        val title = c.getString(0).orEmpty().ifBlank { "未命名日程" }
                        val t = java.time.Instant.ofEpochMilli(c.getLong(1)).atZone(now.zone)
                            .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
                        add("$t $title")
                    }
                }
                if (items.isEmpty()) "今天没有读取到日程。" else "今天的日程：${items.joinToString("；")}"
            } ?: "今天没有读取到日程。"
        }.getOrElse { "日历读取失败：${it.message?.take(80) ?: "未知错误"}" }
    }

    fun countMediaImagesSummary(context: Context): String {
        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            PermissionCatalog.isGranted(context, Manifest.permission.READ_MEDIA_IMAGES) ||
                PermissionCatalog.isGranted(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        } else {
            PermissionCatalog.isGranted(context, Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        if (!ok) {
            return "照片权限未授予，无法统计相册。请用户允许「照片」权限后再试。"
        }
        return runCatching {
            var count = 0
            context.contentResolver.query(
                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(android.provider.MediaStore.Images.Media._ID),
                null, null, null
            )?.use { c -> count = c.count }
            "本机相册大约有 $count 张图片。"
        }.getOrElse { "统计相册失败：${it.message?.take(80) ?: "未知错误"}" }
    }

    fun permissionStatusSummary(context: Context): String {
        val all = PermissionCatalog.all()
        val granted = all.filter { PermissionCatalog.isGranted(context, it.permission) }
        val denied = all - granted.toSet()
        val g = granted.joinToString("、") { it.title }.ifBlank { "无" }
        val d = denied.joinToString("、") { "${it.title}（${it.risk.label}）" }.ifBlank { "无" }
        return "已授权：$g。未授权：$d。电量等无需运行时权限的能力始终可用。"
    }
}
