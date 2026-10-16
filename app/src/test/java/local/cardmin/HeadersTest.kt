// SPDX-License-Identifier: Apache-2.0
package local.cardmin

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import local.cardmin.data.Api
import local.cardmin.data.ApiException
import local.cardmin.data.SecureStore
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HeadersTest {
    @Test fun requestsCarryExpectedHeaders() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"status":401,"type":"LoginFailureCredentialFailed"}"""))
        server.start()
        val api = Api(SecureStore(ApplicationProvider.getApplicationContext()), server.url("").toString().trimEnd('/'))
        runCatching { api.login("a@example.invalid", "x") }.exceptionOrNull().let { assertTrue(it is ApiException) }
        val req = server.takeRequest()
        assertEquals("/login", req.path)
        assertEquals("Mozilla/5.0 (X11; Linux x86_64; rv:152.0) Gecko/20100101 Firefox/152.0", req.getHeader("User-Agent"))
        assertNull(req.getHeader("Platform"))
        assertNull(req.getHeader("Build-Number"))
        assertTrue(req.getHeader("traceparent")!!.matches(Regex("^00-[0-9a-f]{32}-[0-9a-f]{16}-01$")))
        assertNull(req.getHeader("Authorization"))
        assertEquals("""{"email":"a@example.invalid","password":"x"}""", req.body.readUtf8())
        server.shutdown()
    }
}
