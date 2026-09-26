package io.github.gsfernandes81.zwanaquota

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import io.github.gsfernandes81.zwanaquota.core.CookieJar
import io.github.gsfernandes81.zwanaquota.core.Credentials
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The portal login and the session cookie, encrypted with a key that lives in
 * the Android Keystore and never leaves it.
 *
 * Termux keeps these in `.env` and `~/.cache/zwana/`, mode 600. An app's
 * private storage is already that; the encryption is for the copies that
 * leave the phone -- a backup, a device transfer -- which is also why
 * backups are switched off in the manifest. EncryptedSharedPreferences would
 * do the same and is deprecated; this is the part of it that was needed.
 *
 * A value that will not decrypt -- the Keystore was cleared, the app was
 * restored onto another phone -- is simply absent: the widget then says it
 * needs signing in, rather than failing.
 */
class Vault(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("vault", Context.MODE_PRIVATE)

    fun credentials(): Credentials? {
        val username = read("username") ?: return null
        val password = read("password") ?: return null
        return Credentials(username, password).takeIf { username.isNotBlank() && password.isNotEmpty() }
    }

    fun setCredentials(credentials: Credentials) {
        write("username", credentials.username)
        write("password", credentials.password)
        // A new login is a new session.
        prefs.edit().remove("cookies").apply()
    }

    fun signOut() = prefs.edit().clear().apply()

    val signedIn: Boolean get() = credentials() != null

    fun cookies(): CookieJar = CookieJar.parse(read("cookies"))

    fun saveCookies(jar: CookieJar) = write("cookies", jar.serialize())

    private fun write(name: String, value: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val sealed = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(name, Base64.encodeToString(sealed, Base64.NO_WRAP)).apply()
    }

    private fun read(name: String): String? {
        val stored = prefs.getString(name, null) ?: return null
        return try {
            val sealed = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES))
            String(cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "zwana-vault"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
