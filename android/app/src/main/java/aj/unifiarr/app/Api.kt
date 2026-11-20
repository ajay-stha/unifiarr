package aj.unifiarr.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.util.concurrent.TimeUnit

/** Talks to the user's Unifiarr server, which holds every API key and proxies to the services (same routes the web app uses). */
object Api {
    /** e.g. "http://192.168.1.10:4180/" (always ends with a slash). */
    var base = ""

    class Failure(message: String, val status: Int) : Exception(message)

    // The server only answers /api calls carrying this header (CSRF guard); images go through the same client via Coil.
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val request = chain.request()
            chain.proceed(if (base.isNotEmpty() && request.url.toString().startsWith(base)) request.newBuilder().header("x-unifiarr", "1").build() else request)
        }
        .build()

    private val jsonType = "application/json".toMediaType()

    /** Calls `api/<service>/<path>`; returns a JSONObject, JSONArray or null. `body` may be a Map, List, JSONObject, JSONArray or a ready RequestBody. */
    suspend fun call(service: String, path: String = "", method: String = "GET", body: Any? = null): Any? = withContext(Dispatchers.IO) {
        val payload: RequestBody? = when (body) {
            null -> if (method == "GET" || method == "DELETE") null else ByteArray(0).toRequestBody(null)
            is RequestBody -> body
            else -> toJson(body).toString().toRequestBody(jsonType)
        }
        val request = Request.Builder().url(base + "api/" + service + (if (path.isEmpty()) "" else "/$path")).method(method, payload).build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw Failure(message(text).ifBlank { "$service returned ${response.code}." }, response.code)
            if (text.isBlank()) null else runCatching { JSONTokener(text).nextValue() }.getOrDefault(text)
        }
    }

    suspend fun obj(service: String, path: String = "", method: String = "GET", body: Any? = null) = call(service, path, method, body) as JSONObject
    suspend fun arr(service: String, path: String = "", method: String = "GET", body: Any? = null) = call(service, path, method, body) as JSONArray

    /** Image URLs: absolute ones pass through, server-relative ones ("api/jellyfin/…") are resolved against the server. */
    fun image(path: String?): String? = when {
        path.isNullOrBlank() -> null
        path.startsWith("http") -> path
        else -> base + path.trimStart('/')
    }

    private fun message(text: String): String {
        val json = runCatching { JSONTokener(text).nextValue() }.getOrNull()
        return when (json) {
            is JSONObject -> json.optString("message").ifBlank { json.optString("title") }.ifBlank { text.trim() }
            is JSONArray -> json.optJSONObject(0)?.optString("errorMessage").orEmpty().ifBlank { text.trim() }
            else -> text.trim()
        }
    }

    fun toJson(value: Any?): Any? = when (value) {
        is JSONObject, is JSONArray, null -> value ?: JSONObject.NULL
        is Map<*, *> -> JSONObject().apply { value.forEach { (k, v) -> put(k.toString(), toJson(v)) } }
        is Iterable<*> -> JSONArray().apply { value.forEach { put(toJson(it)) } }
        is Array<*> -> JSONArray().apply { value.forEach { put(toJson(it)) } }
        else -> value
    }
}

// JSON helpers: org.json's optString returns "null" for JSON null, these don't.
fun JSONObject.str(key: String): String = if (isNull(key)) "" else optString(key)
fun JSONObject.strOrNull(key: String): String? = if (isNull(key)) null else optString(key).ifEmpty { null }
fun JSONObject.obj(key: String): JSONObject? = optJSONObject(key)
fun JSONObject.list(key: String): List<JSONObject> = optJSONArray(key).objects()
fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { optString(it) }
fun JSONObject.copy(): JSONObject = JSONObject(toString())
