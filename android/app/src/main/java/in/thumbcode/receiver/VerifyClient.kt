package `in`.thumbcode.receiver

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("thumbcode", Context.MODE_PRIVATE)

    /**
     * Defaults for the live project, so a fresh install scans without setup.
     * Anything entered on the settings screen overrides them. The anon key is
     * public by design: it grants nothing beyond what /verify already returns
     * to anyone holding a camera up to a printed code.
     */
    companion object {
        const val DEFAULT_URL = "https://mggopgforgmcexpkzsim.supabase.co"
        const val DEFAULT_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9." +
            "eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Im1nZ29wZ2ZvcmdtY2V4cGt6c2ltIiwicm9sZSI6ImFub24i" +
            "LCJpYXQiOjE3ODkyMjE4NTgsImV4cCI6MjEwNDc5Nzg1OH0.S-YaPNhCXOl2LGZ4-0_Lf2VOYl1V9n1V6D23YtgxyWo"
    }

    var projectUrl: String
        get() = prefs.getString("url", null)?.takeIf { it.isNotBlank() } ?: DEFAULT_URL
        set(v) = prefs.edit().putString("url", v.trim().trimEnd('/')).apply()

    var anonKey: String
        get() = prefs.getString("key", null)?.takeIf { it.isNotBlank() } ?: DEFAULT_KEY
        set(v) = prefs.edit().putString("key", v.trim()).apply()

    /**
     * A label for where this handset is checking documents. It is what makes
     * "same certificate verified in two cities this afternoon" visible in the
     * log, so it should name a counter or a town, not a person.
     */
    var place: String
        get() = prefs.getString("place", "") ?: ""
        set(v) = prefs.edit().putString("place", v.trim()).apply()

    val configured: Boolean get() = projectUrl.isNotEmpty() && anonKey.isNotEmpty()
    val placeOrDevice: String get() = place.ifEmpty { Build.MODEL }
}

sealed class Verdict {
    data class Verified(
        val docId: String,
        val docType: String,
        val officeName: String,
        val officeCode: String,
        val issueDate: String,
        val reference: String,
        val holderName: String,
        val referenceNo: String,
        val particulars: String,
        val signed: Boolean,
    ) : Verdict()

    data class Revoked(
        val docId: String,
        val reason: String,
        val at: String,
        val docType: String,
        val officeName: String,
        val officeCode: String,
        val issueDate: String,
        val reference: String,
        val holderName: String,
        val referenceNo: String,
        val particulars: String,
        val signed: Boolean,
    ) : Verdict()
    data class Unknown(val docId: String) : Verdict()
    data class Mismatch(val docId: String) : Verdict()
    data class Failed(val message: String) : Verdict()
}

class VerifyClient(private val settings: Settings) {

    fun verify(docId: String, spots: List<Int>): Verdict {
        if (!settings.configured) return Verdict.Failed("No Supabase project set. Open settings.")

        val body = JSONObject().apply {
            put("docId", docId)
            put("spots", JSONArray(spots))
            put("place", settings.placeOrDevice)
        }.toString()

        return try {
            val conn = (URL("${settings.projectUrl}/functions/v1/tc/verify").openConnection()
                    as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 8000
                readTimeout = 8000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("apikey", settings.anonKey)
                setRequestProperty("Authorization", "Bearer ${settings.anonKey}")
            }
            conn.outputStream.use { it.write(body.toByteArray()) }
            val text = (if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream)
                .bufferedReader().use { it.readText() }
            conn.disconnect()
            parse(JSONObject(text), docId)
        } catch (e: Exception) {
            Verdict.Failed(e.message ?: "Could not reach the verification service")
        }
    }

    private fun parse(json: JSONObject, docId: String): Verdict = when (json.optString("result")) {
        "verified" -> Verdict.Verified(
            docId = json.optString("docId", docId),
            docType = json.optString("docType"),
            officeName = json.optString("officeName"),
            officeCode = json.optString("officeCode"),
            issueDate = json.optString("issueDate"),
            reference = json.optString("reference"),
            holderName = json.optString("holderName"),
            referenceNo = json.optString("referenceNo"),
            particulars = json.optString("particulars"),
            signed = json.optString("signMode") == "webauthn",
        )
        "revoked" -> Verdict.Revoked(
            docId = json.optString("docId", docId),
            reason = json.optString("revokedReason", "No reason recorded"),
            at = json.optString("revokedAt"),
            docType = json.optString("docType"),
            officeName = json.optString("officeName"),
            officeCode = json.optString("officeCode"),
            issueDate = json.optString("issueDate"),
            reference = json.optString("reference"),
            holderName = json.optString("holderName"),
            referenceNo = json.optString("referenceNo"),
            particulars = json.optString("particulars"),
            signed = json.optString("signMode") == "webauthn",
        )
        "unknown" -> Verdict.Unknown(docId)
        "pattern_mismatch" -> Verdict.Mismatch(docId)
        else -> Verdict.Failed(json.optString("error", "Unexpected response"))
    }
}
