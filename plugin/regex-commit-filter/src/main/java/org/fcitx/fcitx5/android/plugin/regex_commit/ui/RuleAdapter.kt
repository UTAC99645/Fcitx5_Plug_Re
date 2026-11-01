/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.regex_commit.ui

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import org.fcitx.fcitx5.android.plugin.regex_commit.R
import org.fcitx.fcitx5.android.plugin.regex_commit.core.Rule

class RuleAdapter(
    private val onEdit: (Rule) -> Unit,
    private val onToggle: (Rule, Boolean) -> Unit,
    private val onMove: (Rule, Int) -> Unit,
    private val onDelete: (Rule) -> Unit
) : RecyclerView.Adapter<RuleAdapter.Holder>() {

    private val items = mutableListOf<Rule>()

    fun submit(list: List<Rule>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val ctx = parent.context
        val card = MaterialCardView(ctx).apply {
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = ctx.dp(4)
                bottomMargin = ctx.dp(4)
            }
            radius = ctx.dp(10).toFloat()
        }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ctx.dp(12), ctx.dp(10), ctx.dp(8), ctx.dp(10))
        }
        val toggle = SwitchCompat(ctx).apply {
            isClickable = true
        }
        row.addView(toggle, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = ctx.dp(8) })

        val textColumn = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val title = TextView(ctx).apply {
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val pattern = TextView(ctx).apply {
            textSize = 12f
            typeface = Typeface.MONOSPACE
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, ctx.dp(2), 0, ctx.dp(2))
        }
        val summary = TextView(ctx).apply {
            textSize = 11f
            alpha = 0.7f
            maxLines = 2
        }
        textColumn.addView(title)
        textColumn.addView(pattern)
        textColumn.addView(summary)
        row.addView(textColumn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val actions = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val up = action(ctx, "↑")
        val down = action(ctx, "↓")
        val delete = action(ctx, "✕")
        actions.addView(up)
        actions.addView(down)
        actions.addView(delete)
        row.addView(actions)

        card.addView(row)
        return Holder(card, toggle, title, pattern, summary, up, down, delete)
    }

    private fun action(ctx: Context, symbol: String): TextView = TextView(ctx).apply {
        text = symbol
        textSize = 16f
        gravity = Gravity.CENTER
        setPadding(ctx.dp(8), ctx.dp(3), ctx.dp(8), ctx.dp(3))
        isClickable = true
        isFocusable = true
        setTextColor(ctx.getColor(R.color.rcf_primary))
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val rule = items[position]
        holder.title.text = rule.name.ifBlank { rule.pattern }
        holder.pattern.text = rule.pattern
        holder.summary.text = buildSummary(holder.card.context, rule)

        holder.toggle.setOnCheckedChangeListener(null)
        holder.toggle.isChecked = rule.enabled
        holder.toggle.setOnCheckedChangeListener { _, checked ->
            if (checked != rule.enabled) onToggle(rule, checked)
        }

        holder.card.setOnClickListener { onEdit(rule) }
        holder.up.setOnClickListener { onMove(rule, -1) }
        holder.down.setOnClickListener { onMove(rule, 1) }
        holder.delete.setOnClickListener { onDelete(rule) }
    }

    private fun buildSummary(ctx: Context, rule: Rule): String {
        val parts = mutableListOf<String>()
        parts += when {
            rule.scope.packages.isEmpty() -> ctx.getString(R.string.item_scope_all)
            rule.scope.isWhitelist -> ctx.getString(
                R.string.item_scope_whitelist, rule.scope.packages.joinToString(", ")
            )
            else -> ctx.getString(
                R.string.item_scope_blacklist, rule.scope.packages.joinToString(", ")
            )
        }
        parts += ctx.getString(
            if (rule.applyOncePerCommit) R.string.item_mode_once else R.string.item_mode_all
        )
        if (rule.stopOnFirstMatch) parts += ctx.getString(R.string.item_stop_following)
        if (rule.caseInsensitive) parts += ctx.getString(R.string.item_case_insensitive)
        if (rule.unescapeReplacement) parts += ctx.getString(R.string.item_unescape)
        return parts.joinToString(" · ")
    }

    class Holder(
        val card: MaterialCardView,
        val toggle: SwitchCompat,
        val title: TextView,
        val pattern: TextView,
        val summary: TextView,
        val up: TextView,
        val down: TextView,
        val delete: TextView
    ) : RecyclerView.ViewHolder(card)
}
