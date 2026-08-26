package com.nonamevpn.app.deploy

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
    val directPort: Int = 51820,
    val bypassPort: Int = 56003,
)

sealed class DeployAuth {
    data class Password(val password: String) : DeployAuth()
    data class Key(val pem: String, val passphrase: String = "") : DeployAuth()
}

fun DeployTarget.auth(): DeployAuth =
    if (privateKeyPem.isNotBlank()) DeployAuth.Key(privateKeyPem, keyPassphrase)
    else DeployAuth.Password(password)

sealed class DeployEvent {
    data class Progress(val fraction: Float, val step: String) : DeployEvent()
    data class Log(val line: String) : DeployEvent()
    data class Success(val message: String) : DeployEvent()
    data class Failure(val message: String) : DeployEvent()
}
