/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.regex_commit.core

import com.google.re2j.Matcher

/**
 * Replacement template expansion, mirroring the C++ implementation:
 * `$$` -> `$`, `$1` / `${1}` -> numbered group, `${name}` -> named group,
 * optionally `\n`, `\r`, `\t`, `\\` when [unescape] is enabled.
 * No evaluation of any kind is performed.
 */
object ReplacementExpander {

    fun expand(template: String, matcher: Matcher, unescape: Boolean): String {
        val sb = StringBuilder(template.length)
        var i = 0
        while (i < template.length) {
            val c = template[i]
            when {
                c == '\\' && unescape -> {
                    if (i + 1 >= template.length) {
                        sb.append(c)
                        i++
                    } else {
                        when (val n = template[i + 1]) {
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            '0' -> sb.append('\u0000')
                            '\\' -> sb.append('\\')
                            else -> sb.append('\\').append(n)
                        }
                        i += 2
                    }
                }
                c == '$' -> {
                    if (i + 1 >= template.length) {
                        sb.append('$')
                        i++
                    } else {
                        val n = template[i + 1]
                        when {
                            n == '$' -> {
                                sb.append('$')
                                i += 2
                            }
                            n == '{' -> {
                                val end = template.indexOf('}', i + 2)
                                if (end < 0) {
                                    sb.append(template, i, template.length)
                                    i = template.length
                                } else {
                                    sb.append(group(template.substring(i + 2, end), matcher))
                                    i = end + 1
                                }
                            }
                            n.isDigit() -> {
                                var j = i + 1
                                while (j < template.length && template[j].isDigit()) j++
                                sb.append(group(template.substring(i + 1, j), matcher))
                                i = j
                            }
                            else -> {
                                sb.append('$')
                                i++
                            }
                        }
                    }
                }
                else -> {
                    sb.append(c)
                    i++
                }
            }
        }
        return sb.toString()
    }

    private fun group(key: String, matcher: Matcher): String {
        return runCatching {
            val index = key.toIntOrNull()
            if (index != null) matcher.group(index) else matcher.group(key)
        }.getOrNull() ?: ""
    }
}
