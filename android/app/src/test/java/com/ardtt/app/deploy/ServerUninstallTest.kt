package com.ardtt.app.deploy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerUninstallTest {
    @Test
    fun remoteCommandWipesStackAndSignalsDone() {
        val cmd = ServerUninstall.remoteCommand()
        assertTrue(cmd.startsWith("bash -c "))
        assertTrue(cmd.contains("docker compose down"))
        assertTrue(cmd.contains("--remove-orphans"))
        assertTrue(cmd.contains("rm -rf /opt/ardtt /opt/nonamevpn"))
        assertTrue(cmd.contains("ardtt-provision"))
        assertTrue(cmd.contains("nvpn-provision"))
        assertTrue(cmd.contains(ServerUninstall.DONE_MARKER))
        assertTrue(cmd.contains("exit 0"))
        assertFalse(cmd.contains("install.sh"))
    }
}
