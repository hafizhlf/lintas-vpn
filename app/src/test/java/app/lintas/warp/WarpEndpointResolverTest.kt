package app.lintas.warp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WarpEndpointResolverTest {
    @Test fun resolvesIpLiteralWithoutDns() {
        val resolver = WarpEndpointResolver(timeoutMs = 100L)
        try {
            assertEquals("127.0.0.1", resolver.resolve("127.0.0.1")?.hostAddress)
        } finally {
            resolver.shutdown()
        }
    }

    @Test fun ignoresBlankHost() {
        val resolver = WarpEndpointResolver(timeoutMs = 100L)
        try {
            assertNull(resolver.resolve("  "))
        } finally {
            resolver.shutdown()
        }
    }
}
