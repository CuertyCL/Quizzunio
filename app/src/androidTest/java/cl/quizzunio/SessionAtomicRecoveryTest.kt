package cl.quizzunio

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import cl.quizzunio.auth.KeystoreTokenStore
import cl.quizzunio.auth.TokenPair
import java.io.File
import java.time.Instant
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class SessionAtomicRecoveryTest {
    @Test fun recoversLegacyBackupButNeverRestoresAnAbandonedSession() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val path = File(base.cacheDir, "session-test-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = path
        }
        val server = "https://fixture.example.test"
        val expiry = Instant.parse("2026-12-20T00:00:00Z")
        val pair = TokenPair("synthetic-access", "synthetic-refresh", expiry, expiry, expiry)
        val store = KeystoreTokenStore(context)
        val main = File(path, "session.enc")
        val backup = File(path, "session.enc.bak")
        val pending = File(path, "session.enc.new")
        val unrelated = File(path, "quizzes-fixture").apply { writeText("intacto") }
        assertNull(store.read(server))
        store.write(server, pair)
        assertEquals(pair.accessToken, store.read(server)?.accessToken)
        assertTrue(main.renameTo(backup))
        assertEquals(pair.refreshToken, store.read(server)?.refreshToken)
        assertTrue(main.renameTo(pending))
        assertNull(store.read(server))
        assertTrue(pending.renameTo(backup))
        assertTrue(runCatching { store.read("https://other.example.test") }.isFailure)
        assertTrue(main.renameTo(backup))
        store.cleanupJournal.begin()
        val reopened = KeystoreTokenStore(context)
        assertTrue(reopened.cleanupJournal.isPending())
        assertTrue(runCatching { reopened.read(server) }.isFailure)
        reopened.clear()
        assertTrue(reopened.cleanupJournal.isPending())
        reopened.cleanupJournal.finish()
        assertNull(KeystoreTokenStore(context).read(server))
        assertEquals("intacto", unrelated.readText())
        assertTrue(main.delete())
        backup.writeText("cifrado corrupto")
        assertTrue(runCatching { store.read(server) }.isFailure)
    }
}
