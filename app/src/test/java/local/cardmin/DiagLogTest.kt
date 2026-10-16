// SPDX-License-Identifier: Apache-2.0
package local.cardmin

import local.cardmin.data.DiagLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagLogTest {
    @Test fun pathIdsAreReplaced() {
        assertEquals("/appUser/{u}/card/{c}/pin", DiagLog.redactPath("/appUser/4711/card/99/pin"))
        assertEquals("/appUser/{u}/device/{d}/key", DiagLog.redactPath("/appUser/4711/device/ab-cd/key"))
        assertEquals("/appUser/{u}/device/confirmation", DiagLog.redactPath("/appUser/4711/device/confirmation"))
        assertEquals("/appUser/{u}/state?deviceId={d}", DiagLog.redactPath("/appUser/4711/state?deviceId=xyz"))
    }

    @Test fun secretsBecomeShapes() {
        val out = DiagLog.redactJson("""{"mobileNumber":"+491701234567","tan":"123456","signedText":"3045022100ab","status":"ACTIVE","n":[1,2]}""")
        assertFalse(out.contains("1701234567"))
        assertFalse(out.contains("123456"))
        assertTrue(out.contains("<str 13 +digits>"))
        assertTrue(out.contains("<str 6 digits>"))
        assertTrue(out.contains("<str 12 hex>"))
        assertTrue(out.contains("\"ACTIVE\""))
        assertEquals("<str 11 text>", DiagLog.redactJson("not json!!!"))
    }
}
