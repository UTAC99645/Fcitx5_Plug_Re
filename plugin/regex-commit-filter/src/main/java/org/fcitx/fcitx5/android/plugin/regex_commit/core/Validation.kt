/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.regex_commit.core

/**
 * A single problem found while checking a rule. [kind] is translated by the UI,
 * [detail] carries the raw engine message (never user committed text).
 */
data class ValidationIssue(val kind: IssueKind, val detail: String = "")

enum class IssueKind {
    EMPTY_PATTERN,
    LOOKAHEAD,
    LOOKBEHIND,
    BACKREFERENCE,
    POSSESSIVE,
    ATOMIC_GROUP,
    SYNTAX,
    UNKNOWN_GROUP,
    PATTERN_TOO_LARGE
}
