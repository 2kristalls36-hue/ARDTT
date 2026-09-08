package com.ardtt.app.deploy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerUninstallTest {
    @Test
    fun remoteCommandPrefersPackageInstaller() {
        val cmd = ServerUninstall.remoteCommand()
        assertTrue(cmd.startsWith("bash -c "))
        assertTrue(cmd.contains("ARDTT_ACTION=uninstall"))
        assertTrue(cmd.contains("bash /opt/ardtt/current/install.sh"))
        assertTrue(cmd.contains(ServerUninstall.DONE_MARKER) || cmd.contains("ARDTT_UNINSTALLED"))
        assertFalse(cmd.contains("apt-get purge"))
        assertFalse(cmd.contains("rm -rf /var/lib/docker"))
        assertFalse(cmd.contains("ip link del docker0"))
        assertFalse(cmd.contains("swapoff /swapfile"))
        assertFalse(cmd.contains("ufw --force delete"))
        assertFalse(cmd.contains("wipe_docker_netfilter"))
        assertFalse(cmd.contains("foreign_docker_workloads"))
    }

    @Test
    fun fallbackIsLabelScopedAndLeavesDockerEngine() {
        val fallback = ServerUninstall.FALLBACK_SCRIPT
        assertTrue(fallback.contains("com.ardtt.owner=ardtt"))
        assertTrue(fallback.contains("com.ardtt.instance="))
        assertTrue(fallback.contains("rm -rf /opt/ardtt /opt/nonamevpn"))
        assertTrue(fallback.contains(ServerUninstall.DONE_MARKER))
        assertFalse(fallback.contains("apt-get purge"))
        assertFalse(fallback.contains("/var/lib/docker"))
        assertFalse(fallback.contains("docker0"))
        assertFalse(fallback.contains("/swapfile"))
        assertFalse(fallback.contains("lookup 51820"))
        assertFalse(fallback.contains("awg0"))
    }

    @Test
    fun remoteCommandDoesNotPurgeDockerWhenHostLooksEmpty() {
        val cmd = ServerUninstall.remoteCommand()
        assertFalse(cmd.contains("docker image prune -af"))
        assertFalse(cmd.contains("docker-ce"))
        assertFalse(cmd.contains("/etc/containerd"))
        assertFalse(cmd.contains("host_drop_port"))
    }
}
