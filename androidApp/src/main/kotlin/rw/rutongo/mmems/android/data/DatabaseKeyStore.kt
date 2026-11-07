package rw.rutongo.mmems.android.data

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.SecureRandom

object DatabaseKeyStore {
    fun passphrase(context: Context): ByteArray {
        val masterKey =
            MasterKey
                .Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
        val prefs =
            EncryptedSharedPreferences.create(
                context,
                "mmems_database_key",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        prefs.getString(KEY, null)?.let { return Base64.decode(it, Base64.NO_WRAP) }
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        prefs.edit().putString(KEY, Base64.encodeToString(bytes, Base64.NO_WRAP)).apply()
        return bytes
    }

    private const val KEY = "sqlcipher_key"
}
