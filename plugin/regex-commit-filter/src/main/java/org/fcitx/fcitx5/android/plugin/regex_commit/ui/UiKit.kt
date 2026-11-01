/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.regex_commit.ui

import android.content.Context
import org.fcitx.fcitx5.android.plugin.regex_commit.R
import org.fcitx.fcitx5.android.plugin.regex_commit.core.IssueKind
import org.fcitx.fcitx5.android.plugin.regex_commit.core.ValidationIssue

fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

object IssueMessages {

    fun describe(context: Context, issue: ValidationIssue): String = when (issue.kind) {
        IssueKind.EMPTY_PATTERN -> context.getString(R.string.err_empty_pattern)
        IssueKind.LOOKAHEAD -> context.getString(R.string.err_lookahead)
        IssueKind.LOOKBEHIND -> context.getString(R.string.err_lookbehind)
        IssueKind.BACKREFERENCE -> context.getString(R.string.err_backreference)
        IssueKind.POSSESSIVE -> context.getString(R.string.err_possessive)
        IssueKind.ATOMIC_GROUP -> context.getString(R.string.err_atomic)
        IssueKind.SYNTAX -> context.getString(R.string.err_syntax, issue.detail)
        IssueKind.UNKNOWN_GROUP -> context.getString(R.string.err_unknown_group, issue.detail)
        IssueKind.PATTERN_TOO_LARGE -> context.getString(R.string.err_pattern_too_large)
    }

    fun describeAll(context: Context, issues: List<ValidationIssue>): String =
        issues.take(3).joinToString("\n") { describe(context, it) }
}
