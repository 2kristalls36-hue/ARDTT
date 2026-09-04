package com.ardtt.app.bypass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallHashPersistenceTest {

    @Test
    fun deviceKeySurvivesProfileRename() {
        val hash = "abcdefghijklmnopqrstuvwxyz123456"
        val stored = mapOf(
            CallHashPersistence.profileKey("old-name") to hash,
            CallHashPersistence.DEVICE_KEY to hash,
        )
        assertEquals(
            hash,
            CallHashPersistence.resolve("new-imported-profile", stored),
        )
    }

    @Test
    fun oldProfileKeyedHashIsFoundWithoutDeviceKey() {
        val hash = "abcdefghijklmnopqrstuvwxyz123456"
        val stored = mapOf(CallHashPersistence.profileKey("alice") to hash)
        assertEquals(hash, CallHashPersistence.resolve("bob", stored))
        assertEquals(hash, CallHashPersistence.resolve(null, stored))
    }

    @Test
    fun fileBackupUsedWhenPrefsEmpty() {
        val hash = "abcdefghijklmnopqrstuvwxyz123456"
        assertEquals(hash, CallHashPersistence.resolve("alice", emptyMap(), hash))
        assertNull(CallHashPersistence.resolve("alice", emptyMap(), "   "))
    }

    @Test
    fun currentProfileNameWinsOverStaleKeys() {
        val stored = mapOf(
            CallHashPersistence.profileKey("alice") to "alicehashalicehashalicehash12",
            CallHashPersistence.profileKey("bob") to "bobhashbobhashbobhashbobhash12",
            CallHashPersistence.DEVICE_KEY to "devicehashdevicehashdeviceha12",
        )
        assertEquals(
            "alicehashalicehashalicehash12",
            CallHashPersistence.resolve("alice", stored),
        )
    }

    @Test
    fun keysToWriteAlwaysIncludeDeviceAndNamedProfile() {
        val keys = CallHashPersistence.keysToWrite("MTS")
        assertEquals(
            listOf(CallHashPersistence.DEVICE_KEY, CallHashPersistence.profileKey("MTS")),
            keys,
        )
        assertEquals(listOf(CallHashPersistence.DEVICE_KEY), CallHashPersistence.keysToWrite("  "))
        assertEquals(listOf(CallHashPersistence.DEVICE_KEY), CallHashPersistence.keysToWrite(null))
    }

    @Test
    fun blankStoredValuesAreIgnored() {
        assertNull(
            CallHashPersistence.resolve(
                "alice",
                mapOf(CallHashPersistence.profileKey("alice") to "  "),
                fileBackup = null,
            ),
        )
        assertTrue(CallHashPersistence.PREFS_NAME.isNotBlank())
        assertTrue(CallHashPersistence.BACKUP_FILE_NAME.endsWith(".txt"))
    }
}
