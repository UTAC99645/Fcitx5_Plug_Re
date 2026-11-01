/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.regex_commit.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class RuleScope(
    val mode: String = MODE_BLACKLIST,
    val packages: List<String> = emptyList()
) {
    companion object {
        const val MODE_BLACKLIST = "blacklist"
        const val MODE_WHITELIST = "whitelist"
    }

    val isWhitelist: Boolean
        get() = mode == MODE_WHITELIST
}

@Serializable
data class Rule(
    val id: String = "",
    val enabled: Boolean = true,
    val name: String = "",
    val pattern: String = "",
    val replacement: String = "",
    val scope: RuleScope = RuleScope(),
    val applyOncePerCommit: Boolean = true,
    val stopOnFirstMatch: Boolean = false,
    val caseInsensitive: Boolean = false,
    val unescapeReplacement: Boolean = false
)

@Serializable
data class RuleDocument(
    val version: Int = CURRENT_VERSION,
    val rules: List<Rule> = emptyList(),
    val skipNoSpellCheck: Boolean = false
) {
    companion object {
        const val CURRENT_VERSION = 1
    }
}

object RuleJson {

    val codec: Json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
        isLenient = false
    }

    fun decode(text: String): RuleDocument = codec.decodeFromString(text)

    fun encode(document: RuleDocument): String = codec.encodeToString(document)
}
