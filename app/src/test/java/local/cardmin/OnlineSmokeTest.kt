// SPDX-License-Identifier: Apache-2.0
package local.cardmin

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import local.cardmin.data.Api
import local.cardmin.data.ApiException
import local.cardmin.data.SecureStore
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Talks to the real backend with harmless requests only. Skipped unless run with
 * `./gradlew testDebugUnitTest -Ponline --tests local.cardmin.OnlineSmokeTest`.
 */
@RunWith(RobolectricTestRunner::class)
class OnlineSmokeTest {
    @Test fun loginWithUnknownAccountIsRejectedCleanly() = runBlocking {
        assumeTrue(System.getProperty("cardmin.online") == "true")
        val api = Api(SecureStore(ApplicationProvider.getApplicationContext()))
        try {
            api.login("cardmin-test@example.invalid", "NotAPassword-123")
            fail("login unexpectedly succeeded")
        } catch (e: ApiException) {
            println("login rejected: http=${e.httpStatus} type=${e.errorType} message=${e.message}")
            assertEquals("LoginFailureCredentialFailed", e.errorType)
        }
    }
}
