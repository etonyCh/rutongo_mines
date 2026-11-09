package rw.rutongo.mmems.identity

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import rw.rutongo.mmems.shared.enums.Role
import kotlin.test.Test
import kotlin.test.assertEquals

class UserViewSerializationTest {
    @Test
    fun `identity responses serialize for the web client`() {
        val user =
            UserView(
                id = "user-1",
                username = "manager",
                fullName = "Site Manager",
                employeeId = "EID-1",
                role = Role.MANAGER,
                active = true,
                siteIds = listOf("site-1"),
                version = 3,
            )

        val encoded = Json.encodeToString(user)

        assertEquals(
            """{"id":"user-1","username":"manager","fullName":"Site Manager","employeeId":"EID-1","role":"MANAGER","active":true,"siteIds":["site-1"],"version":3}""",
            encoded,
        )
    }
}
