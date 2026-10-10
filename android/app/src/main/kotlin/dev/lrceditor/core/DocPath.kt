package dev.lrceditor.core

/** Helpers for SAF document URIs like `content://auth/tree/<treeId>/document/<docId>` (both parts percent-encoded). */
object DocPath {
    fun percentDecode(s: String): String {
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length) {
                val v = s.substring(i + 1, i + 3).toIntOrNull(16)
                if (v != null) { out.write(v); i += 3; continue }
            }
            out.write(c.toString().toByteArray(Charsets.UTF_8)); i++
        }
        return out.toString(Charsets.UTF_8.name())
    }

    /** Best-effort absolute path of a document URI, e.g. `/storage/emulated/0/Music/Artist/Song.mp3`; null if not a document URI. */
    fun fullPath(uri: String): String? {
        val d = uri.indexOf("/document/")
        if (d < 0) return null
        val docId = percentDecode(uri.substring(d + 10))
        return if (docId.startsWith("primary:")) "/storage/emulated/0/" + docId.removePrefix("primary:") else docId
    }

    /** Path of the document below the tree root (e.g. `Artist/Album/Song.mp3`), or null if the URI isn't a tree document URI. */
    fun relativePath(uri: String): String? {
        val t = uri.indexOf("/tree/")
        val d = uri.indexOf("/document/")
        if (t < 0 || d < t) return null
        val treeId = percentDecode(uri.substring(t + 6, d))
        val docId = percentDecode(uri.substring(d + 10))
        return if (docId.startsWith(treeId)) docId.substring(treeId.length).trimStart('/') else null
    }
}
