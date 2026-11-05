package rw.rutongo.mmems.identity

import com.nimbusds.jwt.SignedJWT
import org.junit.jupiter.api.Test
import rw.rutongo.mmems.shared.enums.Role
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TokenSignerTest {
    @Test
    fun `access token is signed short lived and contains no site grants`() {
        val signer = TokenSigner.createForTests()
        val principal = Principal(UUID.randomUUID(), "mechanic-1", Role.MECHANIC, false)

        val raw = signer.sign(principal)
        val jwt = SignedJWT.parse(raw)

        assertEquals("primary", jwt.header.keyID)
        assertEquals("RS256", jwt.header.algorithm.name)
        assertNotNull(signer.verify(raw))
        assertEquals(principal.id.toString(), jwt.jwtClaimsSet.subject)
        assertEquals(900L, (jwt.jwtClaimsSet.expirationTime.time - jwt.jwtClaimsSet.issueTime.time) / 1000)
        assertFalse(jwt.jwtClaimsSet.claims.keys.any { it.contains("site", ignoreCase = true) })
    }

    @Test
    fun `modified token is rejected`() {
        val signer = TokenSigner.createForTests()
        val token = signer.sign(Principal(UUID.randomUUID(), "manager-1", Role.MANAGER, false))
        val parts = token.split('.')
        val changedSignature = (if (parts[2].first() == 'a') "b" else "a") + parts[2].drop(1)

        assertNull(signer.verify("${parts[0]}.${parts[1]}.$changedSignature"))
    }
}
