package ru.spkmd.myfiles

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.File
import java.text.Normalizer
import java.util.Locale

/** Файл, полученный через «Поделиться», с уже разобранными метаданными. */
class SharedFile(
    val uri: Uri,
    /** Безопасное имя для отправки (NFC, без путей и управляющих символов, уникальное в пачке). */
    val name: String,
    val mime: String,
    /** Размер в байтах или -1, если источник его не сообщил. */
    val size: Long
)

object SharedFiles {

    /** Сервер отклоняет запрос, если имя длиннее 180 символов; байтов не меньше, чем символов. */
    private const val MAX_NAME_BYTES = 180

    // ---------------------------------------------------------------- приём Intent

    /** Собирает URI из ACTION_SEND / ACTION_SEND_MULTIPLE (EXTRA_STREAM и ClipData), без дублей. */
    fun collectUris(intent: Intent): List<Uri> {
        val result = LinkedHashSet<Uri>()

        // Порядок: сначала то, что соответствует action, потом запасные варианты —
        // часть приложений кладёт список в SEND или одиночный Uri в SEND_MULTIPLE.
        if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
            result.addAll(streamList(intent))
            streamSingle(intent)?.let { result.add(it) }
        } else {
            streamSingle(intent)?.let { result.add(it) }
            result.addAll(streamList(intent))
        }

        val clip = intent.clipData
        if (clip != null) {
            for (i in 0 until clip.itemCount) {
                clip.getItemAt(i).uri?.let { result.add(it) }
            }
        }

