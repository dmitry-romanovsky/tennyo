package app.tennyo.mobile.link.pairing

import kotlin.test.Test
import kotlin.test.assertEquals

class RemoteAddressTest {
    @Test
    fun `a host name takes the default port`() {
        assertEquals(valid("myhome.duckdns.org", 63343), RemoteAddress.parse("myhome.duckdns.org", defaultPort = 63343))
    }

    @Test
    fun `an IPv4 literal keeps its port`() {
        assertEquals(valid("203.0.113.5", 8443), RemoteAddress.parse("203.0.113.5:8443", defaultPort = 63343))
    }

    @Test
    fun `an https prefix is accepted`() {
        assertEquals(valid("host", 63343), RemoteAddress.parse("https://host:63343", defaultPort = 1))
    }

    @Test
    fun `malformed addresses are rejected with their reason`() {
        val rejected = mapOf(
            "http://x" to RemoteAddressError.NOT_AN_ADDRESS,
            "user@x" to RemoteAddressError.NOT_AN_ADDRESS,
            "x/path" to RemoteAddressError.NOT_AN_ADDRESS,
            "my_host.example" to RemoteAddressError.NOT_AN_ADDRESS,
            "[::1]" to RemoteAddressError.IPV6_UNSUPPORTED,
            "x:0" to RemoteAddressError.BAD_PORT,
            "x:70000" to RemoteAddressError.BAD_PORT,
            "x y" to RemoteAddressError.NOT_AN_ADDRESS,
            "ünï.de" to RemoteAddressError.NOT_AN_ADDRESS,
            "" to RemoteAddressError.EMPTY,
            "127.0.0.1" to RemoteAddressError.NOT_AN_ADDRESS,
            "127.8.9.10:8443" to RemoteAddressError.NOT_AN_ADDRESS,
            "0.0.0.0" to RemoteAddressError.NOT_AN_ADDRESS,
            "ftp:x" to RemoteAddressError.NOT_AN_ADDRESS,
            "x:" to RemoteAddressError.BAD_PORT,
            "-bad.example" to RemoteAddressError.NOT_A_HOST,
            "999.1.1.1" to RemoteAddressError.NOT_A_HOST,
        )

        rejected.forEach { (text, error) ->
            assertEquals(RemoteAddressParse.Invalid(error), RemoteAddress.parse(text, defaultPort = 63343), text)
        }
    }

    private fun valid(host: String, port: Int) =
        RemoteAddressParse.Valid("https://$host:$port/api/tennyo")
}
