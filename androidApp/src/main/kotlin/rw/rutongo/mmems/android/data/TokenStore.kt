package rw.rutongo.mmems.android.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import rw.rutongo.mmems.shared.api.SessionResponse

interface TokenStore {
    fun accessToken(): String?

    fun refreshToken(): String?

    fun refreshExpiresAt(): Long

    fun save(session: SessionResponse)

    fun clear()

    fun isLoggedIn(): Boolean = !accessToken().isNullOrBlank() || !refreshToken().isNullOrBlank()
}

class EncryptedTokenStore(
    context: Context,
) : TokenStore {
    private val masterKey =
        MasterKey
            .Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    private val prefs =
        EncryptedSharedPreferences.create(
            context,
            "mmems_session",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )

    override fun accessToken(): String? = prefs.getString(KEY_ACCESS, null)

    override fun refreshToken(): String? = prefs.getString(KEY_REFRESH, null)

    override fun refreshExpiresAt(): Long = prefs.getLong(KEY_REFRESH_EXPIRES, 0L)

    override fun save(session: SessionResponse) {
        prefs
            .edit()
            .putString(KEY_ACCESS, session.accessToken)
            .putString(KEY_REFRESH, session.refreshToken)
            .putLong(KEY_REFRESH_EXPIRES, System.currentTimeMillis() + 30L * 24 * 60 * 60 * 1000)
            .apply()
    }

    override fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val KEY_ACCESS = "access_token"
        const val KEY_REFRESH = "refresh_token"
        const val KEY_REFRESH_EXPIRES = "refresh_expires_at"
    }
}

class InMemoryTokenStore : TokenStore {
    private var access: String? = null
    private var refresh: String? = null

    override fun accessToken(): String? = access

    override fun refreshToken(): String? = refresh

    override fun refreshExpiresAt(): Long = Long.MAX_VALUE

    override fun save(session: SessionResponse) {
        access = session.accessToken
        refresh = session.refreshToken
    }

    override fun clear() {
        access = null
        refresh = null
    }
}
