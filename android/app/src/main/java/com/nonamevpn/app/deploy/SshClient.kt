package com.nonamevpn.app.deploy

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Properties

class SshClient(
    private val session: Session,
    private val sudoPassword: String = "",
) {
    fun exec(command: String, timeoutMs: Long = 900_000L): String {
        check(session.isConnected) { "SSH session down" }
        var channel: ChannelExec? = null
        return try {
            channel = session.openChannel("exec") as ChannelExec
            val remote = elevate(command)
            channel.setCommand(remote)
            val stdout = ByteArrayOutputStream()
            val stderr = ByteArrayOutputStream()
            channel.outputStream = stdout
            channel.setErrStream(stderr)
            channel.connect(15_000)
            val deadline = System.currentTimeMillis() + timeoutMs
            while (!channel.isClosed) {
                if (System.currentTimeMillis() > deadline) {
                    error("SSH command timeout")
                }
                Thread.sleep(200)
            }
            val out = stdout.toString(Charsets.UTF_8.name())
            val err = stderr.toString(Charsets.UTF_8.name())
            if (channel.exitStatus != 0) {
                error("exit=${channel.exitStatus}: ${(err.ifBlank { out }).take(800)}")
            }
            (out + err).trim()
        } finally {
            runCatching { channel?.disconnect() }
        }
    }

    /** Stream command output line-by-line (for progress). */
    fun execStreaming(command: String, timeoutMs: Long = 900_000L, onLine: (String) -> Unit): Int {
        check(session.isConnected) { "SSH session down" }
        var channel: ChannelExec? = null
        return try {
            channel = session.openChannel("exec") as ChannelExec
            channel.setCommand(elevate(command))
            val lineSink = LineOutputStream(onLine)
            channel.outputStream = lineSink
            channel.setErrStream(lineSink)
            channel.connect(15_000)
            val deadline = System.currentTimeMillis() + timeoutMs
            while (!channel.isClosed) {
                if (System.currentTimeMillis() > deadline) error("SSH command timeout")
                Thread.sleep(200)
            }
            lineSink.flushLine()
            channel.exitStatus
        } finally {
            runCatching { channel?.disconnect() }
        }
    }

    /**
     * Decode SSH bytes as UTF-8 lines. The old byte→Char path broke Cyrillic
     * (e.g. «Сборка и запуск Compose…» became Hangul/symbol garbage).
     */
    private class LineOutputStream(private val onLine: (String) -> Unit) : java.io.OutputStream() {
        private val buf = ByteArrayOutputStream(512)

        override fun write(b: Int) {
            when (b) {
                '\n'.code -> flushLine()
                '\r'.code -> Unit
                else -> buf.write(b)
            }
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            var i = off
            val end = off + len
            while (i < end) {
                write(b[i].toInt() and 0xff)
                i++
            }
        }

        fun flushLine() {
            if (buf.size() == 0) return
            val raw = buf.toString(Charsets.UTF_8.name())
            buf.reset()
            val cleaned = stripAnsi(raw).trimEnd()
            if (cleaned.isNotEmpty()) onLine(cleaned)
        }

        companion object {
            private val ANSI_RE = Regex("\u001B\\[[0-9;?]*[A-Za-z]|\u001B\\].*?\u0007|\u001B.")

            fun stripAnsi(s: String): String = ANSI_RE.replace(s, "")
        }
    }

    fun upload(local: File, remotePath: String) {
        check(session.isConnected) { "SSH session down" }
        var sftp: ChannelSftp? = null
        try {
            sftp = session.openChannel("sftp") as ChannelSftp
            sftp.connect(15_000)
            sftp.put(local.absolutePath, remotePath)
        } finally {
            runCatching { sftp?.disconnect() }
        }
    }

    fun uploadBytes(data: ByteArray, remotePath: String) {
        val tmp = File.createTempFile("nvpn-up", ".bin")
        try {
            tmp.writeBytes(data)
            upload(tmp, remotePath)
        } finally {
            tmp.delete()
        }
    }

    private fun elevate(command: String): String {
        if (session.userName == "root") return command
        val pass = sudoPassword.ifBlank { "" }
        val quoted = shellQuote(command)
        return if (pass.isNotBlank()) {
            "echo ${shellQuote(pass)} | sudo -S -p '' bash -c $quoted"
        } else {
            "sudo -n bash -c $quoted"
        }
    }

    companion object {
        fun connect(host: String, user: String, port: Int, auth: DeployAuth): Session {
            val jsch = JSch()
            when (auth) {
                is DeployAuth.Key -> {
                    val pass = auth.passphrase.takeIf { it.isNotBlank() }?.toByteArray(Charsets.UTF_8)
                    jsch.addIdentity("nvpn-deploy", auth.pem.toByteArray(Charsets.UTF_8), null, pass)
                }
                is DeployAuth.Password -> Unit
            }
            val session = jsch.getSession(user, host, port)
            if (auth is DeployAuth.Password) {
                session.setPassword(auth.password)
            }
            session.setConfig(
                Properties().apply {
                    put("StrictHostKeyChecking", "no")
                    // Long Compose builds on ~1 GiB VPS can stall stdout for minutes.
                    put("ServerAliveInterval", "15")
                    put("ServerAliveCountMax", "40")
                    put("PreferredAuthentications", when (auth) {
                        is DeployAuth.Key -> "publickey"
                        is DeployAuth.Password -> "password,keyboard-interactive"
                    })
                },
            )
            session.connect(20_000)
            return session
        }

        fun shellQuote(value: String): String =
            "'" + value.replace("'", "'\"'\"'") + "'"
    }
}
