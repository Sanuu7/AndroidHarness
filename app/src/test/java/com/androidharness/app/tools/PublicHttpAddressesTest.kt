package com.androidharness.app.tools

import java.net.InetAddress
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PublicHttpAddressesTest {
    @Test
    fun `local private and transition addresses are blocked`() {
        for (ip in listOf("127.0.0.1", "127.1.2.3", "0.0.0.0", "10.0.0.1", "172.16.0.1",
            "192.168.1.1", "169.254.169.254", "100.64.0.1", "198.18.0.1", "224.0.0.1",
            "::1", "::", "fc00::1", "fe80::1", "::ffff:127.0.0.1", "64:ff9b::7f00:1",
            "2002:7f00:1::", "2001:db8::1")) {
            assertFalse(ip, PublicHttpAddresses.isPublic(InetAddress.getByName(ip)))
        }
    }

    @Test
    fun `public IPv4 and IPv6 addresses are allowed`() {
        for (ip in listOf("8.8.8.8", "1.1.1.1", "2606:4700:4700::1111")) {
            assertTrue(ip, PublicHttpAddresses.isPublic(InetAddress.getByName(ip)))
        }
    }

    @Test(expected = java.io.IOException::class)
    fun `mixed public private DNS answers are rejected`() {
        PublicHttpAddresses.validate(listOf(InetAddress.getByName("8.8.8.8"), InetAddress.getByName("127.0.0.1")))
    }
}
