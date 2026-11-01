/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.regex_commit.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import org.fcitx.fcitx5.android.plugin.regex_commit.R
import org.fcitx.fcitx5.android.plugin.regex_commit.core.Rule
import org.fcitx.fcitx5.android.plugin.regex_commit.core.RuleJson
import org.fcitx.fcitx5.android.plugin.regex_commit.core.RuleStore

class MainActivity : AppCompatActivity() {

    private lateinit var store: RuleStore
    private lateinit var adapter: RuleAdapter
    private lateinit var statusView: TextView
    private lateinit var countView: TextView
    private lateinit var skipSwitch: SwitchCompat
    private lateinit var recycler: RecyclerView
    private lateinit var root: LinearLayout

    private val editLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            refresh()
            syncNow()
        }
    }

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) importFrom(uri) }

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> if (uri != null) exportTo(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = RuleStore.get(this)
        store.load()
        setContentView(buildContent())
        refresh()
        syncNow()
    }

    private fun buildContent(): LinearLayout {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val header = TextView(this).apply {
            text = getString(R.string.title_rules)
            textSize = 22f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(16), dp(16), dp(16), dp(2))
        }
        root.addView(header)

        countView = TextView(this).apply {
            textSize = 12f
            alpha = 0.7f
            setPadding(dp(16), 0, dp(16), dp(6))
        }
        root.addView(countView)

        val privacy = TextView(this).apply {
            text = getString(R.string.privacy_statement)
            textSize = 12f
            setPadding(dp(16), 0, dp(16), dp(8))
        }
        root.addView(privacy)

        val statusCard = MaterialCardView(this).apply {
            radius = dp(10).toFloat()
        }
        val statusInner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        statusView = TextView(this).apply { textSize = 14f }
        val hint = TextView(this).apply {
            text = getString(R.string.status_hint)
            textSize = 11f
            alpha = 0.7f
            setPadding(0, dp(4), 0, 0)
        }
        statusInner.addView(statusView)
        statusInner.addView(hint)
        statusCard.addView(statusInner)
        root.addView(statusCard, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            leftMargin = dp(12)
            rightMargin = dp(12)
        })

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(4))
        }
        val add = MaterialButton(this).apply {
            text = getString(R.string.action_add)
            setOnClickListener { openEditor(null) }
        }
        val importButton = MaterialButton(this).apply {
            text = getString(R.string.action_import)
            setOnClickListener {
                importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
            }
        }
        val export = MaterialButton(this).apply {
            text = getString(R.string.action_export)
            setOnClickListener { exportLauncher.launch("regex-commit-rules.json") }
        }
        listOf(add, importButton, export).forEach {
            buttonRow.addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginEnd = dp(6) })
        }
        root.addView(buttonRow)

        recycler = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            clipToPadding = false
        }
        adapter = RuleAdapter(
            onEdit = { openEditor(it.id) },
            onToggle = { rule, checked ->
                store.setEnabled(rule.id, checked)
                refresh()
                syncNow()
            },
            onMove = { rule, delta ->
                store.move(rule.id, delta)
                refresh()
                syncNow()
            },
            onDelete = { rule -> confirmDelete(rule) }
        )
        recycler.adapter = adapter
        root.addView(recycler, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        skipSwitch = SwitchCompat(this).apply {
            text = getString(R.string.switch_skip_no_suggestions)
            textSize = 12f
            setPadding(dp(16), dp(8), dp(16), dp(16))
            setOnCheckedChangeListener { _, checked ->
                store.setSkipNoSpellCheck(checked)
                syncNow()
            }
        }
        root.addView(skipSwitch)
        return root
    }

    private fun refresh() {
        val doc = store.current()
        adapter.submit(doc.rules)
        countView.text = getString(R.string.status_rules, doc.rules.size)
        skipSwitch.setOnCheckedChangeListener(null)
        skipSwitch.isChecked = doc.skipNoSpellCheck
        skipSwitch.setOnCheckedChangeListener { _, checked ->
            store.setSkipNoSpellCheck(checked)
            syncNow()
        }
        recycler.visibility = if (doc.rules.isEmpty()) RecyclerView.GONE else RecyclerView.VISIBLE
        if (doc.rules.isEmpty() && root.childCount > 0) {
            countView.text = getString(R.string.empty_rules)
        }
    }

    private fun syncNow() {
        statusView.text = getString(R.string.status_syncing)
        Thread {
            val result = store.saveAndPush()
            runOnUiThread {
                if (!result.saved) {
                    snack(getString(R.string.toast_save_failed))
                    return@runOnUiThread
                }
                statusView.text = if (result.bridge.ok) {
                    getString(R.string.status_synced)
                } else {
                    getString(R.string.status_failed, result.bridge.message)
                }
            }
        }.start()
    }

    private fun openEditor(ruleId: String?) {
        editLauncher.launch(Intent(this, EditRuleActivity::class.java).apply {
            putExtra(EditRuleActivity.EXTRA_RULE_ID, ruleId)
        })
    }

    private fun confirmDelete(rule: Rule) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_title)
            .setMessage(getString(R.string.delete_message, rule.name.ifBlank { rule.pattern }))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete_confirm) { _, _ ->
                store.remove(rule.id)
                refresh()
                syncNow()
            }
            .show()
    }

    private fun importFrom(uri: Uri) {
        Thread {
            val text = runCatching {
                contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()
            val document = text?.let { runCatching { RuleJson.decode(it) }.getOrNull() }
            runOnUiThread {
                if (document == null) {
                    snack(getString(R.string.toast_import_failed, "invalid JSON"))
                } else {
                    store.replaceAll(document)
                    refresh()
                    syncNow()
                    snack(getString(R.string.toast_import_ok, document.rules.size))
                }
            }
        }.start()
    }

    private fun exportTo(uri: Uri) {
        Thread {
            val ok = runCatching {
                contentResolver.openOutputStream(uri)?.bufferedWriter()?.use {
                    it.write(RuleJson.encode(store.current()))
                }
            }.isSuccess
            runOnUiThread {
                if (ok) snack(getString(R.string.toast_export_ok))
                else snack(getString(R.string.toast_export_failed, "I/O"))
            }
        }.start()
    }

    private fun snack(message: String) {
        Snackbar.make(root, message, Snackbar.LENGTH_LONG).show()
    }
}
