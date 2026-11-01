/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.regex_commit.ui

import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import org.fcitx.fcitx5.android.plugin.regex_commit.R
import org.fcitx.fcitx5.android.plugin.regex_commit.core.MatchDetail
import org.fcitx.fcitx5.android.plugin.regex_commit.core.Rule
import org.fcitx.fcitx5.android.plugin.regex_commit.core.RuleEngine
import org.fcitx.fcitx5.android.plugin.regex_commit.core.RuleScope
import org.fcitx.fcitx5.android.plugin.regex_commit.core.RuleStore
import org.fcitx.fcitx5.android.plugin.regex_commit.core.RuleValidator

class EditRuleActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_RULE_ID = "rule_id"
    }

    private lateinit var store: RuleStore
    private val engine = RuleEngine()

    private var ruleId: String = ""
    private var originalEnabled: Boolean = true

    private lateinit var nameField: TextInputLayout
    private lateinit var patternField: TextInputLayout
    private lateinit var replacementField: TextInputLayout
    private lateinit var packagesField: TextInputLayout
    private lateinit var whitelistSwitch: SwitchCompat
    private lateinit var applyOnceSwitch: SwitchCompat
    private lateinit var stopSwitch: SwitchCompat
    private lateinit var caseSwitch: SwitchCompat
    private lateinit var unescapeSwitch: SwitchCompat
    private lateinit var sampleField: TextInputLayout
    private lateinit var resultView: TextView
    private lateinit var matchesView: TextView
    private lateinit var errorView: TextView
    private lateinit var root: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = RuleStore.get(this)
        store.load()
        ruleId = intent.getStringExtra(EXTRA_RULE_ID) ?: ""
        val existing = store.current().rules.firstOrNull { it.id == ruleId }
        originalEnabled = existing?.enabled ?: true
        setContentView(buildContent(existing))
        updatePreview()
    }

    private fun buildContent(existing: Rule?): ScrollView {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(24))
        }

        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = getString(if (existing == null) R.string.edit_new_title else R.string.edit_edit_title)
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
        }
        titleRow.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val save = MaterialButton(this).apply {
            text = getString(R.string.action_save)
            setOnClickListener { saveRule() }
        }
        titleRow.addView(save)
        root.addView(titleRow)

        nameField = addField(
            root, getString(R.string.label_name), existing?.name ?: "",
            hint = getString(R.string.hint_name)
        )

        patternField = addField(
            root, getString(R.string.label_pattern), existing?.pattern ?: "",
            hint = getString(R.string.hint_pattern), mono = true, multiline = true
        )

        replacementField = addField(
            root, getString(R.string.label_replacement), existing?.replacement ?: "",
            hint = getString(R.string.hint_replacement), mono = true, multiline = true
        )

        packagesField = addField(
            root, getString(R.string.label_packages),
            existing?.scope?.packages?.joinToString("\n") ?: "",
            hint = getString(R.string.hint_packages), mono = true, multiline = true
        )

        whitelistSwitch = addSwitch(
            root, getString(R.string.label_whitelist),
            existing?.scope?.isWhitelist ?: false
        )
        applyOnceSwitch = addSwitch(
            root, getString(R.string.label_apply_once),
            existing?.applyOncePerCommit ?: true
        )
        stopSwitch = addSwitch(
            root, getString(R.string.label_stop_on_first),
            existing?.stopOnFirstMatch ?: false
        )
        caseSwitch = addSwitch(
            root, getString(R.string.label_case_insensitive),
            existing?.caseInsensitive ?: false
        )
        unescapeSwitch = addSwitch(
            root, getString(R.string.label_unescape),
            existing?.unescapeReplacement ?: false
        )

        root.addView(sectionTitle(getString(R.string.section_try)))

        sampleField = addField(
            root, getString(R.string.label_try_input), "",
            hint = getString(R.string.hint_try_input), multiline = true
        )

        errorView = TextView(this).apply {
            textSize = 13f
            setTextColor(getColor(R.color.rcf_error))
            setPadding(0, dp(4), 0, dp(4))
            visibility = TextView.GONE
        }
        root.addView(errorView)

        root.addView(sectionTitle(getString(R.string.label_try_output)))
        resultView = TextView(this).apply {
            textSize = 14f
            typeface = Typeface.MONOSPACE
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setBackgroundColor(getColor(R.color.rcf_mono_bg))
            text = getString(R.string.label_try_output_empty)
        }
        root.addView(resultView)

        root.addView(sectionTitle(getString(R.string.label_try_matches)))
        matchesView = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        root.addView(matchesView)

        val scroll = ScrollView(this)
        scroll.addView(root)
        return scroll
    }

    private fun sectionTitle(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 15f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(16), 0, dp(4))
    }

    private fun addField(
        parent: LinearLayout,
        label: String,
        value: String,
        hint: String,
        mono: Boolean = false,
        multiline: Boolean = false
    ): TextInputLayout {
        val layout = TextInputLayout(this).apply {
            this.hint = label
            setPadding(0, dp(6), 0, 0)
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
        }
        val edit = TextInputEditText(this).apply {
            setText(value)
            hint = hint
            if (mono) typeface = Typeface.MONOSPACE
            if (multiline) {
                inputType = inputType or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                minLines = 2
                gravity = android.view.Gravity.TOP or android.view.Gravity.START
            }
            doAfterTextChanged { updatePreview() }
        }
        layout.addView(edit)
        parent.addView(layout, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        return layout
    }

    private fun addSwitch(parent: LinearLayout, label: String, checked: Boolean): SwitchCompat {
        val sw = SwitchCompat(this).apply {
            text = label
            textSize = 14f
            isChecked = checked
            setPadding(0, dp(8), 0, dp(0))
            setOnCheckedChangeListener { _, _ -> updatePreview() }
        }
        parent.addView(sw, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        return sw
    }

    private fun draft(): Rule {
        val packages = packagesField.editText?.text?.toString()
            ?.split('\n', ',', ';', ' ')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.distinct()
            ?: emptyList()
        val mode = if (whitelistSwitch.isChecked) {
            RuleScope.MODE_WHITELIST
        } else {
            RuleScope.MODE_BLACKLIST
        }
        return Rule(
            id = ruleId,
            enabled = originalEnabled,
            name = nameField.editText?.text?.toString()?.trim() ?: "",
            pattern = patternField.editText?.text?.toString() ?: "",
            replacement = replacementField.editText?.text?.toString() ?: "",
            scope = RuleScope(mode = mode, packages = packages),
            applyOncePerCommit = applyOnceSwitch.isChecked,
            stopOnFirstMatch = stopSwitch.isChecked,
            caseInsensitive = caseSwitch.isChecked,
            unescapeReplacement = unescapeSwitch.isChecked
        )
    }

    private fun updatePreview() {
        if (!::errorView.isInitialized) return
        val rule = draft()
        val issues = RuleValidator.validate(rule)
        if (issues.isNotEmpty()) {
            errorView.visibility = TextView.VISIBLE
            errorView.text = IssueMessages.describeAll(this, issues)
            resultView.text = ""
            matchesView.text = ""
            return
        }
        errorView.visibility = TextView.GONE
        val sample = sampleField.editText?.text?.toString() ?: ""
        val preview = engine.preview(rule, sample)
        resultView.text = if (sample.isEmpty()) {
            getString(R.string.label_try_output_empty)
        } else {
            preview.output
        }
        matchesView.text = when {
            sample.isEmpty() -> ""
            preview.matches.isEmpty() -> getString(R.string.no_match)
            else -> buildMatches(preview.matches)
        }
    }

    private fun buildMatches(matches: List<MatchDetail>): String {
        val sb = StringBuilder()
        matches.forEach { match ->
            sb.append("#").append(match.index + 1)
                .append(" [").append(match.start).append(",").append(match.end).append(") ")
                .append(match.value).append('\n')
            match.groups.forEach { group ->
                sb.append("  ")
                    .append(getString(R.string.match_group_item, group.name, group.value))
                    .append('\n')
            }
        }
        return sb.toString().trimEnd()
    }

    private fun saveRule() {
        val rule = draft()
        val issues = RuleValidator.validate(rule)
        if (issues.isNotEmpty()) {
            patternField.error = IssueMessages.describeAll(this, issues)
            Snackbar.make(root, patternField.error ?: "", Snackbar.LENGTH_LONG).show()
            return
        }
        patternField.error = null
        val finalRule = if (rule.name.isBlank()) rule.copy(name = rule.pattern.take(40)) else rule
        store.upsert(finalRule)
        setResult(RESULT_OK)
        finish()
    }
}
