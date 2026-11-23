package com.ardtt.app.deploy

/**
 * Client-side removal of one ARDTT instance (deploy v2). The app uploads its
 * ardtt-deploy.sh and runs `ARDTT_ACTION=uninstall`: instance-scoped by Docker
 * labels and /opt/ardtt. Docker Engine, other containers, networks and
 * firewall rules stay as they are.
 */
object ServerUninstall {
    const val DONE_MARKER = "ARDTT_UNINSTALLED"
    const val TIMEOUT_MS = 10 * 60_000L

    fun remoteCommand(purgeData: Boolean = true): String =
        "bash -c ${SshClient.shellQuote(DeployInstallEnv.uninstallCommand(purgeData = purgeData))}"
}
