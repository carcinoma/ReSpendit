// SPDX-License-Identifier: Apache-2.0
package local.cardmin

import local.cardmin.ui.Validation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ValidationTest {
    @Test fun pinRules() {
        assertNull(Validation.pin("4711", "4711"))
        assertNotNull(Validation.pin("1234", "1234"))
        assertNotNull(Validation.pin("4321", "4321"))
        assertNotNull(Validation.pin("1122", "1122"))
        assertNotNull(Validation.pin("471", "471"))
        assertNotNull(Validation.pin("4711", "4712"))
    }

    @Test fun passwordRules() {
        assertNull(Validation.password("Abcdefg1", "Abcdefg1"))
        assertNotNull(Validation.password("abcdefg1", "abcdefg1"))
        assertNotNull(Validation.password("Abc def1", "Abc def1"))
        assertNotNull(Validation.password("Ab1!", "Ab1!"))
    }

    @Test fun phoneNormalisation() {
        assertEquals("+491701234567", Validation.phone("0170 1234567"))
        assertEquals("+491701234567", Validation.phone("+49 170 123 45 67"))
        assertEquals("+436641234567", Validation.phone("0043 664 1234567"))
        assertNull(Validation.phone("12"))
    }

    @Test fun tokenFromLink() {
        assertEquals("abc123", Validation.tokenFromLink("https://mybenefits.spendit.de/accountActivation?token=abc123"))
        assertEquals("abc123", Validation.tokenFromLink(" abc123 "))
    }
}
