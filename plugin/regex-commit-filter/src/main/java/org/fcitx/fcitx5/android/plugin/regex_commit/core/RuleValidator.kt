/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.regex_commit.core

import com.google.re2j.Matcher
import com.google.re2j.Pattern
import com.google.re2j.PatternSyntaxException

object RuleValidator {

    private const val MAX_PATTERN_LENGTH = 4096

    fun compile(pattern: String, caseInsensitive: Boolean): Pattern {
        val flags = if (caseInsensitive) Pattern.CASE_INSENSITIVE else 0
        return Pattern.compile(pattern, flags)
    }

    fun validate(rule: Rule): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()
        if (rule.pattern.isEmpty()) {
            issues += ValidationIssue(IssueKind.EMPTY_PATTERN)
            return issues
        }
        if (rule.pattern.length > MAX_PATTERN_LENGTH) {
            issues += ValidationIssue(IssueKind.PATTERN_TOO_LARGE)
            return issues
        }
        scanUnsupported(rule.pattern)?.let { issues += it }
        if (issues.isNotEmpty()) return issues

        val pattern = try {
            compile(rule.pattern, rule.caseInsensitive)
        } catch (e: PatternSyntaxException) {
            issues += ValidationIssue(IssueKind.SYNTAX, e.message ?: "")
            return issues
        }
        issues += validateReplacement(rule.replacement, pattern)
        return issues
    }

    /** Static scan for constructs that RE2 deliberately does not support. */
    private fun scanUnsupported(pattern: String): ValidationIssue? {
        var i = 0
        while (i < pattern.length) {
            val c = pattern[i]
            when (c) {
                '\\' -> {
                    val next = if (i + 1 < pattern.length) pattern[i + 1] else null
                    if (next != null && next.isDigit()) {
                        return ValidationIssue(IssueKind.BACKREFERENCE, "\\$next")
                    }
                    i += 2
                }
                '[' -> {
                    i++
                    while (i < pattern.length && pattern[i] != ']') {
                        if (pattern[i] == '\\') i++
                        i++
                    }
                    i++
                }
                '(' -> {
                    if (pattern.startsWith("(?=", i) || pattern.startsWith("(?!", i)) {
                        return ValidationIssue(IssueKind.LOOKAHEAD)
                    }
                    if (pattern.startsWith("(?<=", i) || pattern.startsWith("(?<!", i)) {
                        return ValidationIssue(IssueKind.LOOKBEHIND)
                    }
                    if (pattern.startsWith("(?>", i)) {
                        return ValidationIssue(IssueKind.ATOMIC_GROUP)
                    }
                    i++
                }
                '*', '+', '?', '}' -> {
                    if (i + 1 < pattern.length && pattern[i + 1] == '+') {
                        return ValidationIssue(IssueKind.POSSESSIVE)
                    }
                    i++
                }
                else -> i++
            }
        }
        return null
    }

    private fun validateReplacement(
        replacement: String,
        pattern: Pattern
    ): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()
        var i = 0
        while (i < replacement.length) {
            val c = replacement[i]
            if (c == '\\') {
                i += 2
                continue
            }
            if (c != '$') {
                i++
                continue
            }
            if (i + 1 >= replacement.length) break
            val next = replacement[i + 1]
            when {
                next == '$' -> i += 2
                next == '{' -> {
                    val end = replacement.indexOf('}', i + 2)
                    if (end < 0) {
                        issues += ValidationIssue(IssueKind.SYNTAX, replacement.substring(i))
                        break
                    }
                    val name = replacement.substring(i + 2, end)
                    if (!groupExists(name, pattern)) {
                        issues += ValidationIssue(IssueKind.UNKNOWN_GROUP, name)
                    }
                    i = end + 1
                }
                next.isDigit() -> {
                    var j = i + 1
                    while (j < replacement.length && replacement[j].isDigit()) j++
                    val index = replacement.substring(i + 1, j).toIntOrNull()
                    if (index == null || index > pattern.groupCount()) {
                        issues += ValidationIssue(
                            IssueKind.UNKNOWN_GROUP,
                            replacement.substring(i + 1, j)
                        )
                    }
                    i = j
                }
                else -> i++
            }
        }
        return issues
    }

    private fun groupExists(name: String, pattern: Pattern): Boolean {
        val named = pattern.namedGroups()
        val index = name.toIntOrNull()
        return if (index != null) index in 0..pattern.groupCount() else named.containsKey(name)
    }

    fun expand(
        template: String,
        matcher: Matcher,
        unescape: Boolean
    ): String = ReplacementExpander.expand(template, matcher, unescape)
}
