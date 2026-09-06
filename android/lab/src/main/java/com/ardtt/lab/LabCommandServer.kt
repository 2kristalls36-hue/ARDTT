package com.ardtt.lab

import android.content.Context
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class LabCommandServer(
    private val context: Context,
    private val binder: CellularBinder,
    private val onLog: (String) -> Unit,
    private val onAgent: () -> Unit,
    private val screenshot: () -> ByteArray?,
) {
    private val running = AtomicBoolean(false)
    private val clients = CopyOnWriteArrayList<Client>()
    private val pool = Executors.newCachedThreadPool()
    private var server: ServerSocket? = null
    var localPort: Int = 0
        private set

    fun start(): Int {
        if (running.get()) return localPort
        val sock = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        server = sock
        localPort = sock.localPort
        running.set(true)
        thread(name = "ardtt-lab-accept", isDaemon = true) {
            while (running.get()) {
                val client = runCatching { sock.accept() }.getOrNull() ?: break
                pool.execute { handle(client) }
            }
        }
        return localPort
    }

    fun stop() {
        running.set(false)
        clients.forEach { it.close() }
        clients.clear()
        runCatching { server?.close() }
        server = null
    }

    fun broadcast(line: String) {
        clients.forEach { it.write(line) }
    }

    private fun handle(socket: Socket) {
        val client = Client(socket)
        clients.add(client)
        onAgent()
        onLog("агент подключился")
        try {
            var line = client.readLine()
            while (line != null && running.get()) {
                dispatch(line, client)
                line = client.readLine()
            }
        } catch (_: Exception) {
        } finally {
            clients.remove(client)
            client.close()
            onLog("агент отключился")
        }
    }

    private fun dispatch(line: String, client: Client) {
        val req = LabProtocol.parseLine(line)
        if (req == null) {
            client.write(LabProtocol.error("0", "не JSON / нет cmd"))
            return
        }
        onLog("агент: ${req.cmd}")
        val reply = try {
            handleRequest(req)
        } catch (err: Exception) {
            LabProtocol.error(req.id, err.message ?: err.javaClass.simpleName)
        }
        client.write(reply)
    }

    private fun handleRequest(req: LabRequest): String {
        return when (req.cmd) {
            "ping" -> LabProtocol.reply(req.id, true, mapOf("pong" to true))
            "status" -> {
                val snap = DeviceSnapshot.capture(context, binder)
                LabProtocol.reply(req.id, true, snap.toMap())
            }
            "probe" -> {
                val vps = req.fields.optString("vps").ifBlank { "" }
                val port = req.fields.optInt("port", 9100)
                val yandex = binder.tcp("77.88.8.8", 443, 700)
                val cloudflare = binder.tcp("1.1.1.1", 443, 700)
                val vpsOk = if (vps.isNotBlank()) binder.tcp(vps, port, 900) else null
                LabProtocol.reply(
                    req.id,
                    true,
                    mapOf(
                        "yandex_443" to yandex,
                        "cloudflare_443" to cloudflare,
                        "vps" to vps,
                        "vps_port" to port,
                        "vps_ok" to vpsOk,
                        "looks_like_whitelist" to (yandex && !cloudflare),
                        "bind" to "cellular_or_default",
                    ),
                )
            }
            "launch_ardtt" -> {
                val err = launchArdtt(context)
                if (err == null) LabProtocol.reply(req.id, true)
                else LabProtocol.error(req.id, err)
            }
            "screenshot" -> {
                val png = screenshot()
                    ?: return LabProtocol.error(
                        req.id,
                        "нет разрешения на захват экрана — нажмите «Экран» в Lab",
                    )
                val b64 = android.util.Base64.encodeToString(png, android.util.Base64.NO_WRAP)
                LabProtocol.reply(req.id, true, mapOf("png_b64" to b64, "bytes" to png.size))
            }
            "watch" -> {
                val snap = DeviceSnapshot.capture(context, binder)
                LabProtocol.reply(req.id, true, snap.toMap() + mapOf("watching" to true))
            }
            else -> LabProtocol.error(req.id, "неизвестная команда ${req.cmd}")
        }
    }

    private class Client(private val socket: Socket) {
        private val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
        private val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))

        fun readLine(): String? = reader.readLine()

        @Synchronized
        fun write(line: String) {
            runCatching {
                writer.write(line)
                writer.write("\n")
                writer.flush()
            }
        }

        fun close() {
            runCatching { socket.close() }
        }
    }
}
