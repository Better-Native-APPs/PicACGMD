package app.picacgmd.core.domain.image

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame

class ImageLoadProgressRegistryTest {
    @Test
    fun onlyReaderObservationsRegisterCanonicalHttpUrls() {
        val registry = ImageLoadProgressRegistry()
        assertNull(registry.observe("not a URL"))
        assertNull(registry.begin("https://images.example.com/unrequested", 10))
        assertEquals(0, registry.trackedUrlCount)
        val progress = assertNotNull(registry.observe("HTTPS://IMAGES.EXAMPLE.COM:443/page"))
        assertSame(progress, registry.observe("https://images.example.com/page"))
        assertEquals(1, registry.trackedUrlCount)
        val transfer = assertNotNull(registry.begin("https://images.example.com/page", 10))
        assertEquals(0f, progress.value)
        transfer.report(4)
        assertEquals(0.4f, progress.value)
        transfer.report(12)
        assertEquals(1f, progress.value)
        transfer.finish(completed = true)
        assertEquals(0, registry.trackedUrlCount)
        assertEquals(1f, progress.value)
        assertNotSame(progress, registry.observe("https://images.example.com/page"))
    }

    @Test
    fun unknownLengthsStayIndeterminateUntilCompletion() {
        val registry = ImageLoadProgressRegistry()
        val url = "https://images.example.com/page"
        val progress = assertNotNull(registry.observe(url))
        val transfer = assertNotNull(registry.begin(url, -1))
        transfer.report(512)
        assertNull(progress.value)
        transfer.finish(completed = true)
        assertEquals(1f, progress.value)
        assertEquals(0, registry.trackedUrlCount)
    }

    @Test
    fun staleBodiesCannotRemoveOrUpdateANewerTransferOrRegistration() {
        val registry = ImageLoadProgressRegistry()
        val url = "https://images.example.com/page"
        val progress = assertNotNull(registry.observe(url))
        val first = assertNotNull(registry.begin(url, 10))
        val second = assertNotNull(registry.begin(url, 20))
        first.report(10)
        first.finish(completed = true)
        assertEquals(0f, progress.value)
        assertEquals(1, registry.trackedUrlCount)
        second.report(5)
        assertEquals(0.25f, progress.value)
        second.finish(completed = false)
        assertNull(progress.value)
        assertEquals(0, registry.trackedUrlCount)
        val fresh = assertNotNull(registry.observe(url))
        second.finish(completed = true)
        assertNull(fresh.value)
        assertEquals(1, registry.trackedUrlCount)
    }

    @Test
    fun pendingCallCleanupDoesNotReleaseAnActiveBody() {
        val registry = ImageLoadProgressRegistry()
        val url = "https://images.example.com/page"
        val progress = assertNotNull(registry.observe(url))
        val pending = assertNotNull(registry.awaiting(url))
        val transfer = assertNotNull(registry.begin(url, 10))
        pending.finish(completed = true)
        assertEquals(1, registry.trackedUrlCount)
        transfer.finish(completed = false)
        val fresh = assertNotNull(registry.observe(url))
        pending.finish(completed = false)
        assertNull(fresh.value)
        assertEquals(1, registry.trackedUrlCount)
        assertNull(progress.value)
        registry.clear()
        assertEquals(0, registry.trackedUrlCount)
    }
}
