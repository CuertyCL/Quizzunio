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
    @Test fun passwordsUseUnicodeCodePointsAndEmailsRemainAscii() {
        CredentialValidator.password("  password123  ", true)
        CredentialValidator.password("legacy", false)
        CredentialValidator.password("", false)
        CredentialValidator.password("  contrasen\u0303a😀  ", true)
        CredentialValidator.password("😀".repeat(128), true)
        assertThrows(IllegalArgumentException::class.java) { CredentialValidator.password("😀".repeat(129), true) }
        assertThrows(IllegalArgumentException::class.java) { CredentialValidator.password("contraseña123\uD800", true) }
        assertThrows(IllegalArgumentException::class.java) { CredentialValidator.password("😀".repeat(11), true) }
        assertThrows(IllegalArgumentException::class.java) { CredentialValidator.password("12345678901", true) }
        assertThrows(IllegalArgumentException::class.java) { CredentialValidator.email("á@example.com") }
        assertThrows(IllegalArgumentException::class.java) { CredentialValidator.email("a@ejémplo.com") }
        assertThrows(IllegalArgumentException::class.java) { CredentialValidator.email("a..b@example.com") }
        assertThrows(IllegalArgumentException::class.java) { CredentialValidator.email("a".repeat(65) + "@example.com") }
        assertThrows(IllegalArgumentException::class.java) { CredentialValidator.email("a@" + "x".repeat(64) + ".com") }
        CredentialValidator.email("Name+tag@EXAMPLE.com")
    }

    @Test fun usernameUsesNfcAndDoesNotFoldCaseOrCompatibilityCharacters() {
        listOf("José", "María-Luisa", "usuario_ñ", "用户123", "مستخدم٢", "a\u0301\u0327bc", "Ａna").forEach {
            CredentialValidator.username(it)
        }
        assertEquals("José", CredentialValidator.canonicalIdentifier("Jose\u0301"))
        assertEquals("É".repeat(32), CredentialValidator.canonicalUsername("E\u0301".repeat(32)))
        assertEquals("𐐀".repeat(32), CredentialValidator.canonicalUsername("𐐀".repeat(32)))
        assertEquals("Usuario", CredentialValidator.canonicalIdentifier("Usuario"))
        assertEquals("usuario", CredentialValidator.canonicalIdentifier("usuario"))
        assertEquals("Ａna", CredentialValidator.canonicalIdentifier("Ａna"))
        assertEquals("User@EXAMPLE.com", CredentialValidator.canonicalIdentifier("User@EXAMPLE.com"))
    }

    @Test fun usernameRejectsMalformedSequencesAndProhibitedCharacters() {
        listOf(
            "ab", "𐐀".repeat(33), "A".repeat(257), "usuario nombre", "usuario@nombre", "usuario😀", "ab\u0000c",
            "ab\u200Dc", "ab\u202Ec", "ab\uFE0Fc", "ab\uDB40\uDD00c", "ab\u034Fc", "ab\u115Fc",
            "ab\u180Bc", "ab\u20E3c", "ab\uD800c", "\u0301abc", "a_\u0301bc", "a3\u0301bc"
        ).forEach { input ->
            assertThrows(IllegalArgumentException::class.java) { CredentialValidator.username(input) }
        }
    }
}
