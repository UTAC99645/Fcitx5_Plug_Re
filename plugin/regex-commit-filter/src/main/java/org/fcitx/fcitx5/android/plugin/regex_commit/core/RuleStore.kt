/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.regex_commit.core

import android.content.Context
import java.io.File
import java.util.UUID

data class SyncResult(val saved: Boolean, val bridge: BridgeResult)

/**
 * Owns the ruleset inside the plugin process: loads/saves `rules.json` in the
 * plugin's private storage (never on external storage) and pushes a snapshot to
 * the native addon through [RuleBridge].
 *
 * The native addon keeps its own last-received copy inside the main app sandbox,
 * so the rules survive an IME restart without the plugin being opened again.
 */
class RuleStore private constructor(context: Context) {

    private val file: File = File(context.filesDir, "rules.json")
    private val lock = Any()
    private var document = RuleDocument()
    private var loaded = false

    fun load(): RuleDocument = synchronized(lock) {
        if (!loaded) {
            loaded = true
            if (file.exists()) {
                val text = runCatching { file.readText() }.getOrNull()
                val decoded = text?.let { runCatching { RuleJson.decode(it) }.getOrNull() }
                if (decoded != null) {
                    document = decoded.copy(rules = decoded.rules.map(::withId))
                }
            }
        }
        document
    }

    fun current(): RuleDocument = load()

    fun replaceAll(newDocument: RuleDocument) {
        synchronized(lock) {
            document = newDocument.copy(rules = newDocument.rules.map(::withId))
        }
    }

    fun setSkipNoSpellCheck(value: Boolean) {
        synchronized(lock) {
            document = document.copy(skipNoSpellCheck = value)
        }
    }

    fun upsert(rule: Rule) {
        synchronized(lock) {
            val fixed = withId(rule)
            val index = document.rules.indexOfFirst { it.id == fixed.id }
            document = if (index >= 0) {
                document.copy(rules = document.rules.toMutableList().also { it[index] = fixed })
            } else {
                document.copy(rules = document.rules + fixed)
            }
        }
    }

    fun setEnabled(id: String, enabled: Boolean) {
        synchronized(lock) {
            document = document.copy(rules = document.rules.map {
                if (it.id == id) it.copy(enabled = enabled) else it
            })
        }
    }

    fun remove(id: String) {
        synchronized(lock) {
            document = document.copy(rules = document.rules.filterNot { it.id == id })
        }
    }

    fun move(id: String, delta: Int) {
        synchronized(lock) {
            val rules = document.rules.toMutableList()
            val from = rules.indexOfFirst { it.id == id }
            if (from < 0) return
            val to = (from + delta).coerceIn(0, rules.lastIndex)
            if (to == from) return
            val item = rules.removeAt(from)
            rules.add(to, item)
            document = document.copy(rules = rules)
        }
    }

    /** Persist locally first, then push to the running IME addon. */
    fun saveAndPush(): SyncResult {
        val snapshot = synchronized(lock) { document }
        val json = RuleJson.encode(snapshot)
        val saved = runCatching { writeAtomically(json) }.isSuccess
        val bridge = RuleBridge.push(json)
        return SyncResult(saved, bridge)
    }

    private fun writeAtomically(json: String) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json)
        if (!tmp.renameTo(file)) {
            file.writeText(json)
            tmp.delete()
        }
    }

    private fun withId(rule: Rule): Rule =
        if (rule.id.isBlank()) rule.copy(id = UUID.randomUUID().toString()) else rule

    companion object {

        @Volatile
        private var instance: RuleStore? = null

        fun get(context: Context): RuleStore =
            instance ?: synchronized(this) {
                instance ?: RuleStore(context.applicationContext).also { instance = it }
            }
    }
}
