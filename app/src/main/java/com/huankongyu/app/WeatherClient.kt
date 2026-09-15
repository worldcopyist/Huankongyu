package com.huankongyu.app

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONObject

/**
 * Free weather lookups without an API key.
 * - Coordinates → Open-Meteo
 * - City name → wttr.in (supports Chinese city names)
 */
internal object WeatherClient {

    data class Weather(
        val place: String,
        val temperatureC: Int?,
        val feelsLikeC: Int?,
        val condition: String,
        val windKmh: Int?,
        val humidityPercent: Int?,
        val source: String
    ) {
        fun summary(): String {
            val temp = temperatureC?.let { "${it}℃" } ?: "温度未知"
            val feels = feelsLikeC?.takeIf { it != temperatureC }?.let { "，体感 ${it}℃" }.orEmpty()
            val wind = windKmh?.let { "，风速约 ${it} km/h" }.orEmpty()
            val hum = humidityPercent?.let { "，湿度 $it%" }.orEmpty()
            return "$place：$condition，$temp$feels$wind$hum（数据源 $source）"
        }
    }

    suspend fun weatherFor(
        context: android.content.Context,
        cityOrNull: String?
    ): Weather {
        val city = cityOrNull?.trim()?.takeIf { it.isNotBlank() && !it.equals("null", true) }
        if (city != null) {
            val byCity = runCatching { fetchWttr(city) }
            if (byCity.isSuccess) return byCity.getOrThrow()
        }
        val loc = DeviceTools.requestBestLocationForWeather(context)
            ?: return Weather(
                place = city ?: "未知位置",
                temperatureC = null,
                feelsLikeC = null,
                condition = "无法获取",
                windKmh = null,
                humidityPercent = null,
                source = "定位失败，且未提供城市名"
            )
        return fetchOpenMeteo(loc.first, loc.second, city ?: "当前位置")
    }

    /** wttr.in JSON for a city name. */
    fun fetchWttr(city: String): Weather {
        val encoded = URLEncoder.encode(city, Charsets.UTF_8.name())
        val body = httpGet("https://wttr.in/$encoded?format=j1&lang=zh")
        val json = JSONObject(body)
        val current = json.optJSONArray("current_condition")?.optJSONObject(0)
            ?: error("天气数据格式异常")
        val area = json.optJSONArray("nearest_area")?.optJSONObject(0)
        val areaName = area?.optJSONArray("areaName")?.optJSONObject(0)?.optString("value")
            ?: city
        val tempC = current.optString("temp_C").toIntOrNull()
        val feels = current.optString("FeelsLikeC").toIntOrNull()
        val desc = current.optJSONArray("weatherDesc")?.optJSONObject(0)?.optString("value")
            ?: current.optString("weatherDesc")
        val wind = current.optString("windspeedKmph").toIntOrNull()
        val hum = current.optString("humidity").toIntOrNull()
        return Weather(
            place = areaName,
            temperatureC = tempC,
            feelsLikeC = feels,
            condition = desc.ifBlank { "未知" },
            windKmh = wind,
            humidityPercent = hum,
            source = "wttr.in"
        )
    }

    /** Open-Meteo current weather by coordinates. */
    fun fetchOpenMeteo(lat: Double, lon: Double, placeLabel: String): Weather {
        val url = "https://api.open-meteo.com/v1/forecast" +
            "?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m" +
            "&timezone=auto"
        val body = httpGet(url)
        val json = JSONObject(body)
        val current = json.optJSONObject("current") ?: error("天气数据格式异常")
        val temp = current.optDouble("temperature_2m", Double.NaN)
        val feels = current.optDouble("apparent_temperature", Double.NaN)
        val hum = current.optDouble("relative_humidity_2m", Double.NaN)
        val wind = current.optDouble("wind_speed_10m", Double.NaN)
        val code = current.optInt("weather_code", -1)
        return Weather(
            place = placeLabel,
            temperatureC = if (temp.isNaN()) null else temp.toInt(),
            feelsLikeC = if (feels.isNaN()) null else feels.toInt(),
            condition = weatherCodeToText(code),
            windKmh = if (wind.isNaN()) null else wind.toInt(),
            humidityPercent = if (hum.isNaN()) null else hum.toInt(),
            source = "Open-Meteo"
        )
    }

    fun weatherCodeToText(code: Int): String = when (code) {
        0 -> "晴"
        1 -> "大部晴朗"
        2 -> "多云"
        3 -> "阴"
        45, 48 -> "雾"
        51, 53, 55 -> "毛毛雨"
        61, 63, 65 -> "下雨"
        66, 67 -> "冻雨"
        71, 73, 75, 77 -> "下雪"
        80, 81, 82 -> "阵雨"
        85, 86 -> "阵雪"
        95, 96, 99 -> "雷暴"
        else -> "天气代码 $code"
    }

    private fun httpGet(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 16; Mobile) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36"
            )
        }
        return try {
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) error("天气服务返回 $code：${body.take(80)}")
            if (body.isBlank()) error("天气服务空响应")
            body
        } finally {
            connection.disconnect()
        }
    }
}
