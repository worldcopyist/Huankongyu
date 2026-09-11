package com.huankongyu.app

import android.net.Uri
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * App-owned implementation of the planner's web_search tool. It follows a small
 * multi-engine fallback chain so no separate search API key is required.
 */
internal object WebSearchClient {
    fun search(query: String): WebSearchResponse {
        val normalizedQuery = normalizeWebSearchQuery(query) ?: error("网页查询语句为空")
        val failures = mutableListOf<String>()
        listOf(
            "Bing" to { searchBing(normalizedQuery) },
            "DuckDuckGo" to { searchDuckDuckGo(normalizedQuery) }
        ).forEach { (engine, search) ->
            runCatching { search() }
                .onSuccess { results -> if (results.isNotEmpty()) return WebSearchResponse(engine, results.take(4)) }
                .onFailure { error -> failures += "$engine：${error.message?.take(80) ?: "请求失败"}" }
        }
        error(failures.ifEmpty { listOf("没有找到公开网页结果") }.joinToString("；"))
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
            "https://cn.bing.com/search?q=$encoded&adlt=off&mkt=zh-CN",
            "https://www.bing.com/search?q=$encoded&adlt=off&mkt=zh-CN"
        )
        var lastError: Throwable? = null
        urls.forEach { url ->
            val results = runCatching { parseBingSearchResults(fetchSearchPage(url)) }
                .onFailure { lastError = it }
                .getOrDefault(emptyList())
            if (results.isNotEmpty()) return results
        }
        throw lastError ?: IllegalStateException("没有找到可用的 Bing 结果")
    }

    private fun searchDuckDuckGo(query: String): List<WebSearchResult> {
        val encoded = URLEncoder.encode(query, Charsets.UTF_8.name())
        return parseDuckDuckGoSearchResults(fetchSearchPage("https://html.duckduckgo.com/html/?kl=cn-zh&q=$encoded"))
            .ifEmpty { error("没有找到可用的 DuckDuckGo 结果") }
    }

    private fun fetchSearchPage(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 15_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "text/html,application/xhtml+xml")
            setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.7")
            setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 16; Mobile) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36"
            )
        }
        return try {
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText().take(750_000) }.orEmpty()
            if (code !in 200..299) error("搜索服务返回 $code")
            if (body.isBlank()) error("搜索服务没有返回内容")
            body
        } finally {
            connection.disconnect()
        }
    }

    private fun parseBingSearchResults(html: String): List<WebSearchResult> {
        val resultBlocks = Regex("""<li\b[^>]*class=["'][^"']*\bb_algo\b[^"']*["'][^>]*>([\s\S]*?)</li>""", RegexOption.IGNORE_CASE)
            .findAll(html).map { it.groupValues[1] }.toList()
        return resultBlocks.mapNotNull { block ->
            val link = Regex("""<h2[^>]*>[\s\S]*?<a\b[^>]*href=["']([^"']+)["'][^>]*>([\s\S]*?)</a>""", RegexOption.IGNORE_CASE)
                .find(block) ?: return@mapNotNull null
            val url = normalizeResultUrl(link.groupValues[1]) ?: return@mapNotNull null
            val snippet = Regex("""<div[^>]*class=["'][^"']*\bb_caption\b[^"']*["'][^>]*>[\s\S]*?<p[^>]*>([\s\S]*?)</p>""", RegexOption.IGNORE_CASE)
                .find(block)?.groupValues?.get(1).orEmpty()
            WebSearchResult(cleanSearchText(link.groupValues[2]), url, cleanSearchText(snippet))
        }.filter { it.title.isNotBlank() }.distinctBy { it.url }.take(6)
    }

    private fun parseDuckDuckGoSearchResults(html: String): List<WebSearchResult> {
        val anchors = Regex("""<a\b(?=[^>]*\bclass=["'][^"']*\bresult__a\b[^"']*["'])(?=[^>]*\bhref=["'][^"']+["'])[^>]*>[\s\S]*?</a>""", RegexOption.IGNORE_CASE)
            .findAll(html)
        return anchors.mapNotNull { match ->
            val anchor = match.value
            val href = Regex("""\bhref=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(anchor)?.groupValues?.get(1)
                ?: return@mapNotNull null
            val url = normalizeDuckDuckGoUrl(href) ?: return@mapNotNull null
            val title = cleanSearchText(anchor.substringAfter('>').substringBeforeLast("</a>"))
            val afterAnchor = html.substring(match.range.last + 1).take(2_000)
            val snippet = Regex(
                """<a\b[^>]*class=["'][^"']*\bresult__snippet\b[^"']*["'][^>]*>([\s\S]*?)</a>|<div[^>]*class=["'][^"']*\bresult__snippet\b[^"']*["'][^>]*>([\s\S]*?)</div>""",
                RegexOption.IGNORE_CASE
            ).find(afterAnchor)
                ?.let { cleanSearchText(it.groupValues.drop(1).firstOrNull { value -> value.isNotBlank() }.orEmpty()) }
                .orEmpty()
            WebSearchResult(title, url, snippet)
        }.filter { it.title.isNotBlank() }.distinctBy { it.url }.take(6).toList()
    }

    private fun normalizeDuckDuckGoUrl(rawHref: String): String? {
        val href = cleanSearchText(rawHref)
        val absolute = if (href.startsWith("//")) "https:$href" else href
        val redirected = Uri.parse(absolute).getQueryParameter("uddg")
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
