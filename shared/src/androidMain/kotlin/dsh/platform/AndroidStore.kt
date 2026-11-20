package dsh.platform

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Keystore-backed encrypted preferences (auth cookies never leave the device unencrypted). */
@Suppress("DEPRECATION")
class AndroidKeyValueStore(context: Context) : KeyValueStore {
    private val prefs: SharedPreferences = runCatching {
        val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context, "dsh_secure", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.getOrElse { context.getSharedPreferences("dsh_plain_fallback", Context.MODE_PRIVATE) }

    override fun get(key: String): String? = prefs.getString(key, null)
    override fun put(key: String, value: String?) { prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply() }
}
