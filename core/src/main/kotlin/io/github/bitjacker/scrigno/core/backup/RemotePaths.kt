package io.github.bitjacker.scrigno.core.backup

import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/** Helpers for the `/`-separated paths used on the server. */
object RemotePaths {

    private const val TEMP_SUFFIX = ".scrigno-part"
    private const val MAX_NAME_LENGTH = 180
    private val INVALID_CHARS = Regex("[\\\\/:*?\"<>|\\u0000-\\u001F\\u007F]")

    /** Splits a path in its non empty segments. Both `/` and `\` are accepted as separators. */
    fun segments(path: String): List<String> =
        path.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }

    /** `a//b/` -> `a/b`, `\\srv\\x` -> `/srv/x`. An absolute path keeps its leading slash. */
    fun normalize(path: String): String {
        val trimmed = path.trim().replace('\\', '/')
        val body = segments(trimmed).joinToString("/")
        return if (trimmed.startsWith("/")) "/$body" else body
    }

    /**
     * Joins path parts. The result is absolute only if the first non blank part is absolute:
     * `join("/base", "a/b") = "/base/a/b"`, `join("", "a") = "a"`, `join("/", "") = "/"`.
     */
    fun join(vararg parts: String): String {
        val first = parts.firstOrNull { it.isNotBlank() }?.trim()?.replace('\\', '/')
        val absolute = first?.startsWith("/") == true
        val body = parts.flatMap { segments(it) }.joinToString("/")
        return if (absolute) "/$body" else body
    }

    fun parent(path: String): String {
        val normalized = normalize(path)
        val segments = segments(normalized)
        if (segments.isEmpty()) return if (normalized.startsWith("/")) "/" else ""
        val body = segments.dropLast(1).joinToString("/")
        return if (normalized.startsWith("/")) "/$body" else body
    }

    fun name(path: String): String = segments(path).lastOrNull() ?: ""

    /** Folder of the month in which a photo was taken, e.g. `2024/05`. */
    fun monthFolder(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val date = Instant.ofEpochMilli(epochMillis).atZone(zone)
        return String.format(Locale.ROOT, "%04d/%02d", date.year, date.monthValue)
    }

    /**
     * Makes a file name safe for every server: no separators or characters Windows/SMB refuse,
     * no trailing dots or spaces, bounded length (the extension is preserved).
     */
    fun sanitizeFileName(name: String): String {
        var clean = name.replace(INVALID_CHARS, "_").trim().trimEnd('.', ' ')
        if (clean.isEmpty() || clean == "." || clean == "..") clean = "file"
        if (clean.length > MAX_NAME_LENGTH) {
            val (base, ext) = splitExtension(clean)
            val keep = (MAX_NAME_LENGTH - ext.length).coerceAtLeast(1)
            clean = base.take(keep) + ext
        }
        return clean
    }

    /** `IMG_1.jpg` with attempt 2 -> `IMG_1_2.jpg`. Attempt 0 returns the name unchanged. */
    fun withCollisionSuffix(name: String, attempt: Int): String {
        if (attempt <= 0) return name
        val (base, ext) = splitExtension(name)
        return "${base}_$attempt$ext"
    }

    /** Name used while a file is being uploaded; renamed to the final name once complete. */
    fun tempName(name: String): String = name + TEMP_SUFFIX

    fun isTempName(name: String): Boolean = name.endsWith(TEMP_SUFFIX)

    /** Splits `photo.jpg` in `photo` and `.jpg`. A leading dot is not an extension. */
    fun splitExtension(name: String): Pair<String, String> {
        val dot = name.lastIndexOf('.')
        return if (dot <= 0 || dot == name.length - 1) name to "" else name.substring(0, dot) to name.substring(dot)
    }
}
