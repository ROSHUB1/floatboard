package com.floatboard

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * Pro plan via offline-signed license keys (no Play Store, no server needed).
 * You sign keys with your private key (tools/license.js); the app only holds the PUBLIC key and
 * verifies the signature, so nobody can forge a key without your private key.
 *
 * Key format: FB1.<base64url payload json>.<base64url ECDSA-P256 signature>
 */
object Entitlement {
    const val FREE_SOUND_LIMIT = 20

    /** Optional link to where people can pay (Ko-fi, payment link, your page). Empty hides the button. */
    val PURCHASE_URL = ""

    /** Paste the output of `node tools/license.js keygen` here (base64 X.509 public key). */
    private const val PUBLIC_KEY_B64 = ""

    private const val TAG = "Entitlement"
    private lateinit var prefs: SharedPreferences

    var plan by mutableStateOf<String?>(null)
        private set
    var holder by mutableStateOf("")
        private set
    var expiresAtSec by mutableLongStateOf(0L)
        private set

    val isConfigured: Boolean get() = PUBLIC_KEY_B64.isNotBlank()

    val isPro: Boolean
        get() = plan == "pro" && (expiresAtSec == 0L || System.currentTimeMillis() / 1000 < expiresAtSec)

    fun canAddSound(): Boolean = isPro || SoundStore.sounds.size < FREE_SOUND_LIMIT

    @Synchronized
    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences("floatboard_license", Context.MODE_PRIVATE)
        prefs.getString("key", null)?.let { apply(it) }   // re-verified on every launch
    }

    /** Returns null on success, or a short error message for the UI. */
    fun activate(raw: String): String? {
        val error = apply(raw)
        if (error == null) prefs.edit().putString("key", raw.filterNot { it.isWhitespace() }).apply()
        return error
    }

    fun deactivate() {
        prefs.edit().remove("key").apply()
        plan = null
        holder = ""
        expiresAtSec = 0L
    }

    private fun apply(raw: String): String? {
        val pub = publicKey() ?: return "Licensing isn't set up in this build yet."
        val parts = raw.filterNot { it.isWhitespace() }.split('.')
        if (parts.size != 3 || parts[0] != "FB1") return "That doesn't look like a FloatBoard key."
        return try {
            val payload = decode(parts[1])
            val signature = decode(parts[2])
            val valid = Signature.getInstance("SHA256withECDSA").run {
                initVerify(pub)
                update(payload)
                verify(signature)
            }
            if (!valid) return "This key isn't valid."
            val json = JSONObject(String(payload, Charsets.UTF_8))
            if (json.optString("plan") != "pro") return "Unknown plan in this key."
            val exp = json.optLong("exp", 0L)
            if (exp != 0L && System.currentTimeMillis() / 1000 >= exp) return "This key has expired."
            plan = "pro"
            holder = json.optString("name")
            expiresAtSec = exp
            null
        } catch (e: Exception) {
            Log.w(TAG, "key rejected", e)
            "This key is damaged or incomplete."
        }
    }

    private fun decode(s: String): ByteArray {
        val padded = s + "=".repeat((4 - s.length % 4) % 4)
        return Base64.decode(padded, Base64.URL_SAFE or Base64.NO_WRAP)
    }

    private fun publicKey(): PublicKey? = try {
        if (PUBLIC_KEY_B64.isBlank()) null
        else KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.decode(PUBLIC_KEY_B64, Base64.DEFAULT)))
    } catch (e: Exception) {
        Log.e(TAG, "bad public key", e)
        null
    }
}
