package dev.lrceditor.data.lyrics

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Minimal blocking HTTP GET on top of HttpURLConnection. Call from Dispatchers.IO. */
object Http {
    class Response(val code: Int, val body: String)

    const val BROWSER_UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
    private const val MAX_BYTES = 2 * 1024 * 1024

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    fun get(url: String, timeoutMs: Int, headers: Map<String, String> = emptyMap()): Response {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.instanceFollowRedirects = true
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            val code = c.responseCode
            val stream = if (code >= 400) c.errorStream else c.inputStream
            val body = stream?.use { s ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(8192)
                while (true) {
                    val n = s.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > MAX_BYTES) throw IOException("response too large")
                }
                out.toString(Charsets.UTF_8.name())
            }.orEmpty()
            return Response(code, body)
        } finally {
            c.disconnect()
        }
    }

    /** Throws [BlockedException] for the status codes that mean "blocked" or "slow down". */
    fun requireOk(r: Response, what: String) {
        if (r.code == 403 || r.code == 429 || r.code == 503) throw BlockedException("$what blocked or rate limited (HTTP ${r.code})")
        if (r.code !in 200..299) throw IOException("$what answered HTTP ${r.code}")
    }
}
