package rw.rutongo.mmems.shared.enums

import kotlin.test.Test
import kotlin.test.assertEquals

class DomainEnumsTest {
    @Test
    fun `only three primary operational statuses exist`() {
        assertEquals(setOf("OPERATIONAL", "UNDER_REPAIR", "DOWN"), OperationalStatus.entries.map { it.name }.toSet())
        assertEquals(3, OperationalStatus.entries.size)
    }
}
