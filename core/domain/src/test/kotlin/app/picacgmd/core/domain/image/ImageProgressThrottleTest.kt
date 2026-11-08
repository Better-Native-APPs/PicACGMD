package app.picacgmd.core.domain.image

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ImageProgressThrottleTest {
    @Test
    fun subPercentReadsAreSuppressedUntilOneHundredMilliseconds() {
        var now = 0L
        val throttle = ImageProgressThrottle(contentLength = 100_000) { now }
        assertFalse(throttle.shouldReport(100))
        now = 99
        assertFalse(throttle.shouldReport(200))
        now = 100
        assertTrue(throttle.shouldReport(300))
        now = 199
        assertFalse(throttle.shouldReport(400))
        now = 200
        assertTrue(throttle.shouldReport(500))
    }

    @Test
    fun integerPercentChangesAndTheLastKnownByteReportImmediately() {
        val throttle = ImageProgressThrottle(contentLength = 100_000) { 0L }
        assertTrue(throttle.shouldReport(1_000))
        assertFalse(throttle.shouldReport(1_999))
        assertTrue(throttle.shouldReport(2_000))
        assertTrue(throttle.shouldReport(99_999))
        assertTrue(throttle.shouldReport(100_000))
        assertFalse(throttle.shouldReport(100_001))
    }

    @Test
    fun unknownLengthsDoNotGenerateIndeterminateUpdates() {
        var now = 0L
        val throttle = ImageProgressThrottle(contentLength = -1) { now }
        now = 10_000
        assertFalse(throttle.shouldReport(20_000))
    }
}
