package com.nonamevpn.app.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TunReuseTest {
    @Test
    fun reusesWhenIpDnsMtuMatch() {
        assertTrue(
            canReuseBypassTun(
                existingValid = true,
                lastIp = "10.9.0.5",
                lastDns = "10.9.0.1",
                lastMtu = 1300,
                ip = "10.9.0.5",
                dns = "10.9.0.1",
                mtu = 1300,
            ),
        )
        assertTrue(
            canReuseBypassTun(
                existingValid = true,
                lastIp = "10.9.0.5",
                lastDns = "10.9.0.1",
                lastMtu = 1300,
                ip = "10.9.0.5/32",
                dns = "10.9.0.1",
                mtu = 1300,
            ),
        )
    }

    @Test
    fun rebuildsWhenAnythingChanges() {
        assertFalse(
            canReuseBypassTun(
                existingValid = false,
                lastIp = "10.9.0.5",
                lastDns = "10.9.0.1",
                lastMtu = 1300,
                ip = "10.9.0.5",
                dns = "10.9.0.1",
                mtu = 1300,
            ),
        )
        assertFalse(
            canReuseBypassTun(
                existingValid = true,
                lastIp = "10.9.0.5",
                lastDns = "10.9.0.1",
                lastMtu = 1300,
                ip = "10.9.0.6",
                dns = "10.9.0.1",
                mtu = 1300,
            ),
        )
        assertFalse(
            canReuseBypassTun(
                existingValid = true,
                lastIp = "10.9.0.5",
                lastDns = "10.9.0.1",
                lastMtu = 1300,
                ip = "10.9.0.5",
                dns = "10.9.0.1",
                mtu = 1280,
            ),
        )
    }

    @Test
    fun softRestartReusesOnlyWhileAlreadyOnBypass() {
        assertTrue(
            shouldReuseBypassTunOnSoftRestart(
                softRestart = true,
                pathIsBypass = true,
                currentBackendIsBypass = true,
                tunValid = true,
            ),
        )
        assertFalse(
            shouldReuseBypassTunOnSoftRestart(
                softRestart = true,
                pathIsBypass = true,
                currentBackendIsBypass = false,
                tunValid = true,
            ),
        )
        assertFalse(
            shouldReuseBypassTunOnSoftRestart(
                softRestart = false,
                pathIsBypass = true,
                currentBackendIsBypass = true,
                tunValid = true,
            ),
        )
    }

    @Test
    fun softRestartRebuildsTunWhenUnderlayChanges() {
        assertFalse(
            shouldReuseBypassTunOnSoftRestart(
                softRestart = true,
                pathIsBypass = true,
                currentBackendIsBypass = true,
                tunValid = true,
                underlayChanged = true,
            ),
        )
        assertTrue(
            shouldReuseBypassTunOnSoftRestart(
                softRestart = true,
                pathIsBypass = true,
                currentBackendIsBypass = true,
                tunValid = true,
                underlayChanged = false,
            ),
        )
    }

    @Test
    fun tunUnderlayChangeIgnoresTransientNone() {
        assertFalse(tunUnderlayChanged(null, "cell:2:T-Mobile:1"))
        assertFalse(tunUnderlayChanged("cell:3:MTS:1", "none"))
        assertFalse(tunUnderlayChanged("cell:3:MTS:1", ""))
        assertFalse(tunUnderlayChanged("cell:3:MTS:1", "cell:3:MTS:1"))
        assertTrue(tunUnderlayChanged("cell:3:MTS:1", "cell:2:T-Mobile:2"))
        assertTrue(tunUnderlayChanged("wifi:home:1", "cell:3:MTS:2"))
    }
}
