package com.huankongyu.app

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONArray

/**
 * App-owned implementation of the planner's web_search tool.
 * Multi-engine fallback (no API key): Bing → DuckDuckGo (POST) → 百度 → 维基百科.
 */
internal object WebSearchClient {

    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 16; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    fun search(query: String): WebSearchResponse {
        val normalizedQuery = normalizeWebSearchQuery(query) ?: error("网页查询语句为空")
        val failures = mutableListOf<String>()

        val engines: List<Pair<String, () -> List<WebSearchResult>>> = listOf(
            "Bing" to { searchBing(normalizedQuery) },
            "DuckDuckGo" to { searchDuckDuckGo(normalizedQuery) },
            "百度" to { searchBaidu(normalizedQuery) },
            "维基百科" to { searchWikipedia(normalizedQuery) }
        )

        engines.forEach { (engine, search) ->
            runCatching { search() }
                .onSuccess { results ->
                    if (results.isNotEmpty()) return WebSearchResponse(engine, results.take(4))
                    failures += "$engine：无结果"
                }
                .onFailure { error ->
                    failures += "$engine：${error.message?.take(80) ?: "请求失败"}"
                }
        }
        error(
            "所有搜索源均失败。" + failures.joinToString("；") +
                "。可能是网络受限、搜索页改版或需要验证码。"
        )
    }

    fun formatContext(query: String, response: WebSearchResponse): String = buildString {
        appendLine("已通过 ${response.engine} 查询「$query」，得到以下公开网页资料：")
        response.results.forEachIndexed { index, result ->
            appendLine("[${index + 1}] ${result.title}")
            if (result.snippet.isNotBlank()) appendLine("摘要：${result.snippet.take(320)}")
            appendLine("链接：${result.url}")
        }
    }.trim()

    private fun searchBing(query: String): List<WebSearchResult> {
        val encoded = URLEncoder.encode(query, Charsets.UTF_8.name())
        val urls = listOf(
            "https://cn.bing.com/search?q=$encoded&form=QBLH&mkt=zh-CN",
            "https://www.bing.com/search?q=$encoded&mkt=zh-CN",
            "https://www.bing.com/search?q=$encoded"
        )
        var lastError: Throwable? = null
        urls.forEach { url ->
            runCatching { parseBingSearchResults(fetchSearchPage(url, referer = "https://cn.bing.com/")) }
                .onSuccess { if (it.isNotEmpty()) return it }
                .onFailure { lastError = it }
        }
        throw lastError ?: IllegalStateException("Bing 未返回可用结果（可能被验证码拦截）")
    }

    private fun searchDuckDuckGo(query: String): List<WebSearchResult> {
        // POST + lite endpoint is more reliable than html/?q= on mobile networks.
        val postBody = "q=" + URLEncoder.encode(query, Charsets.UTF_8.name())
        val lite = runCatching {
            parseDuckDuckGoSearchResults(
                fetchSearchPage(
                    "https://lite.duckduckgo.com/lite/",
                    method = "POST",
                    body = postBody,
                    contentType = "application/x-www-form-urlencoded"
                )
            )
        }.getOrDefault(emptyList())
        if (lite.isNotEmpty()) return lite

        val html = fetchSearchPage(
            "https://html.duckduckgo.com/html/?kl=cn-zh&q=" +
                URLEncoder.encode(query, Charsets.UTF_8.name())
        )
        return parseDuckDuckGoSearchResults(html)
            .ifEmpty { error("DuckDuckGo 未返回可用结果") }
    }

    private fun searchBaidu(query: String): List<WebSearchResult> {
        val encoded = URLEncoder.encode(query, Charsets.UTF_8.name())
        val html = fetchSearchPage(
            "https://www.baidu.com/s?wd=$encoded&rn=8",
            referer = "https://www.baidu.com/"
        )
        return parseBaiduSearchResults(html)
            .ifEmpty { error("百度未返回可用结果（可能需要验证码）") }
    }

