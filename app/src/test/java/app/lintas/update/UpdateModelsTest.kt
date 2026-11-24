package app.lintas.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateModelsTest {
    @Test fun parsesVersionTagWithAndWithoutV() {
        assertEquals(ParsedVersion(1, 0, 7), parseVersionTag("v1.0.7"))
        assertEquals(ParsedVersion(1, 0, 7), parseVersionTag("1.0.7"))
    }

    @Test fun rejectsNonStableOrMalformedTags() {
        assertNull(parseVersionTag("v1.0.7-beta"))
        assertNull(parseVersionTag("v1.0"))
        assertNull(parseVersionTag("latest"))
    }

    @Test fun comparesVersionsNumerically() {
        assertTrue(isNewerVersion("v1.0.10", "1.0.9"))
        assertTrue(isNewerVersion("1.1.0", "1.0.99"))
        assertFalse(isNewerVersion("1.0.7", "1.0.7"))
        assertFalse(isNewerVersion("1.0.6", "1.0.7"))
    }

    @Test fun generationGateInvalidatesOlderTokens() {
        val gate = GenerationGate()
        val first = gate.next()
        assertTrue(gate.isCurrent(first))
        gate.invalidate()
        assertFalse(gate.isCurrent(first))
        val second = gate.next()
        assertTrue(gate.isCurrent(second))
        assertFalse(gate.isCurrent(first))
    }

    @Test fun checkResultRequiresCurrentTokenAndCheckingStatus() {
        val gate = GenerationGate()
        val token = gate.next()
        assertTrue(canApplyCheckResult(token, gate, UpdateStatus.CHECKING))
        assertFalse(canApplyCheckResult(token, gate, UpdateStatus.AVAILABLE))
        assertFalse(canApplyCheckResult(token, gate, UpdateStatus.ERROR))
        gate.invalidate()
        assertFalse(canApplyCheckResult(token, gate, UpdateStatus.CHECKING))
    }
}
