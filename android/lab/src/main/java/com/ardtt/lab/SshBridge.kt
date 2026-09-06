package com.ardtt.lab

import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.SocketFactory
import com.jcraft.jsch.UserInfo
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Properties

class SshBridge(
    private val binder: CellularBinder,
    private val onLog: (String) -> Unit,
) {
    @Volatile
    private var session: Session? = null
    @Volatile
    private var remotePort: Int = 0

    fun connected(): Boolean = session?.isConnected == true

    fun connect(target: LabTarget, localPort: Int) {
        disconnect()
        val jsch = JSch()
        val sess = jsch.getSession(target.user, target.host, target.sshPort)
        sess.setPassword(target.password)
        sess.userInfo = object : UserInfo {
            override fun getPassword(): String = target.password
            override fun promptPassword(message: String?): Boolean = true
            override fun getPassphrase(): String? = null
            override fun promptPassphrase(message: String?): Boolean = false
            override fun promptYesNo(message: String?): Boolean = true
            override fun showMessage(message: String?) = Unit
        }
        sess.setSocketFactory(object : SocketFactory {
            override fun createSocket(host: String, port: Int): Socket {
                val socket = Socket()
                runCatching { binder.bindSocket(socket) }
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(host, port), 20_000)
                return socket
            }

            override fun getInputStream(socket: Socket): InputStream = socket.getInputStream()
            override fun getOutputStream(socket: Socket): OutputStream = socket.getOutputStream()
        })
        sess.setConfig(
            Properties().apply {
                put("StrictHostKeyChecking", "no")
                put("ServerAliveInterval", "15")
                put("ServerAliveCountMax", "8")
                put("PreferredAuthentications", "password,keyboard-interactive")
            },
        )
        onLog("SSH ${target.user}@${target.host}:${target.sshPort}…")
        sess.connect(20_000)
        sess.setPortForwardingR(
            "127.0.0.1",
            target.remotePort,
            "127.0.0.1",
            localPort,
        )
        publishPortFile(sess, target.remotePort)
        remotePort = target.remotePort
        session = sess
        onLog("реверс 127.0.0.1:${target.remotePort} → телефон :$localPort")
    }

    fun disconnect() {
        val sess = session
        val port = remotePort
        session = null
        remotePort = 0
        if (sess != null) {
            if (port > 0) runCatching { sess.delPortForwardingR(port) }
            runCatching { sess.disconnect() }
        }
    }

    private fun publishPortFile(sess: Session, remotePort: Int) {
        val channel = runCatching { sess.openChannel("exec") as com.jcraft.jsch.ChannelExec }
            .getOrNull() ?: return
        try {
            channel.setCommand("printf '%s\\n' '$remotePort' > ${LabProtocol.PORT_FILE}")
            channel.connect(8_000)
            var waited = 0
            while (!channel.isClosed && waited < 5_000) {
                Thread.sleep(100)
                waited += 100
            }
        } catch (_: Exception) {
        } finally {
            runCatching { channel.disconnect() }
        }
    }
}
