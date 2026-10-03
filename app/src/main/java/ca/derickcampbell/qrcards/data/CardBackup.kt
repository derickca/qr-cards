package ca.derickcampbell.qrcards.data

import android.content.Context
import android.net.Uri
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import org.json.JSONObject
import java.io.File

/**
 * Encrypted local backup of the card library.
 *
 * The spec promises no cloud backup: export writes an encrypted file to a
 * location the user chooses (e.g. Downloads, a USB stick), import reads it
 * back. Restore *replaces* the current library — predictable, no merge
 * semantics to get wrong.
 *
 * Encryption: AES256-GCM via androidx.security EncryptedFile, key held in the
 * Android Keystore. Anyone who finds the backup file gets ciphertext.
 * EncryptedFile works on Files, not Uris, so transfers stage through the
 * cache dir (staging files are deleted after use).
 */
object CardBackup {

    fun exportTo(context: Context, destination: Uri) {
        val staging = File(context.cacheDir, "backup-export.qrcards")
        try {
            encryptedFile(context, staging).openFileOutput().use { out ->
                out.write(internalJsonBytes(context))
            }
            context.contentResolver.openOutputStream(destination)?.use { out ->
                staging.inputStream().use { it.copyTo(out) }
            } ?: throw IllegalArgumentException("Cannot write backup destination")
        } finally {
            staging.delete()
        }
    }

    /**
     * Imports a backup, replacing the current library.
     * @return number of cards imported.
     * @throws IllegalArgumentException if the file is not a valid backup.
     */
    fun importFrom(context: Context, source: Uri): Int {
        val staging = File(context.cacheDir, "backup-import.qrcards")
        try {
            context.contentResolver.openInputStream(source)?.use { input ->
                staging.outputStream().use { input.copyTo(it) }
            } ?: throw IllegalArgumentException("Cannot read backup file")
            // Decryption fails when the file isn't one of our backups (e.g. a
            // PNG/SVG picked by mistake) or was encrypted on a different install
            // (the key lives in this install's Keystore). Report that plainly
            // instead of leaking the crypto library's message.
            val bytes = try {
                encryptedFile(context, staging).openFileInput().use { it.readBytes() }
            } catch (e: Exception) {
                throw IllegalArgumentException(
                    "Couldn't decrypt this backup — it may not be a QR Cards " +
                        "backup file, or it was created on a different install.",
                    e
                )
            }
            val repo = CardRepository(context)
            val cards = try {
                val arr = JSONObject(bytes.toString(Charsets.UTF_8)).getJSONArray("cards")
                List(arr.length()) { i -> repo.fromJson(arr.getJSONObject(i)) }
            } catch (e: Exception) {
                throw IllegalArgumentException("Not a valid QR Cards backup", e)
            }
            cards.forEach {
                require(it.id.isNotBlank() && it.name.isNotBlank() && it.payload.isNotEmpty()) {
                    "Backup contains an invalid card"
                }
            }
            repo.replaceAll(cards)
            return cards.size
        } finally {
            staging.delete()
        }
    }

    private fun internalJsonBytes(context: Context): ByteArray {
        val f = File(context.filesDir, "cards.json")
        if (!f.exists()) f.writeText("""{"cards":[]}""")
        return f.readBytes()
    }

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
}
