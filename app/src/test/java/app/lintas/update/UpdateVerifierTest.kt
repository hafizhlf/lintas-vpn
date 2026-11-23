package app.lintas.update

import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateVerifierTest {
    @Test fun hashesBytesWithSha256() {
        assertEquals(
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
            UpdateVerifier.sha256("hello".toByteArray()),
        )
    }
}
