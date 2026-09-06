package com.ardtt.app.deploy

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * Pull `install.sh` out of a GitHub stack/source gzip tarball so deploy does
 * not need a second HTTP fetch to raw.githubusercontent.com.
 */
object DeployStackArchive {
    private const val BLOCK = 512
    private const val MAX_INSTALL_BYTES = 2 * 1024 * 1024

    fun extractInstallScript(gzipTar: ByteArray): ByteArray? {
        if (!DeployStackFetcher.looksLikeGzip(gzipTar)) return null
        return runCatching {
            GZIPInputStream(ByteArrayInputStream(gzipTar)).use { gzip ->
                walkTar(gzip)
            }
        }.getOrNull()
    }

    private fun walkTar(input: InputStream): ByteArray? {
        var pendingName: String? = null
        while (true) {
            val header = ByteArray(BLOCK)
            if (!readExact(input, header)) return null
            if (header.all { it.toInt() == 0 }) return null
            val size = parseOctal(header, 124, 12)
            val type = (header[156].toInt() and 0xff).toChar()
            val name = pendingName ?: tarName(header)
            pendingName = null
            if (size < 0) return null
            if (size > MAX_INSTALL_BYTES) {
                skipFully(input, size + padding(size))
                continue
            }
            val data = ByteArray(size.toInt())
            if (size > 0 && !readExact(input, data)) return null
            skipFully(input, padding(size))
            when (type) {
                'L' -> pendingName = data.decodeToString().trimEnd('\u0000')
                '0', '\u0000' -> {
                    if (isInstallPath(name) && DeployStackFetcher.looksLikeInstaller(data)) {
                        return data
                    }
                }
            }
        }
    }

    internal fun isInstallPath(name: String): Boolean {
        val n = name.replace('\\', '/').trim().removePrefix("./")
        return n == "install.sh" || n.endsWith("/install.sh")
    }

    private fun tarName(header: ByteArray): String {
        val name = cString(header, 0, 100)
        val magic = cString(header, 257, 6)
        val prefix = if (magic.startsWith("ustar")) cString(header, 345, 155) else ""
        return if (prefix.isNotEmpty()) "$prefix/$name" else name
    }

    private fun cString(bytes: ByteArray, offset: Int, length: Int): String {
        var end = offset
        val stop = offset + length
        while (end < stop && bytes[end] != 0.toByte()) end++
        return bytes.decodeToString(offset, end)
    }

    private fun parseOctal(header: ByteArray, offset: Int, length: Int): Long {
        val raw = cString(header, offset, length).trim()
        if (raw.isEmpty()) return 0
        return raw.toLongOrNull(8) ?: -1
    }

    private fun padding(size: Long): Long = (BLOCK - (size % BLOCK)) % BLOCK

    private fun readExact(input: InputStream, dest: ByteArray): Boolean {
        var off = 0
        while (off < dest.size) {
            val n = input.read(dest, off, dest.size - off)
            if (n < 0) return false
            off += n
        }
        return true
    }

    private fun skipFully(input: InputStream, count: Long) {
        var left = count
        val buf = ByteArray(BLOCK)
        while (left > 0) {
            val n = input.read(buf, 0, minOf(left, buf.size.toLong()).toInt())
            if (n < 0) return
            left -= n
        }
    }
}
