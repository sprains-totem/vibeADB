package com.vibeadb.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UrlParserTest {

    @Test
    fun findsTunnelHostInCloudflaredOutput() {
        val line = "|  Your quick Tunnel has been created! Visit it at (it may take some time to be reachable):  |"
        val urlLine = "|  https://tea-pot-quiet-early.trycloudflare.com                                        |"
        assertEquals(
            "tea-pot-quiet-early.trycloudflare.com",
            UrlParser.findTunnelHost(urlLine)
        )
        assertNull(UrlParser.findTunnelHost(line))
    }

    @Test
    fun ignoresOtherUrls() {
        assertNull(UrlParser.findTunnelHost("https://api.cloudflare.com/client/v4"))
        assertNull(UrlParser.findTunnelHost("https://evil.attacker.com/trycloudflare.com"))
    }

    @Test
    fun stripsScheme() {
        assertEquals(
            "a-b-c.trycloudflare.com",
            UrlParser.findTunnelHost("INF Registered tunnel connection https://a-b-c.trycloudflare.com")
        )
    }
}
