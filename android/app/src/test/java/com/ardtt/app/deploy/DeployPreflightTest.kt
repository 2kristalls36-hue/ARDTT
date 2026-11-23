package com.ardtt.app.deploy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeployPreflightTest {
    @Test
    fun parseDockerMissingIsOkPackageWillInstall() {
        val parsed = DeployPreflight.parse(
            """
            ARDTT_PREFLIGHT|os_id=ubuntu
            ARDTT_PREFLIGHT|os_ver=26.04
            ARDTT_PREFLIGHT|arch=x86_64
            ARDTT_PREFLIGHT|python=1
            ARDTT_PREFLIGHT|tun=1
            ARDTT_PREFLIGHT|iptables=1
            ARDTT_PREFLIGHT|docker=missing
            ARDTT_PREFLIGHT_DONE|ok=1|code=OK|message=docker-from-distro
            """.trimIndent(),
        )
        assertTrue(parsed.ok)
        assertEquals(null, parsed.code)
        assertEquals("ubuntu", parsed.osId)
        assertEquals("26.04", parsed.osVersion)
        assertEquals("missing", parsed.docker)
        assertFalse(parsed.dockerPresent)
        assertFalse(parsed.iptablesMissing)
        assertTrue(parsed.python)
        assertTrue(parsed.tun)
    }

    @Test
    fun parseForeignRuntimeIsNotACleanHost() {
        val parsed = DeployPreflight.parse(
            """
            ARDTT_PREFLIGHT|os_id=ubuntu
            ARDTT_PREFLIGHT|podman=1
            ARDTT_PREFLIGHT_DONE|ok=0|code=UNSUPPORTED_RUNTIME|message=foreign-runtime
            """.trimIndent(),
        )
        assertFalse(parsed.ok)
        assertEquals(DeployIssue.UNSUPPORTED_RUNTIME, parsed.code)
        assertEquals("1", parsed.fields["podman"])
    }

    @Test
    fun parseDockerAccessAndDaemon() {
        val denied = DeployPreflight.parse(
            "ARDTT_PREFLIGHT|docker=present\nARDTT_PREFLIGHT_DONE|ok=0|code=DOCKER_ACCESS_DENIED|message=docker-access",
        )
        assertEquals(DeployIssue.DOCKER_ACCESS_DENIED, denied.code)
        assertEquals("present", denied.docker)

        val down = DeployPreflight.parse(
            "ARDTT_PREFLIGHT|docker=present\nARDTT_PREFLIGHT_DONE|ok=0|code=DOCKER_NOT_RUNNING|message=docker-daemon",
        )
        assertEquals(DeployIssue.DOCKER_NOT_RUNNING, down.code)
    }

    @Test
    fun parseReadyHost() {
        val parsed = DeployPreflight.parse(
            """
            ARDTT_PREFLIGHT|os_id=ubuntu
            ARDTT_PREFLIGHT|os_ver=24.04
            ARDTT_PREFLIGHT|arch=aarch64
            ARDTT_PREFLIGHT|python=1
            ARDTT_PREFLIGHT|tun=1
            ARDTT_PREFLIGHT|docker=present
            ARDTT_PREFLIGHT|docker_version=27.0.3
            ARDTT_PREFLIGHT_DONE|ok=1|code=OK|message=ready
            """.trimIndent(),
        )
        assertTrue(parsed.ok)
        assertEquals(null, parsed.code)
        assertEquals("ok", parsed.docker)
        assertEquals("27.0.3", parsed.dockerVersion)
        assertEquals("aarch64", parsed.arch)
        // iptables is probed only when Docker is absent; no key means not missing.
        assertFalse(parsed.iptablesMissing)
    }

    @Test
    fun remoteScriptProbesDistroDockerNotPython() {
        val script = DeployPreflight.REMOTE_SCRIPT
        assertTrue(script.contains("echo \"ARDTT_PREFLIGHT|os_id="))
        assertTrue(script.contains("docker-from-distro"))
        assertTrue(script.contains("code=DOCKER_MISSING"))
        assertFalse(script.contains("PYTHON_MISSING"))
        assertFalse(script.contains("docker-from-package"))
    }

    @Test
    fun remoteScriptIsValidBash() {
        val proc = ProcessBuilder("bash", "-n", "-c", DeployPreflight.REMOTE_SCRIPT)
            .redirectErrorStream(true)
            .start()
        val out = proc.inputStream.bufferedReader().readText()
        assertEquals("bash -n: $out", 0, proc.waitFor())
    }

    @Test
    fun parseNoDistroDockerIsFailure() {
        // RHEL/Alma/Rocky: dnf without a Docker package in the distribution.
        val parsed = DeployPreflight.parse(
            "ARDTT_PREFLIGHT|os_id=almalinux\nARDTT_PREFLIGHT|docker=missing\n" +
                "ARDTT_PREFLIGHT_DONE|ok=0|code=DOCKER_MISSING|message=no-distro-docker",
        )
        assertFalse(parsed.ok)
        assertEquals(DeployIssue.DOCKER_MISSING, parsed.code)
    }

    @Test
    fun parseDockerFromDistroIsOk() {
        val parsed = DeployPreflight.parse(
            "ARDTT_PREFLIGHT|docker=missing\nARDTT_PREFLIGHT|docker_pkg=docker.io\n" +
                "ARDTT_PREFLIGHT_DONE|ok=1|code=OK|message=docker-from-distro",
        )
        assertTrue(parsed.ok)
        assertEquals("docker.io", parsed.fields["docker_pkg"])
    }
}
