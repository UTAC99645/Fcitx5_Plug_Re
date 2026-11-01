/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.regex_commit.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleEngineTest {

    private val engine = RuleEngine()

    private fun rule(
        pattern: String,
        replacement: String = "",
        applyOnce: Boolean = true,
        stopOnFirstMatch: Boolean = false,
        caseInsensitive: Boolean = false,
        scope: RuleScope = RuleScope(),
        enabled: Boolean = true
    ) = Rule(
        id = "test",
        enabled = enabled,
        name = "test",
        pattern = pattern,
        replacement = replacement,
        scope = scope,
        applyOncePerCommit = applyOnce,
        stopOnFirstMatch = stopOnFirstMatch,
        caseInsensitive = caseInsensitive
    )

    private fun apply(vararg rules: Rule, input: String, packageName: String = "com.example.app") =
        engine.apply(RuleDocument(rules = rules.toList()), input, packageName)

    @Test
    fun plainReplacementReplacesAll() {
        assertEquals("bar bar", apply(rule("foo", "bar", applyOnce = false), input = "foo foo"))
    }

    @Test
    fun namedCaptureGroups() {
        val r = rule(
            "(?P<pre>1[3-9]\\d)(\\d{4})(?P<post>\\d{4})",
            "\${pre}****\${post}"
        )
        assertEquals("138****5678", apply(r, input = "13812345678"))
    }

    @Test
    fun unicodeHanScriptClass() {
        assertEquals("字字abc", apply(rule("\\p{Han}", "字", applyOnce = false), input = "中文abc"))
    }

    @Test
    fun caseInsensitiveInlineFlag() {
        assertEquals("hi hi", apply(rule("(?i)hello", "hi", applyOnce = false), input = "HeLLo hello"))
    }

    @Test
    fun caseInsensitiveOption() {
        assertEquals("hi hi", apply(
            rule("hello", "hi", applyOnce = false, caseInsensitive = true),
            input = "HeLLo hello"
        ))
    }

    @Test
    fun nonGreedyQuantifier() {
        assertEquals("X X", apply(rule("a.*?b", "X", applyOnce = false), input = "a1b a22b"))
    }

    @Test
    fun lookaheadIsRejectedAtSaveTime() {
        val issues = RuleValidator.validate(rule("foo(?=bar)", "x"))
        assertEquals(1, issues.size)
        assertEquals(IssueKind.LOOKAHEAD, issues[0].kind)
    }

    @Test
    fun lookbehindIsRejectedAtSaveTime() {
        val issues = RuleValidator.validate(rule("(?<=foo)bar", "x"))
        assertEquals(IssueKind.LOOKBEHIND, issues.first().kind)
    }

    @Test
    fun backreferenceIsRejectedAtSaveTime() {
        val issues = RuleValidator.validate(rule("(a)\\1", "x"))
        assertEquals(IssueKind.BACKREFERENCE, issues.first().kind)
    }

    @Test
    fun possessiveQuantifierIsRejectedAtSaveTime() {
        val issues = RuleValidator.validate(rule("a++", "x"))
        assertEquals(IssueKind.POSSESSIVE, issues.first().kind)
    }

    @Test
    fun unknownReplacementGroupIsRejected() {
        val issues = RuleValidator.validate(rule("(a)", "\${nope}"))
        assertEquals(IssueKind.UNKNOWN_GROUP, issues.first().kind)
    }

    @Test
    fun oversizeTextIsSkipped() {
        val input = "a".repeat(5000)
        assertEquals(input, apply(rule("a", "b", applyOnce = false), input = input))
    }

    @Test
    fun chainedRulesFeedNextRule() {
        assertEquals("c", apply(
            rule("a", "b", applyOnce = false),
            rule("b", "c", applyOnce = false),
            input = "a"
        ))
    }

    @Test
    fun stopOnFirstMatchSkipsFollowingRules() {
        assertEquals("y", apply(
            rule("x", "y", applyOnce = false, stopOnFirstMatch = true),
            rule("y", "z", applyOnce = false),
            input = "x"
        ))
    }

    @Test
    fun whitelistScopeOnlyAppliesToAllowedPackages() {
        val scoped = rule(
            "a", "b", applyOnce = false,
            scope = RuleScope(RuleScope.MODE_WHITELIST, listOf("com.allowed.app"))
        )
        assertEquals("b", apply(scoped, input = "a", packageName = "com.allowed.app"))
        assertEquals("a", apply(scoped, input = "a", packageName = "com.other.app"))
    }

    @Test
    fun blacklistScopeSkipsBlockedPackages() {
        val scoped = rule(
            "a", "b", applyOnce = false,
            scope = RuleScope(RuleScope.MODE_BLACKLIST, listOf("com.blocked.app"))
        )
        assertEquals("a", apply(scoped, input = "a", packageName = "com.blocked.app"))
        assertEquals("b", apply(scoped, input = "a", packageName = "com.other.app"))
    }

    @Test
    fun dollarEscapeIsLiteral() {
        assertEquals("$1", apply(rule("a", "$$1"), input = "a"))
    }

    @Test
    fun disabledRuleIsIgnored() {
        assertEquals("a", apply(
            rule("a", "b", applyOnce = false, enabled = false),
            input = "a"
        ))
    }

    @Test
    fun noSuggestionsPolicySkipsRewrite() {
        val document = RuleDocument(
            rules = listOf(rule("a", "b", applyOnce = false)),
            skipNoSpellCheck = true
        )
        assertEquals("a", engine.apply(document, "a", "com.example.app", noSpellCheck = true))
    }

    @Test
    fun emptyInputIsUnchanged() {
        assertEquals("", apply(rule("a", "b", applyOnce = false), input = ""))
    }

    @Test
    fun previewReportsMatchesAndGroups() {
        val preview = engine.preview(
            rule("(?P<num>\\d+)", "<\${num}>", applyOnce = true),
            "abc 123 def"
        )
        assertTrue(preview.issues.isEmpty())
        assertEquals("abc <123> def", preview.output)
        assertEquals(1, preview.matches.size)
        val groups = preview.matches[0].groups
        assertTrue(groups.any { it.name == "num" && it.value == "123" })
    }

    @Test
    fun previewReportsCompileError() {
        val preview = engine.preview(rule("(unclosed", "x"), "abc")
        assertTrue(preview.issues.isNotEmpty())
        assertEquals("", preview.output)
    }

    @Test
    fun escapedReplacementExpandsControlCharacters() {
        val r = rule("(a)", "\$1\\n", applyOnce = true).copy(unescapeReplacement = true)
        assertEquals("a\n", apply(r, input = "a"))
    }
}
