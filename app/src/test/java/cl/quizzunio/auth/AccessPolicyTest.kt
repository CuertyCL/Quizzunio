package cl.quizzunio.auth

import org.junit.Assert.*
import org.junit.Test

class AccessPolicyTest {
    @Test fun anonymousAndUnknownSessionsHaveNoRemoteRights() {
        listOf(SessionState.Anonymous, SessionState.Restoring, SessionState.Unavailable("Sin conexión")).forEach {
            assertFalse(AccessPolicy.canUseCloud(it)); assertFalse(AccessPolicy.canGenerateRemotely(it))
            assertEquals(GenerationSource.Local, AccessPolicy.defaultSource(it))
        }
    }
    @Test fun freeCanStoreAndPremiumDefaultsToServer() {
        val free = SessionState.Authenticated(UserIdentity(1, "FreeUser", "free@example.com", UserTier.Free))
        val premium = SessionState.Authenticated(UserIdentity(2, "PremiumUser", "premium@example.com", UserTier.Premium))
        assertTrue(AccessPolicy.canUseCloud(free)); assertFalse(AccessPolicy.canGenerateRemotely(free))
        assertEquals(GenerationSource.Local, AccessPolicy.defaultSource(free))
        assertTrue(AccessPolicy.canUseCloud(premium)); assertTrue(AccessPolicy.canGenerateRemotely(premium))
        assertEquals(GenerationSource.Server, AccessPolicy.defaultSource(premium))
    }
    @Test fun credentialsPreserveSpacesAndRejectNonAsciiInput() {
        CredentialValidator.password("  password123  ", true)
        CredentialValidator.password("legacy", false)
        assertThrows(IllegalArgumentException::class.java) { CredentialValidator.password("contraseña123", true) }
        assertThrows(IllegalArgumentException::class.java) { CredentialValidator.password("12345678901", true) }
        assertThrows(IllegalArgumentException::class.java) { CredentialValidator.email("á@example.com") }
        assertThrows(IllegalArgumentException::class.java) { CredentialValidator.email("a..b@example.com") }
        CredentialValidator.email("Name+tag@EXAMPLE.com")
    }
}
