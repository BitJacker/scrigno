package io.github.bitjacker.scrigno

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.bitjacker.scrigno.data.settings.SecretBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The server password is encrypted with a key that lives in the Android Keystore. */
@RunWith(AndroidJUnit4::class)
class SecretBoxTest {

    private val box = SecretBox(alias = "scrigno.test.key")

    @Test
    fun encryptsWithTheKeystoreAndDecrypts() {
        val secret = "pässwörd with spaces & symbols !"
        val stored = box.encrypt(secret)
        assertTrue("keystore encryption expected, got $stored", stored.startsWith("ks1:"))
        assertTrue(!stored.contains(secret))
        assertEquals(secret, box.decrypt(stored))
        // Same password, new random IV: different ciphertext.
        assertNotEquals(stored, box.encrypt(secret))
        // A new instance (app restarted) still reads it.
        assertEquals(secret, SecretBox(alias = "scrigno.test.key").decrypt(stored))
    }

    @Test
    fun corruptedDataIsRejected() {
        val stored = box.encrypt("secret")
        val corrupted = stored.dropLast(4) + "AAAA"
        assertNull(box.decrypt(corrupted))
        assertEquals("", box.decrypt(""))
    }

    @Test
    fun forgettingTheKeyMakesOldSecretsUnreadable() {
        val stored = box.encrypt("secret")
        box.forget()
        assertNull(box.decrypt(stored))
    }
}
