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
        assertTrue(cmd.contains("ardtt-host"))
        assertTrue(cmd.contains("awg0"))
        assertTrue(cmd.contains("lookup 51820"))
        assertTrue(cmd.contains(ServerUninstall.DONE_MARKER))
        assertTrue(cmd.contains("exit 0"))
        assertFalse(cmd.contains("bash /opt/ardtt/install.sh"))
    }

    @Test
    fun remoteCommandRemovesDockerSwapAndFirewallWhenHostIsOurs() {
        val cmd = ServerUninstall.remoteCommand()
        assertTrue(cmd.contains("foreign_docker_workloads"))
        assertTrue(cmd.contains("docker image prune -af"))
        assertTrue(cmd.contains("apt-get purge -y docker-ce"))
        assertTrue(cmd.contains("rm -rf /var/lib/docker"))
        assertTrue(cmd.contains("ip link del docker0"))
        assertTrue(cmd.contains("swapoff /swapfile"))
        assertTrue(cmd.contains("sed -i"))
        assertTrue(cmd.contains("ufw --force delete allow"))
        assertTrue(cmd.contains("host_drop_port"))
        assertTrue(cmd.contains("wait_apt_lock"))
        assertTrue(cmd.contains("docker-model-plugin"))
        assertTrue(cmd.contains("DOCKER-FORWARD"))
    }
}
