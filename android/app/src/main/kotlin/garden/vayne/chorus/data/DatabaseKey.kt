package garden.vayne.chorus.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * A random SQLCipher passphrase, wrapped by a non-exportable Android Keystore AES key. If the
 * wrapper or Keystore key disappears while the database exists, fail closed instead of opening
 * an empty replica that could hide unsynced ops.
 */
internal object DatabaseKey {
    private const val ALIAS = "chorus-db-wrap-v1"
    private const val FILE = "chorus-db-key-v1"
    private const val INITIALIZED = "chorus-db-initialized-v1"
    private const val VERSION: Byte = 1
    private const val IV_BYTES = 12
    private const val KEY_BYTES = 32

    fun passphrase(context: Context, databaseFile: File): ByteArray {
        val file = File(context.noBackupFilesDir, FILE)
        check(databaseFile.exists() || !File(context.noBackupFilesDir, INITIALIZED).exists()) {
            "The encrypted database is missing; the local replica was not reset."
        }
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (file.exists()) {
            val key = store.getKey(ALIAS, null) as? SecretKey
                ?: error("The database encryption key is missing; the local replica was not reset.")
            val wrapped = file.readBytes()
            require(wrapped.size == 1 + IV_BYTES + KEY_BYTES + 16 && wrapped[0] == VERSION) {
                "The database key wrapper is damaged."
            }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, wrapped, 1, IV_BYTES))
            return cipher.doFinal(wrapped, 1 + IV_BYTES, wrapped.size - 1 - IV_BYTES)
        }
        check(!databaseFile.exists()) { "The database exists but its encryption key is missing." }

        val key = (store.getKey(ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore",
        ).run {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            generateKey()
        }
        val secret = ByteArray(KEY_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val wrapped = byteArrayOf(VERSION) + cipher.iv + cipher.doFinal(secret)
        check(cipher.iv.size == IV_BYTES)
        val temporary = File(file.parentFile, "$FILE.tmp")
        FileOutputStream(temporary).use { output ->
            output.write(wrapped)
            output.fd.sync()
        }
        check(temporary.renameTo(file)) { "Could not save the database encryption key." }
        return secret
    }

    fun markInitialized(context: Context) {
        val marker = File(context.noBackupFilesDir, INITIALIZED)
        if (!marker.exists()) FileOutputStream(marker).use { it.fd.sync() }
    }
}