    private fun searchWikipedia(query: String): List<WebSearchResult> {
        val encoded = URLEncoder.encode(query, Charsets.UTF_8.name())
        val body = fetchSearchPage(
            "https://zh.wikipedia.org/w/api.php?action=opensearch&limit=5&namespace=0&format=json&search=$encoded"
        )
        val arr = JSONArray(body)
        val titles = arr.optJSONArray(1) ?: return emptyList()
        val descs = arr.optJSONArray(2)
        val urls = arr.optJSONArray(3)
        val results = mutableListOf<WebSearchResult>()
        for (i in 0 until titles.length()) {
            val title = titles.optString(i)
            if (title.isBlank()) continue
            results += WebSearchResult(
                title = title,
                url = urls?.optString(i).orEmpty().ifBlank { "https://zh.wikipedia.org/wiki/" + URLEncoder.encode(title, "UTF-8") },
                snippet = descs?.optString(i).orEmpty()
            )
        }
        return results
    }

    private fun fetchSearchPage(
        url: String,
        method: String = "GET",
        body: String? = null,
        contentType: String? = null,
        referer: String? = null
    ): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 12_000
            readTimeout = 18_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.7")
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Cache-Control", "no-cache")
            referer?.let { setRequestProperty("Referer", it) }
            contentType?.let { setRequestProperty("Content-Type", it) }
            if (body != null) {
                doOutput = true
                this.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
        }
        return try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val raw = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText().take(750_000) }.orEmpty()
            if (code !in 200..299) error("HTTP $code")
            if (raw.isBlank()) error("空响应")
            // CAPTCHA / bot walls
            if (raw.contains("异常请求", ignoreCase = true) &&
                raw.contains("验证码", ignoreCase = true)
            ) {
                error("触发搜索验证码")
            }
            raw
        } finally {
            connection.disconnect()
        }
    }

    private fun parseBingSearchResults(html: String): List<WebSearchResult> {
        // Prefer classic algo blocks; fall back to any h2>a if markup changed.
        val blocks = Regex(
            """<li\b[^>]*class=["'][^"']*\bb_algo\b[^"']*["'][^>]*>([\s\S]*?)</li>""",
            RegexOption.IGNORE_CASE
        ).findAll(html).map { it.groupValues[1] }.toList()

        val fromBlocks = blocks.mapNotNull { block ->
            val link = Regex(
                """<h2[^>]*>\s*<a\b[^>]*href=["']([^"']+)["'][^>]*>([\s\S]*?)</a>""",
                RegexOption.IGNORE_CASE
            ).find(block) ?: return@mapNotNull null
            val url = normalizeResultUrl(link.groupValues[1]) ?: return@mapNotNull null
            val snippet = Regex(
                """<p[^>]*>([\s\S]*?)</p>""",
                RegexOption.IGNORE_CASE
            ).find(block)?.groupValues?.get(1).orEmpty()
            WebSearchResult(cleanSearchText(link.groupValues[2]), url, cleanSearchText(snippet))
        }.filter { it.title.isNotBlank() }.distinctBy { it.url }

        if (fromBlocks.isNotEmpty()) return fromBlocks.take(6)

        // Fallback: scrape heading links more loosely.
        val loose = Regex(
            """<h2[^>]*>\s*<a\b[^>]*href=["'](https?://[^"']+)["'][^>]*>([\s\S]*?)</a>""",
            RegexOption.IGNORE_CASE
        ).findAll(html).mapNotNull { m ->
            val url = normalizeResultUrl(m.groupValues[1]) ?: return@mapNotNull null
            if (url.contains("bing.com") || url.contains("microsoft.com")) return@mapNotNull null
            WebSearchResult(cleanSearchText(m.groupValues[2]), url, "")
        }.filter { it.title.isNotBlank() }.distinctBy { it.url }.toList()
        return loose.take(6)
    }

    private fun parseDuckDuckGoSearchResults(html: String): List<WebSearchResult> {
        val results = mutableListOf<WebSearchResult>()
        // lite.duckduckgo.com uses <a rel="nofollow" href="...">
        Regex(
            """<a\b[^>]*rel=["']nofollow["'][^>]*href=["']([^"']+)["'][^>]*>([\s\S]*?)</a>""",
            RegexOption.IGNORE_CASE
        ).findAll(html).forEach { m ->
            val url = normalizeDuckDuckGoUrl(m.groupValues[1]) ?: return@forEach
            val title = cleanSearchText(m.groupValues[2])
            if (title.isNotBlank()) results += WebSearchResult(title, url, "")
        }
        if (results.isEmpty()) {
            Regex(
                """<a\b(?=[^>]*\bclass=["'][^"']*\bresult__a\b[^"']*["'])(?=[^>]*\bhref=["'][^"']+["'])[^>]*>[\s\S]*?</a>""",
                RegexOption.IGNORE_CASE
            ).findAll(html).forEach { match ->
                val anchor = match.value
                val href = Regex("""\bhref=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                    .find(anchor)?.groupValues?.get(1) ?: return@forEach
                val url = normalizeDuckDuckGoUrl(href) ?: return@forEach
                val title = cleanSearchText(anchor.substringAfter('>').substringBeforeLast("</a>"))
                if (title.isNotBlank()) results += WebSearchResult(title, url, "")
            }
        }
        return results.distinctBy { it.url }.take(6)
    }

    private fun parseBaiduSearchResults(html: String): List<WebSearchResult> {
        val results = mutableListOf<WebSearchResult>()
        // Classic Baidu result cards
        Regex(
            """<h3[^>]*class=["'][^"']*\bc-title\b[^"']*["'][^>]*>\s*<a\b[^>]*href=["']([^"']+)["'][^>]*>([\s\S]*?)</a>""",
            RegexOption.IGNORE_CASE
        ).findAll(html).forEach { m ->
            val url = normalizeResultUrl(m.groupValues[1]) ?: return@forEach
            val title = cleanSearchText(m.groupValues[2])
            if (title.isNotBlank()) results += WebSearchResult(title, url, "")
        }
        // Fallback: any baidu result link
        if (results.isEmpty()) {
            Regex(
                """<a\b[^>]*href=["'](https?://(?:www\.)?baidu\.com/link\?url=[^"']+)["'][^>]*>([\s\S]{8,200}?)</a>""",
                RegexOption.IGNORE_CASE
            ).findAll(html).forEach { m ->
                val title = cleanSearchText(m.groupValues[2])
                if (title.isNotBlank() && !title.contains("百度")) {
                    results += WebSearchResult(title, m.groupValues[1], "")
                }
            }
        }
        return results.distinctBy { it.url }.take(6)
    }

    private fun normalizeDuckDuckGoUrl(rawHref: String): String? {
        val href = cleanSearchText(rawHref)
        val absolute = if (href.startsWith("//")) "https:$href" else href
        val redirected = runCatching {
            android.net.Uri.parse(absolute).getQueryParameter("uddg")
        }.getOrNull()
        return normalizeResultUrl(redirected ?: absolute)
    }

    private fun normalizeResultUrl(rawUrl: String): String? {
        val decoded = cleanSearchText(rawUrl).replace("&amp;", "&")
        val absolute = when {
            decoded.startsWith("http://") || decoded.startsWith("https://") -> decoded
            decoded.startsWith("//") -> "https:$decoded"
            else -> return null
        }
        return runCatching { URL(absolute).toExternalForm() }.getOrNull()
    }

    private fun cleanSearchText(raw: String): String = raw
        .replace(Regex("(?is)<script[^>]*>.*?</script>|<style[^>]*>.*?</style>"), " ")
        .replace(Regex("<[^>]+>"), " ")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(420)
}
