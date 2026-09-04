package com.ardtt.app.deploy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeployAuthTest {
    private fun target(
        cascadeUser: String = "",
        cascadePassword: String = "",
        cascadePrivateKeyPem: String = "",
        cascadeKeyPassphrase: String = "",
    ) = DeployTarget(
        id = "1",
        name = "s",
        host = "10.0.0.1",
        cascadeEnabled = true,
        cascadeHost = "2.26.125.160",
        cascadeUser = cascadeUser,
        cascadePassword = cascadePassword,
        cascadePrivateKeyPem = cascadePrivateKeyPem,
        cascadeKeyPassphrase = cascadeKeyPassphrase,
    )

    @Test
    fun cascadeUserFallsBackToRoot() {
        assertEquals("root", target(cascadeUser = "").cascadeSshUser())
        assertEquals("root", target(cascadeUser = "  ").cascadeSshUser())
        assertEquals("deploy", target(cascadeUser = "deploy").cascadeSshUser())
    }

    @Test
    fun cascadeAuthPrefersPemLikeEntry() {
        val key = target(
            cascadePrivateKeyPem = "-----BEGIN OPENSSH PRIVATE KEY-----\n",
            cascadeKeyPassphrase = "phrase",
            cascadePassword = "ignored",
        ).cascadeAuth()
        assertTrue(key is DeployAuth.Key)
        assertEquals("phrase", (key as DeployAuth.Key).passphrase)

        val password = target(cascadePassword = "secret").cascadeAuth()
        assertTrue(password is DeployAuth.Password)
        assertEquals("secret", (password as DeployAuth.Password).password)
    }
}
