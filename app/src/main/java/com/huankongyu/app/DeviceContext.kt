package com.huankongyu.app

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.icu.util.ChineseCalendar
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/** Live, permission-gated device context injected into planner and reply prompts. */
internal suspend fun buildLiveDeviceContext(context: Context): String {
    val now = ZonedDateTime.now()
    val dateText = now.format(DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm，EEEE", Locale.SIMPLIFIED_CHINESE))
    return "本机时间：$dateText（时区 ${now.zone.id}）。\n节日：${festivalFor(now)}。\n日历：${todayCalendarSummary(context, now)}。\n位置：${currentLocationSummary(context)}。"
}

private fun festivalFor(now: ZonedDateTime): String {
    val festivalNames = mutableListOf<String>()
    mapOf(
        "1-1" to "元旦", "2-14" to "情人节", "3-8" to "妇女节", "5-1" to "劳动节",
        "6-1" to "儿童节", "10-1" to "国庆节", "12-25" to "圣诞节"
    )["${now.monthValue}-${now.dayOfMonth}"]?.let(festivalNames::add)
    val lunar = ChineseCalendar().apply { timeInMillis = now.toInstant().toEpochMilli() }
    val lunarMonth = lunar.get(android.icu.util.Calendar.MONTH) + 1
    val lunarDay = lunar.get(android.icu.util.Calendar.DAY_OF_MONTH)
    mapOf(
        "1-1" to "春节", "1-15" to "元宵节", "5-5" to "端午节", "7-7" to "七夕节",
        "8-15" to "中秋节", "9-9" to "重阳节", "12-8" to "腊八节"
    )["$lunarMonth-$lunarDay"]?.let(festivalNames::add)
    val lunarText = "农历${lunarMonth}月${lunarDay}日"
    return if (festivalNames.isEmpty()) "$lunarText，今天没有识别到常见节日" else "$lunarText，${festivalNames.joinToString("、")}"
}

private fun todayCalendarSummary(context: Context, now: ZonedDateTime): String {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) !=
        PackageManager.PERMISSION_GRANTED
    ) {
        return "未授予日历读取权限"
    }
    return runCatching {
        val start = now.toLocalDate().atStartOfDay(now.zone).toInstant().toEpochMilli()
        val end = now.toLocalDate().plusDays(1).atStartOfDay(now.zone).toInstant().toEpochMilli()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also { builder ->
            ContentUris.appendId(builder, start)
            ContentUris.appendId(builder, end)
        }.build()
        context.contentResolver.query(
            uri,
            arrayOf(
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.EVENT_LOCATION
            ),
            null,
            null,
            "${CalendarContract.Instances.BEGIN} ASC"
        )?.use { cursor ->
            buildList {
                while (cursor.moveToNext() && size < 5) {
                    val title = cursor.getString(0).orEmpty().ifBlank { "未命名日程" }
                    val time = Instant.ofEpochMilli(cursor.getLong(1)).atZone(now.zone)
                        .format(DateTimeFormatter.ofPattern("HH:mm"))
                    val place = cursor.getString(2)?.takeIf { it.isNotBlank() }?.let { "，地点：$it" }.orEmpty()
                    add("$time $title$place")
                }
            }
        }?.ifEmpty { listOf("今天没有读取到日程") }?.joinToString("；") ?: "今天没有读取到日程"
    }.getOrElse { "日历读取失败：${it.message?.take(80) ?: "未知错误"}" }
}

@Suppress("MissingPermission")
private suspend fun currentLocationSummary(context: Context): String {
    val hasCoarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED
    val hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED
    if (!hasCoarse && !hasFine) return "未授予位置权限"
    val manager = context.getSystemService(LocationManager::class.java) ?: return "位置服务不可用"
    val fresh = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        withTimeoutOrNull(5_000) {
            suspendCancellableCoroutine<Location?> { continuation ->
                val provider = buildList {
                    if (manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) add(LocationManager.NETWORK_PROVIDER)
                    if (hasFine && manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) add(LocationManager.GPS_PROVIDER)
                    if (manager.isProviderEnabled(LocationManager.PASSIVE_PROVIDER)) add(LocationManager.PASSIVE_PROVIDER)
                }.firstOrNull()
                if (provider == null) {
                    continuation.resume(null)
                } else {
                    manager.getCurrentLocation(provider, null, context.mainExecutor) { location ->
                        if (continuation.isActive) continuation.resume(location)
                    }
                }
            }
        }
    } else {
        null
    }
    val location = fresh ?: manager.getProviders(true)
        .mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
        .maxByOrNull { it.time }
        ?: return "暂未获取到当前位置，请打开系统定位服务后再试"
    val place = runCatching {
        Geocoder(context, Locale.SIMPLIFIED_CHINESE)
            .getFromLocation(location.latitude, location.longitude, 1)
            ?.firstOrNull()
            ?.let { address ->
                listOfNotNull(address.adminArea, address.locality, address.subLocality, address.thoroughfare)
                    .distinct().joinToString("")
            }
    }.getOrNull()?.takeIf { it.isNotBlank() }
    val coordinates = "${"%.5f".format(Locale.US, location.latitude)}, ${"%.5f".format(Locale.US, location.longitude)}"
    return place?.let { "$it（坐标 $coordinates）" } ?: "坐标 $coordinates"
}
