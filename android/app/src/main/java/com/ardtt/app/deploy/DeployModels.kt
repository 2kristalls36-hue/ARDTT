package com.ardtt.app.deploy

data class DeployTarget(
    val id: String,
    val name: String,
    val host: String,
    val sshPort: Int = 22,
    val sshUser: String = "root",
    /** Password auth; empty if using key. */
    val password: String = "",
    /** PEM private key; empty if using password. */
    val privateKeyPem: String = "",
    val keyPassphrase: String = "",
    /** sudo password when user is not root (often same as password). */
    val sudoPassword: String = "",
    val publicHost: String = "",
    /** When true, install.sh picks free UDP ports on the VPS (defaults as preferred). */
    val autoPorts: Boolean = true,
    val directPort: Int = 51820,
    val bypassPort: Int = 56003,
    val provisionPort: Int = 9100,
    val telemetryPort: Int = 9200,
    /** linux amd64/arm64 of the last probed VPS; empty when unknown. */
    val arch: String = "",
    /** Second VPS: egress hop (AWG + DNS + WARP). Phone SSHs to it through the entry VPS. */
    val cascadeEnabled: Boolean = false,
    val cascadeHost: String = "",
    val cascadePort: Int = 22,
    val cascadeUser: String = "root",
    val cascadePassword: String = "",
    /** PEM for the exit VPS; empty if that hop uses a password. */
    val cascadePrivateKeyPem: String = "",
    val cascadeKeyPassphrase: String = "",
    val cascadeProvisionPort: Int = 9100,
    val cascadeTelemetryPort: Int = 9200,
    val cascadeArch: String = "",
    /** Lowercase linux distro id (e.g. ubuntu/debian), empty when unknown. */
    val osId: String = "",
    /** Human-friendly OS version label (usually PRETTY_NAME). */
    val osVersion: String = "",
    /** Epoch ms of last successful deploy from this app; 0 = unknown. */
    val lastDeployedAtMs: Long = 0L,
)

sealed class DeployAuth {
    data class Password(val password: String) : DeployAuth()
    data class Key(val pem: String, val passphrase: String = "") : DeployAuth()
}

fun DeployTarget.auth(): DeployAuth =
    if (privateKeyPem.isNotBlank()) DeployAuth.Key(privateKeyPem, keyPassphrase)
    else DeployAuth.Password(password)

fun DeployTarget.cascadeAuth(): DeployAuth =
    if (cascadePrivateKeyPem.isNotBlank()) DeployAuth.Key(cascadePrivateKeyPem, cascadeKeyPassphrase)
    else DeployAuth.Password(cascadePassword)

fun DeployTarget.cascadeSshUser(): String = cascadeUser.trim().ifBlank { "root" }

enum class DeployJobKind {
    Install,
    Update,
    Uninstall,
    Preflight,
}

sealed class DeployEvent {
    data class Progress(val fraction: Float, val step: String) : DeployEvent()
    data class Log(val line: String) : DeployEvent()
    data class Success(val message: String) : DeployEvent()
    data class Failure(val message: String) : DeployEvent()
}

/** Result of a single-hop install.sh run (cascade key + resolved host ports). */
data class DeployInstallResult(
    val cascadePublicKey: String = "",
    val directPort: Int? = null,
    val bypassPort: Int? = null,
    val cascadeListenPort: Int? = null,
    val provisionPort: Int? = null,
    val telemetryPort: Int? = null,
    val instanceId: String = "",
    val containerName: String = "",
)
