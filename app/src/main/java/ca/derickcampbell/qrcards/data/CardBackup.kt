package ca.derickcampbell.qrcards.data

import android.content.Context
import android.net.Uri
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Local backup of the card library: export writes a file the user keeps
 * (Downloads, a USB stick...), import reads it back. Restore *replaces* the
 * current library — predictable, no merge semantics to get wrong. No cloud.
 *
 * Two formats, auto-detected on import:
 * - Plain JSON (the default): always restorable, on any install or device.
 *   Anyone who finds the file can read it — the user's explicit choice, and
 *   the default because an encrypted backup whose key is lost is worse than
 *   no backup at all.
 * - Password-encrypted (optional): AES-256-GCM with a PBKDF2-SHA256 key.
 *   Portable across installs and devices; lose the password, lose the backup.
 *
 * A legacy Keystore-based format from the earliest builds is still accepted
 * on import (it only ever restored on its original install).
 */
object CardBackup {

    private val MAGIC = "QRC1".toByteArray(Charsets.US_ASCII)
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val PBKDF2_ITERATIONS = 210_000
    private const val KEY_BITS = 256
    private const val GCM_TAG_BITS = 128

    /** True when the backup at [source] is password-encrypted. */
    fun isEncryptedBackup(context: Context, source: Uri): Boolean {
        val staging = File(context.cacheDir, "backup-peek.qrcards")
        return try {
            context.contentResolver.openInputStream(source)?.use { input ->
                staging.outputStream().use { input.copyTo(it) }
            } ?: return false
            isEncryptedBytes(staging.readBytes())
        } catch (e: Exception) {
            false
        } finally {
            staging.delete()
        }
    }

    /**
     * Writes the backup. Null [password] → plain JSON; non-null → encrypted.
     * A non-null password array is wiped before returning.
     */
    fun exportTo(context: Context, destination: Uri, password: CharArray?) {
        val payload = if (password == null) {
            internalJsonBytes(context)
        } else {
            try {
                encryptBackup(internalJsonBytes(context), password)
            } finally {
                password.fill('\u0000')
            }
        }
        context.contentResolver.openOutputStream(destination)?.use { it.write(payload) }
            ?: throw IllegalArgumentException("Cannot write backup destination")
    }

    /**
     * Imports a backup, replacing the current library.
     * @param password required for encrypted backups; ignored otherwise.
     * @return number of cards imported.
     */
    fun importFrom(context: Context, source: Uri, password: CharArray?): Int {
        val staging = File(context.cacheDir, "backup-import.qrcards")
        try {
            context.contentResolver.openInputStream(source)?.use { input ->
                staging.outputStream().use { input.copyTo(it) }
            } ?: throw IllegalArgumentException("Cannot read backup file")
            val raw = staging.readBytes()
            val jsonBytes: ByteArray = when {
                isEncryptedBytes(raw) -> {
                    val pw = password
                        ?: throw IllegalArgumentException("This backup is encrypted — its password is required.")
                    try {
                        decryptBackup(raw, pw)
                    } catch (e: AEADBadTagException) {
                        throw IllegalArgumentException("Wrong password for this backup.", e)
                    } catch (e: Exception) {
                        throw IllegalArgumentException("Couldn't decrypt this backup.", e)
                    } finally {
                        pw.fill('\u0000')
                    }
                }
                isPlainJson(raw) -> raw
                else -> tryLegacyDecrypt(context, staging)
                    ?: throw IllegalArgumentException("This doesn't look like a QR Cards backup file.")
            }
            val repo = CardRepository(context)
            val root = try {
                JSONObject(jsonBytes.toString(Charsets.UTF_8))
            } catch (e: Exception) {
                throw IllegalArgumentException("Not a valid QR Cards backup", e)
            }
            val arr = try {
                root.getJSONArray("cards")
            } catch (e: Exception) {
                throw IllegalArgumentException("Not a valid QR Cards backup", e)
            }
            val cards = List(arr.length()) { i -> repo.fromJson(arr.getJSONObject(i)) }
            // Folders are optional: backups written before folders existed
            // have no "folders" array, and every card lands at top level.
            val foldersArr = root.optJSONArray("folders")
            val folders = if (foldersArr == null) emptyList()
            else List(foldersArr.length()) { i ->
                repo.folderFromJson(foldersArr.getJSONObject(i))
            }
            cards.forEach {
                require(it.id.isNotBlank() && it.name.isNotBlank() && it.payload.isNotEmpty()) {
                    "Backup contains an invalid card"
                }
            }
            // A card pointing at a folder that isn't in the backup (hand-
            // edited file) goes top-level rather than vanishing.
            val folderIds = folders.map { it.id }.toSet()
            val saneCards = cards.map {
                if (it.folderId != null && it.folderId !in folderIds) it.copy(folderId = null)
                else it
            }
            repo.replaceAll(saneCards, folders)
            return cards.size
        } finally {
            staging.delete()
        }
    }

    // -- password-encrypted format: MAGIC + salt + iv + AES-GCM ciphertext --

    private fun encryptBackup(plain: ByteArray, password: CharArray): ByteArray {
        val random = SecureRandom()
        val salt = ByteArray(SALT_BYTES).also { random.nextBytes(it) }
        val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(deriveKey(password, salt), "AES"),
                GCMParameterSpec(GCM_TAG_BITS, iv)
            )
        }
        return MAGIC + salt + iv + cipher.doFinal(plain)
    }

    private fun decryptBackup(blob: ByteArray, password: CharArray): ByteArray {
        var o = MAGIC.size
        val salt = blob.copyOfRange(o, o + SALT_BYTES); o += SALT_BYTES
        val iv = blob.copyOfRange(o, o + IV_BYTES); o += IV_BYTES
        val cipherText = blob.copyOfRange(o, blob.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(deriveKey(password, salt), "AES"),
                GCMParameterSpec(GCM_TAG_BITS, iv)
            )
        }
        return cipher.doFinal(cipherText) // wrong password → AEADBadTagException
    }

    private fun deriveKey(password: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password, salt, PBKDF2_ITERATIONS, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun isEncryptedBytes(raw: ByteArray): Boolean =
        raw.size > MAGIC.size + SALT_BYTES + IV_BYTES &&
            raw.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)

    private fun isPlainJson(raw: ByteArray): Boolean =
        raw.toString(Charsets.UTF_8).trimStart().startsWith("{")

    // -- legacy Keystore format (earliest builds; same-install restores only) --

    private fun tryLegacyDecrypt(context: Context, file: File): ByteArray? =
        runCatching {
            encryptedFile(context, file).openFileInput().use { it.readBytes() }
        }.getOrNull()?.takeIf { isPlainJson(it) }

    private fun encryptedFile(context: Context, file: File): EncryptedFile {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedFile.Builder(
            context,
            file,
            masterKey,
            EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB,
        ).build()
    }

    private fun internalJsonBytes(context: Context): ByteArray {
        val f = File(context.filesDir, "cards.json")
        if (!f.exists()) f.writeText("""{"cards":[]}""")
        return f.readBytes()
    }
}
