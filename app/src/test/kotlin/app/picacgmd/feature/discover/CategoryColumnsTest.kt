package app.picacgmd.feature.discover

import kotlin.test.Test
import kotlin.test.assertEquals

class CategoryColumnsTest {
    @Test fun automaticColumnsFollowWindowWidth() {
        assertEquals(3, categoryColumns(0, 599f))
        assertEquals(4, categoryColumns(0, 600f))
        assertEquals(6, categoryColumns(0, 840f))
    }

    @Test fun explicitColumnsOverrideWidth() {
        for (count in 2..7) {
            assertEquals(count, categoryColumns(count, 360f))
            assertEquals(count, categoryColumns(count, 1200f))
        }
    }
}