        return result.filter { it.scheme == ContentResolver.SCHEME_CONTENT || it.scheme == ContentResolver.SCHEME_FILE }
    }

    private fun streamSingle(intent: Intent): Uri? = try {
        if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        }
    } catch (e: Exception) {
        null
    }

    private fun streamList(intent: Intent): List<Uri> = try {
        val list: List<Uri>? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
        }
        list?.filterNotNull() ?: emptyList()
    } catch (e: Exception) {
        emptyList()
    }

    // ---------------------------------------------------------------- метаданные

    /**
     * Читает имя, MIME и размер через ContentResolver. Может обращаться к провайдеру,
     * поэтому вызывать не из UI-потока.
     */
    fun describe(context: Context, uris: List<Uri>): List<SharedFile> {
        val resolver = context.contentResolver
        val usedNames = HashSet<String>()
        val privateDir = context.applicationInfo.dataDir
        val result = ArrayList<SharedFile>(uris.size)

        for (uri in uris) {
            if (uri.scheme == ContentResolver.SCHEME_FILE && isInsidePrivateDir(uri, privateDir)) {
                continue // не отдаём наружу собственные данные приложения
            }

            var rawName: String? = null
            var size = -1L

            if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
                try {
                    resolver.query(
                        uri,
                        arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                        null, null, null
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (nameIdx >= 0 && !cursor.isNull(nameIdx)) rawName = cursor.getString(nameIdx)
                            val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                            if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) size = cursor.getLong(sizeIdx)
                        }
                    }
                } catch (e: Exception) {
                    // провайдер не поддерживает query — возьмём имя из URI
                }
            } else {
                uri.path?.let { path ->
                    val f = File(path)
                    if (f.isFile) size = f.length()
                }
            }

            if (rawName.isNullOrBlank()) rawName = uri.lastPathSegment

            if (size < 0) {
                try {
                    resolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                        if (afd.length >= 0) size = afd.length
                    }
                } catch (e: Exception) {
                    // размер останется неизвестным — загрузка пойдёт потоковым режимом
                }
            }

            val mime = resolveMime(resolver.getType(uri), rawName)
            val name = uniqueName(sanitizeName(withExtension(rawName, mime)), usedNames)
            result.add(SharedFile(uri, name, mime, size))
        }
        return result
    }

    private fun isInsidePrivateDir(uri: Uri, privateDir: String): Boolean {
        val path = uri.path ?: return true
        return try {
            val canonical = File(path).canonicalPath
            canonical == privateDir || canonical.startsWith("$privateDir/")
        } catch (e: Exception) {
            true
        }
    }

    // ---------------------------------------------------------------- MIME

    private fun resolveMime(reported: String?, name: String?): String {
        val clean = reported?.trim()?.lowercase(Locale.ROOT)
            ?.takeIf { it.isNotEmpty() && '*' !in it && '\r' !in it && '\n' !in it && '/' in it }
        if (clean != null && clean != "application/octet-stream") return clean

        val ext = name?.substringAfterLast('.', "")?.lowercase(Locale.ROOT).orEmpty()
        if (ext.isNotEmpty()) {
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)?.let { return it }
        }
        return clean ?: "application/octet-stream"
    }

    /** Если у файла нет расширения, а тип известен — добавляем расширение (IMG → IMG.jpg). */
    private fun withExtension(name: String?, mime: String): String {
        val base = name?.takeIf { it.isNotBlank() } ?: "file"
        if (base.substringAfterLast('.', "").isNotEmpty() && '.' in base.trimStart('.')) return base
        if (mime == "application/octet-stream") return base
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: return base
        return "$base.$ext"
    }

    // ---------------------------------------------------------------- имена

    /**
     * Приводит имя к безопасному виду:
     *  - NFC (имена с macOS/iOS приходят разложенными: «й» = «и» + U+0306);
     *  - без каталогов, управляющих символов и кавычек;
     *  - без ведущих точек («.», «..», «.htaccess»);
     *  - не длиннее 180 байт UTF-8 с сохранением расширения.
     */
    fun sanitizeName(raw: String): String {
        var n = Normalizer.normalize(raw, Normalizer.Form.NFC)
        n = n.substringAfterLast('/').substringAfterLast('\\')
        n = buildString(n.length) {
            for (ch in n) {
                append(if (ch.isISOControl() || ch == '"' || ch == ' ' || ch == ' ') '_' else ch)
            }
        }.trim().trimStart('.').trim()
        if (n.isEmpty()) n = "file"
        return truncateUtf8(n, MAX_NAME_BYTES)
    }

    private fun truncateUtf8(name: String, maxBytes: Int): String {
        if (name.toByteArray(Charsets.UTF_8).size <= maxBytes) return name
        val dot = name.lastIndexOf('.')
        val ext = if (dot > 0 && name.length - dot <= 16) name.substring(dot) else ""
        val base = if (ext.isEmpty()) name else name.substring(0, dot)
        val budget = maxBytes - ext.toByteArray(Charsets.UTF_8).size

        val sb = StringBuilder()
        var used = 0
        var i = 0
        while (i < base.length) {
            val cp = base.codePointAt(i)
            val chars = Character.charCount(cp)
            val bytes = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).size
            if (used + bytes > budget) break
            sb.appendCodePoint(cp)
            used += bytes
            i += chars
        }
        return sb.toString().ifEmpty { "file" } + ext
    }

    /** «файл.txt» + 1 → «файл(1).txt»; итог всегда укладывается в лимит имени. */
    fun numbered(name: String, n: Int): String {
        val suffix = "($n)"
        val shortened = truncateUtf8(name, MAX_NAME_BYTES - suffix.toByteArray(Charsets.UTF_8).size)
        val dot = shortened.lastIndexOf('.')
        val base = if (dot > 0) shortened.substring(0, dot) else shortened
        val ext = if (dot > 0) shortened.substring(dot) else ""
        return "$base$suffix$ext"
    }

    /** Гарантирует, что внутри одной отправки два файла не получат одинаковое имя. */
    private fun uniqueName(name: String, used: MutableSet<String>): String {
        if (used.add(name.lowercase(Locale.ROOT))) return name
        var counter = 1
        while (true) {
            val candidate = numbered(name, counter)
            if (used.add(candidate.lowercase(Locale.ROOT))) return candidate
            counter++
        }
    }
}
