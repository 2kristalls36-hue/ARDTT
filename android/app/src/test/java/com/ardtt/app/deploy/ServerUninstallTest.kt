package com.ardtt.app.deploy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerUninstallTest {
    @Test
    fun remoteCommandRunsUploadedInstallerUninstall() {
        val cmd = ServerUninstall.remoteCommand()
        assertTrue(cmd.startsWith("bash -c "))
        assertTrue(cmd.contains("ARDTT_ACTION=uninstall"))
        assertTrue(cmd.contains("ARDTT_PURGE_DATA=1"))
        assertTrue(cmd.contains(DeployInstallEnv.DEPLOY_SCRIPT_REMOTE))
    }

    @Test
    fun remoteCommandHasNoHostWideCleanup() {
        val cmd = ServerUninstall.remoteCommand()
        for (bad in listOf("apt-get purge", "/var/lib/docker", "docker0", "swapfile", "prune", "compose down")) {
            assertFalse(bad, cmd.contains(bad))
        }
    }

    @Test
    fun keepDataVariant() {
        assertTrue(ServerUninstall.remoteCommand(purgeData = false).contains("ARDTT_PURGE_DATA=0"))
    }
}
