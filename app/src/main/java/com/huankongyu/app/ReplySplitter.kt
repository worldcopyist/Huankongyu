package com.huankongyu.app

import android.icu.text.BreakIterator
import java.util.Locale

/** Splits only long or intentionally paragraph-separated replies at complete natural-language boundaries. */
internal fun splitAssistantReply(
    reply: String,
    settings: ReplySplitterSettings = ReplySplitterSettings()
): List<String> {
    val config = settings.normalized()
    val normalized = reply.replace("\r\n", "\n").trim()
    if (normalized.isBlank()) return emptyList()
    val paragraphs = normalized.split(Regex("\\n\\s*\\n+")).map { it.trim() }.filter { it.isNotBlank() }
    val lengthModeText = normalized.replace(Regex("\\s+"), " ").trim()
    val shouldKeepOneMessage = when (config.mode) {
        ReplySplitMode.Length -> lengthModeText.length <= config.maxSegmentLength
        ReplySplitMode.Scene -> paragraphs.size == 1 && normalized.length <= config.maxSegmentLength
    }
    if (shouldKeepOneMessage) return listOf(
        if (config.mode == ReplySplitMode.Length) lengthModeText else normalized.replace('\n', ' ')
    )

    val segments = when (config.mode) {
        ReplySplitMode.Length -> splitReplyParagraph(lengthModeText, config)
        ReplySplitMode.Scene -> {
            if (paragraphs.size > 1) paragraphs.flatMap { paragraph -> splitReplyParagraph(paragraph, config) }
            else splitReplyParagraph(normalized, config)
        }
    }
    return mergeReplySegmentsToLimit(segments, config.maxSegments).ifEmpty { listOf(normalized) }
}

private fun splitReplyParagraph(paragraph: String, settings: ReplySplitterSettings): List<String> {
    val sentenceUnits = splitIntoNaturalSentences(paragraph.replace('\n', ' '))
        .flatMap { unit -> splitOverlongReplyUnit(unit.trim(), settings.maxSegmentLength) }
        .filter { it.isNotBlank() }
    if (sentenceUnits.isEmpty()) return emptyList()

    val result = mutableListOf<String>()
    var current = ""
    sentenceUnits.forEach { unit ->
        if (current.isNotEmpty() && current.length + unit.length > settings.maxSegmentLength &&
            current.length >= settings.minSegmentLength
        ) {
            result += current
            current = unit
        } else {
            current += unit
        }
    }
    if (current.isNotBlank()) result += current
    return result
}

private fun splitIntoNaturalSentences(text: String): List<String> {
    return runCatching {
        val iterator = BreakIterator.getSentenceInstance(Locale.CHINA).apply { setText(text) }
        val sentences = mutableListOf<String>()
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            text.substring(start, end).trim().takeIf { it.isNotBlank() }?.let(sentences::add)
            start = end
            end = iterator.next()
        }
        sentences.ifEmpty { listOf(text.trim()) }
    }.getOrElse {
        // Android's ICU implementation is not present in local JVM unit tests; devices use the branch above.
        text.split(Regex("(?<=[。！？!?；;])")).map(String::trim).filter(String::isNotBlank)
            .ifEmpty { listOf(text.trim()) }
    }
}

private fun splitOverlongReplyUnit(unit: String, maxSegmentLength: Int): List<String> {
    if (unit.length <= maxSegmentLength) return listOf(unit)
    val result = mutableListOf<String>()
    var remaining = unit
    while (remaining.length > maxSegmentLength) {
        val searchEnd = maxSegmentLength.coerceAtMost(remaining.lastIndex)
        val preferredBreak = (searchEnd downTo 18).firstOrNull { index -> remaining[index] in "，,、；;：: " }
        val splitAt = (preferredBreak?.plus(1) ?: maxSegmentLength).coerceAtMost(remaining.length)
        result += remaining.substring(0, splitAt).trim()
        remaining = remaining.substring(splitAt).trimStart()
    }
    if (remaining.isNotBlank()) result += remaining
    return result
}

private fun mergeReplySegmentsToLimit(segments: List<String>, maxSegments: Int): List<String> {
    val nonBlank = segments.filter { it.isNotBlank() }
    if (nonBlank.size <= maxSegments) return nonBlank
    return List(maxSegments) { index ->
        val start = index * nonBlank.size / maxSegments
        val end = (index + 1) * nonBlank.size / maxSegments
        nonBlank.subList(start, end).joinToString(separator = "")
    }.filter { it.isNotBlank() }
}

internal fun replySegmentDelayMillis(segment: String): Long =
    (segment.length * 14L).coerceIn(360L, 1_000L)
