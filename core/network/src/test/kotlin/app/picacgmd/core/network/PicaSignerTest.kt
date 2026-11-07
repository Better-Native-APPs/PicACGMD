package app.picacgmd.core.network

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class PicaSignerTest {
    @Test
    fun documentedWorkedExampleMatchesExactly() {
        assertEquals(
            "5e0d8becedd8af6302e0c52efc43ad7040ff36feff68b2983c84e5141143992e",
            PicaSigner().sign("auth/sign-in", "POST", "1582250000", "b1ab87b4800d4d4590a11701b8551afa"),
        )
    }

    @Test
    fun stripsOnlyTheLeadingSlashAndSignsTheQuery() {
        val signer = PicaSigner()
        assertEquals(signer.sign("comics?page=1", "GET", "1", "abc"), signer.sign("/comics?page=1", "GET", "1", "abc"))
        assertNotEquals(signer.sign("comics?page=1", "GET", "1", "abc"), signer.sign("comics?page=2", "GET", "1", "abc"))
    }

    @Test
    fun protocolLowercasingDoesNotDependOnDeviceLocale() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals(
                PicaSigner().sign("comics/ID", "GET", "1", "abc"),
                PicaSigner().sign("comics/id", "get", "1", "ABC"),
            )
        } finally {
            Locale.setDefault(original)
        }
    }
}
