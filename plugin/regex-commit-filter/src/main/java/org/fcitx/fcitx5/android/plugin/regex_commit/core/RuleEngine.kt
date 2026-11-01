/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.regex_commit.core

import com.google.re2j.Matcher
import com.google.re2j.Pattern
import java.util.Collections

data class Limits(
    val maxTextBytes: Int = 4096,
    val maxReplacementsPerRule: Int = 1000,
    val maxNanosPerRule: Long = 20_000_000L
)

data class GroupValue(val name: String, val value: String)

data class MatchDetail(
    val index: Int,
    val start: Int,
    val end: Int,
    val value: String,
    val groups: List<GroupValue>
)

data class PreviewResult(
    val issues: List<ValidationIssue>,
    val output: String,
    val matches: List<MatchDetail>
)

/**
 * Mirrors the C++ runtime engine. Used for the "try it" preview and for unit
 * tests, so that both sides of the socket agree on the semantics.
 */
class RuleEngine(private val limits: Limits = Limits()) {

    private val cache: MutableMap<String, Pattern> =
        Collections.synchronizedMap(object : LinkedHashMap<String, Pattern>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pattern>?): Boolean =
                size > 256
        })

    fun invalidateCache() = cache.clear()

    fun compiled(rule: Rule): Pattern {
        val key = (if (rule.caseInsensitive) "i:" else "s:") + rule.pattern
        cache[key]?.let { return it }
        val pattern = RuleValidator.compile(rule.pattern, rule.caseInsensitive)
        cache[key] = pattern
        return pattern
    }

    /** Apply one rule; returns the replaced text and the number of replacements. */
    fun applyRule(rule: Rule, input: String): Pair<String, Int> {
        if (rule.pattern.isEmpty()) return input to 0
        val pattern = compiled(rule)
        val matcher = pattern.matcher(input)
        val sb = StringBuilder(input.length + 16)
        val start = System.nanoTime()
        var cursor = 0
        var count = 0
        while (matcher.find()) {
            count++
            if (count > limits.maxReplacementsPerRule) return input to 0
            if (count > 1 && System.nanoTime() - start > limits.maxNanosPerRule) return input to 0
            sb.append(input, cursor, matcher.start())
            sb.append(ReplacementExpander.expand(rule.replacement, matcher, rule.unescapeReplacement))
            cursor = matcher.end()
            if (rule.applyOncePerCommit) break
        }
        if (count == 0) return input to 0
        sb.append(input, cursor, input.length)
        return sb.toString() to count
    }

    /**
     * Apply the whole rule chain. Any failure degrades to returning the input
     * unchanged; nothing is ever logged.
     */
    fun apply(
        document: RuleDocument,
        input: String,
        packageName: String,
        noSpellCheck: Boolean = false
    ): String {
        if (input.isEmpty()) return input
        if (Utf8.byteLength(input) > limits.maxTextBytes) return input
        if (document.skipNoSpellCheck && noSpellCheck) return input
        var text = input
        for (rule in document.rules) {
            if (!rule.enabled || !scopeAllows(rule.scope, packageName)) continue
            val (result, count) = runCatching { applyRule(rule, text) }
                .getOrElse { text to 0 }
            if (count > 0) {
                text = result
                if (rule.stopOnFirstMatch) break
            }
        }
        return text
    }

    fun preview(rule: Rule, input: String): PreviewResult {
        val issues = RuleValidator.validate(rule)
        if (issues.isNotEmpty()) return PreviewResult(issues, "", emptyList())
        val pattern = runCatching { compiled(rule) }.getOrNull()
            ?: return PreviewResult(
                listOf(ValidationIssue(IssueKind.SYNTAX, "")), "", emptyList()
            )
        val (output, _) = runCatching { applyRule(rule, input) }
            .getOrElse { input to 0 }
        val matches = collectMatches(rule, pattern, input)
        return PreviewResult(emptyList(), output, matches)
    }

    private fun collectMatches(rule: Rule, pattern: Pattern, input: String): List<MatchDetail> {
        if (input.isEmpty()) return emptyList()
        val matcher: Matcher = pattern.matcher(input)
        val result = mutableListOf<MatchDetail>()
        var index = 0
        val limit = if (rule.applyOncePerCommit) 1 else 50
        while (matcher.find() && result.size < limit) {
            val groups = mutableListOf(GroupValue("0", matcher.group()))
            pattern.namedGroups().forEach { (name, groupIndex) ->
                groups += GroupValue(name, runCatching { matcher.group(groupIndex) }.getOrNull() ?: "")
            }
            for (g in 1..pattern.groupCount()) {
                groups += GroupValue(g.toString(), runCatching { matcher.group(g) }.getOrNull() ?: "")
            }
            result += MatchDetail(index, matcher.start(), matcher.end(), matcher.group(), groups)
            index++
        }
        return result
    }

    private fun scopeAllows(scope: RuleScope, packageName: String): Boolean {
        if (scope.packages.isEmpty()) return true
        val hit = scope.packages.any { it.trim() == packageName }
        return if (scope.isWhitelist) hit else !hit
    }
}

object Utf8 {

    /** UTF-8 byte length of [text] without allocating a byte array. */
    fun byteLength(text: String): Int {
        var bytes = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            bytes += when {
                c.code < 0x80 -> 1
                c.code < 0x800 -> 2
                Character.isHighSurrogate(c) && i + 1 < text.length &&
                    Character.isLowSurrogate(text[i + 1]) -> {
                    i++
                    4
                }
                else -> 3
            }
            i++
        }
        return bytes
    }
}
