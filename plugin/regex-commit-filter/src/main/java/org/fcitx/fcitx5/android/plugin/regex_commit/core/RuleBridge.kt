/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.regex_commit.core

import android.os.Build
import android.net.LocalSocket
import android.net.LocalSocketAddress
import org.fcitx.fcitx5.android.plugin.regex_commit.BuildConfig
import java.io.File

data class BridgeResult(val ok: Boolean, val message: String)

/**
 * Pushes the ruleset to the native addon that runs inside the main app.
 *
 * The main app's addon listens on an abstract Unix socket whose name is derived
 * from the main application id, so no IPC permission or main-app patch is
 * needed. One request per connection: the client writes JSON, half-closes,
 * reads the reply until EOF.
 */
object RuleBridge {

    private const val SOCKET_SUFFIX = ".regex_commit.rules"
    private const val MAX_REPLY_BYTES = 256 * 1024
    private const val REQUEST_TIMEOUT_MS = 1500

    fun socketName(): String = BuildConfig.MAIN_APPLICATION_ID + SOCKET_SUFFIX

    fun push(json: String): BridgeResult =
        runCatching { pushInternal(json) }.getOrElse {
            BridgeResult(false, it.javaClass.simpleName)
        }

    private fun pushInternal(json: String): BridgeResult {
        val name = socketName()
        val socket = LocalSocket()
        try {
            socket.connect(address(name))
            socket.soTimeout = REQUEST_TIMEOUT_MS
            // After a local socket is connected, streams are available.
            val out = socket.outputStream
            out.write(json.toByteArray(Charsets.UTF_8))
            out.flush()
            socket.shutdownOutput()
            val reply = readReply(socket.inputStream)
            if (reply.isBlank()) {
                return BridgeResult(false, "empty reply")
            }
            val ok = reply.contains("\"ok\":true")
            return BridgeResult(ok, if (ok) "" else reply.take(512))
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun readReply(input: java.io.InputStream): String {
        val buffer = ByteArray(MAX_REPLY_BYTES)
        val sb = StringBuilder()
        while (sb.length < MAX_REPLY_BYTES) {
            val read = input.read(buffer)
            if (read <= 0) break
            sb.append(String(buffer, 0, read, Charsets.UTF_8))
        }
        return sb.toString()
    }

    @Suppress("DEPRECATION")
    private fun address(name: String): LocalSocketAddress {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                return LocalSocketAddress(name, LocalSocketAddress.Namespace.ABSTRACT)
            }
        }
        // Pre-Q: a leading NUL makes the kernel treat sun_path as abstract.
        return LocalSocketAddress("\u0000$name")
    }

    /** File used for the plugin-local copy of the rules. */
    fun rulesFile(dir: File): File = File(dir, "rules.json")
}
