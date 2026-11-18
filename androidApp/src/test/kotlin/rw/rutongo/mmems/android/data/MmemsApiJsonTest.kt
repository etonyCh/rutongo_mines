package rw.rutongo.mmems.android.data

import kotlinx.serialization.encodeToString
import org.junit.Assert.assertTrue
import org.junit.Test
import rw.rutongo.mmems.shared.api.LoginRequest
import rw.rutongo.mmems.shared.api.RefreshRequest

class MmemsApiJsonTest {
    @Test
    fun loginAndRefreshIncludeAndroidClientType() {
        val login = KtorMmemsApi.apiJson.encodeToString(LoginRequest(username = "user", password = "secret"))
        val refresh = KtorMmemsApi.apiJson.encodeToString(RefreshRequest(refreshToken = "token"))

        assertTrue(login.contains("\"clientType\":\"ANDROID\""))
        assertTrue(refresh.contains("\"clientType\":\"ANDROID\""))
    }
}
