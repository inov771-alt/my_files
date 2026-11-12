package ru.spkmd.myfiles

import android.content.ContentResolver
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.Reader
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.util.UUID

/** Размер файла не совпал с заявленным провайдером — нужно повторить без Content-Length. */
class SizeMismatchException(message: String) : IOException(message)

/** Сбой на нашей стороне (файл не открылся/не читается) — запрос нужно оборвать. */
class LocalReadException(message: String) : IOException(message)

class UploadCancelledException : IOException("Загрузка отменена")

/** Итог по одному файлу: [finalName] отличается от исходного, если сервер уже имел такое имя. */
class FileResult(val file: SharedFile, val finalName: String, val outcome: Outcome)

class ServerReply(val code: Int, val body: String, val location: String?)

class Uploader(
    private val resolver: ContentResolver,
    private val uploadUrl: String = UPLOAD_URL
) {

    companion object {
        const val UPLOAD_URL = "https://files.spkmd.ru/index.php?api=upload"

        private const val MAX_RENAMES = 20
        private const val BUFFER_SIZE = 64 * 1024
        private const val MAX_RESPONSE_CHARS = 64 * 1024
        private const val PROGRESS_INTERVAL_MS = 120L
        private val CRLF = "\r\n".toByteArray(Charsets.UTF_8)
    }

    @Volatile
    private var connection: HttpURLConnection? = null

    @Volatile
    private var cancelled = false

    /** Прерывает загрузку (можно вызывать из любого потока). */
    fun cancel() {
        cancelled = true
        connection?.disconnect()
    }

    /**
     * Загружает файлы по одному (multipart/form-data: поля `dir` и `files[]`).
     * Если сервер отвечает, что имя уже занято, файл отправляется заново под именем
     * «имя(1).ext», «имя(2).ext» и т.д. — существующие файлы не затираются.
     * [onFileDone] вызывается после окончательного ответа по каждому файлу.
     * [onProgress] получает (отправлено байт, всего байт или -1, имя текущего файла).
     */
    fun upload(
        files: List<SharedFile>,
        dir: String,
        onFileDone: (FileResult) -> Unit,
        onProgress: (sent: Long, total: Long, current: String) -> Unit
    ) {
        val grandTotal = if (files.all { it.size >= 0 }) files.sumOf { it.size } else -1L
        var offset = 0L

        // Что уже лежит на сервере (null — список получить не удалось, тогда полагаемся на ответ upload).
        val taken: MutableSet<String>? = fetchExistingNames(dir)

        for (f in files) {
            var attempt = 0
            var rejected = 0
            while (true) {
                if (cancelled) throw UploadCancelledException()

                var name = nameFor(f, attempt)
                while (taken != null && name in taken) {
                    attempt++
                    name = nameFor(f, attempt)
                }

                val candidate = SharedFile(f.uri, name, f.mime, f.size)
                val base = offset
                val reply = sendWithFallback(candidate, dir) { sent, _, current ->
                    onProgress(base + sent, grandTotal, current)
                }
                val outcome = ServerReplies.interpret(reply, 1)

                if (outcome.kind == OutcomeKind.SUCCESS) {
                    taken?.add(name)
                } else if (rejected < MAX_RENAMES && ServerReplies.isNameConflict(reply)) {
                    // Имя заняло что-то, чего мы не видели в списке (гонка) — пробуем следующее.
                    rejected++
                    taken?.add(name)
                    attempt++
                    continue
                }
                onFileDone(FileResult(f, name, outcome))
                break
            }
            if (f.size > 0) offset += f.size
        }
    }

    private fun nameFor(f: SharedFile, attempt: Int): String =
        if (attempt == 0) f.name else SharedFiles.numbered(f.name, attempt)

    /** GET ?api=list&dir=… → множество имён (файлы и папки). null, если не получилось. */
    private fun fetchExistingNames(dir: String): MutableSet<String>? {
        var conn: HttpURLConnection? = null
        return try {
            val listUrl = uploadUrl.replace("api=upload", "api=list") +
                "&dir=" + URLEncoder.encode(dir, "UTF-8")
            conn = URI(listUrl).toURL().openConnection() as HttpURLConnection
            connection = conn
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.useCaches = false
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("User-Agent", "MyFiles-Android/1.0")
            if (conn.responseCode != 200) return null

            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { readLimited(it, 8 * 1024 * 1024) }
            val json = JSONObject(body)
            if (!json.optBoolean("ok", false)) return null
            val items = json.optJSONArray("items") ?: return null

            val names = HashSet<String>()
            for (i in 0 until items.length()) {
                val name = items.optJSONObject(i)?.optString("name", "") ?: ""
                if (name.isNotEmpty()) names.add(name)
            }
            names
        } catch (e: Exception) {
            if (cancelled) throw UploadCancelledException()
            null
        } finally {
            conn?.disconnect()
            connection = null
        }
    }

    private fun sendWithFallback(
        file: SharedFile,
        dir: String,
        onProgress: (Long, Long, String) -> Unit
    ): ServerReply {
        val files = listOf(file)
        return try {
            send(files, dir, chunked = false, onProgress = onProgress)
        } catch (e: SizeMismatchException) {
            // Провайдер соврал о размере (Google Фото, Диск и т.п.) — повторяем потоково.
            if (cancelled) throw UploadCancelledException()
            send(files, dir, chunked = true, onProgress = onProgress)
        }
    }

    private fun send(
        files: List<SharedFile>,
        dir: String,
        chunked: Boolean,
        onProgress: (Long, Long, String) -> Unit
    ): ServerReply {
        val boundary = "----MyFilesBoundary" + UUID.randomUUID().toString().replace("-", "")

        val dirPart = (
            "--$boundary\r\n" +
                "Content-Disposition: form-data; name=\"dir\"\r\n\r\n" +
                dir + "\r\n"
            ).toByteArray(Charsets.UTF_8)

        // Имена пишутся «сырым» UTF-8, как это делают браузеры — PHP читает их без перекодирования.
        val headers = files.map { f ->
            (
                "--$boundary\r\n" +
                    "Content-Disposition: form-data; name=\"files[]\"; filename=\"${f.name}\"\r\n" +
                    "Content-Type: ${f.mime}\r\n\r\n"
                ).toByteArray(Charsets.UTF_8)
        }
        val closing = "--$boundary--\r\n".toByteArray(Charsets.UTF_8)

        val sizesKnown = files.all { it.size >= 0 }
        val totalFileBytes = if (sizesKnown) files.sumOf { it.size } else -1L
        val declaredLengths = sizesKnown && !chunked

        val conn = URI(uploadUrl).toURL().openConnection() as HttpURLConnection
        connection = conn
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.doInput = true
            conn.useCaches = false
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 30_000
            conn.readTimeout = 300_000
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            conn.setRequestProperty("Accept", "application/json, text/plain, */*")
            conn.setRequestProperty("User-Agent", "MyFiles-Android/1.0")

            // КЛЮЧЕВОЕ для больших файлов: без режима потока HttpURLConnection копит
            // всё тело запроса в памяти и падает с OutOfMemoryError.
            if (declaredLengths) {
                var body = dirPart.size.toLong() + closing.size
                for (i in files.indices) body += headers[i].size + files[i].size + CRLF.size
                conn.setFixedLengthStreamingMode(body)
            } else {
                conn.setChunkedStreamingMode(BUFFER_SIZE)
            }

            var sent = 0L
            var lastReport = 0L
            onProgress(0L, totalFileBytes, files.firstOrNull()?.name.orEmpty())

            val out = BufferedOutputStream(conn.outputStream, BUFFER_SIZE)
            try {
                out.write(dirPart)

                for (i in files.indices) {
                    val f = files[i]
                    out.write(headers[i])

                    val input = try {
                        resolver.openInputStream(f.uri)
                    } catch (e: SecurityException) {
                        throw LocalReadException("Нет доступа к файлу «${f.name}»")
                    } ?: throw LocalReadException("Не удалось открыть файл «${f.name}»")

                    var copied = 0L
                    input.use { stream ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            if (cancelled) throw UploadCancelledException()
                            val n = try {
                                stream.read(buffer)
                            } catch (e: IOException) {
                                throw LocalReadException("Ошибка чтения «${f.name}»: ${e.message ?: ""}")
                            }
                            if (n < 0) break
                            if (declaredLengths && copied + n > f.size) {
                                throw SizeMismatchException("Размер «${f.name}» больше заявленного")
                            }
                            out.write(buffer, 0, n)
                            copied += n
                            sent += n

                            val now = System.currentTimeMillis()
                            if (now - lastReport >= PROGRESS_INTERVAL_MS) {
                                lastReport = now
                                onProgress(sent, totalFileBytes, f.name)
                            }
                        }
                    }
                    if (declaredLengths && copied != f.size) {
                        throw SizeMismatchException("Размер «${f.name}» меньше заявленного")
                    }
                    out.write(CRLF)
                }

                out.write(closing)
                out.flush()
                out.close()
            } catch (e: IOException) {
                // Локальные сбои и отмена: прерываем соединение, а не «закрываем» запрос
                // с обрезанным телом — иначе сервер мог бы принять неполный файл.
                if (cancelled) {
                    conn.disconnect()
                    throw UploadCancelledException()
                }
                if (e is SizeMismatchException || e is LocalReadException) {
                    conn.disconnect()
                    throw e
                }
                // Сетевой сбой при записи: сервер мог ответить до конца передачи (например, 413).
                val early = try {
                    readReply(conn)
                } catch (ignored: Exception) {
                    null
                }
                if (early != null && early.code > 0) return early
                throw IOException(
                    "Соединение оборвано во время передачи (${e.message ?: e.javaClass.simpleName}). " +
                        "Если файл очень большой, возможно, сервер ограничивает размер загрузки.",
                    e
                )
            }
            onProgress(sent, totalFileBytes, files.lastOrNull()?.name.orEmpty())

            return readReply(conn)
        } catch (e: IOException) {
            if (cancelled) throw UploadCancelledException()
            throw e
        } finally {
            conn.disconnect()
            connection = null
        }
    }

    private fun readReply(conn: HttpURLConnection): ServerReply {
        val code = conn.responseCode
        val stream = if (code >= 400) conn.errorStream else conn.inputStream
        val body = stream?.bufferedReader(Charsets.UTF_8)?.use { readLimited(it) } ?: ""
        return ServerReply(code, body, conn.getHeaderField("Location"))
    }

    private fun readLimited(reader: Reader, maxChars: Int = MAX_RESPONSE_CHARS): String {
        val sb = StringBuilder()
        val buf = CharArray(4096)
        while (sb.length < maxChars) {
            val n = reader.read(buf)
            if (n < 0) break
            sb.append(buf, 0, minOf(n, maxChars - sb.length))
        }
        return sb.toString()
    }
}

// ------------------------------------------------------------------ разбор ответа

enum class OutcomeKind { SUCCESS, PARTIAL, ERROR }

class Outcome(val kind: OutcomeKind, val title: String, val details: String = "")

object ServerReplies {

    /**
     * Ожидаемый ответ сервера: JSON вида {"ok":true,"failed":[...]}.
     * Любое отклонение (не JSON, ok != true, непустой failed, код не 2xx) показывается как ошибка.
     */
    fun interpret(reply: ServerReply, sentCount: Int): Outcome {
        val code = reply.code
        val body = reply.body.trim()

        if (code in 300..399) {
            val where = reply.location?.let { "\nАдрес: $it" } ?: ""
            return Outcome(OutcomeKind.ERROR, "Сервер перенаправил запрос (HTTP $code)", where.trim())
        }

        val json: JSONObject? = try {
            if (body.startsWith("{")) JSONObject(body) else null
        } catch (e: Exception) {
            null
        }

        if (code !in 200..299) {
            val title = when (code) {
                401, 403 -> "Доступ запрещён (HTTP $code)"
                404 -> "Адрес загрузки не найден (HTTP 404)"
                413 -> "Файл слишком большой для сервера (HTTP 413)"
                in 500..599 -> "Ошибка на сервере (HTTP $code)"
                else -> "Сервер вернул ошибку (HTTP $code)"
            }
            var message = messageFrom(json) ?: snippet(body)
            if (message.contains("Файлы не получены")) {
                // PHP обнуляет $_FILES, если тело запроса больше post_max_size
                message += "\nВозможно, файл больше лимита сервера (post_max_size / upload_max_filesize / client_max_body_size)."
            }
            return Outcome(OutcomeKind.ERROR, title, message)
        }

        if (json == null) {
            return Outcome(OutcomeKind.ERROR, "Сервер вернул неожиданный ответ", snippet(body))
        }

        if (!json.optBoolean("ok", false)) {
            return Outcome(OutcomeKind.ERROR, "Сервер отклонил загрузку", messageFrom(json) ?: snippet(body))
        }

        val failed: JSONArray? = json.optJSONArray("failed")
        if (failed != null && failed.length() > 0) {
            val list = (0 until failed.length()).joinToString("\n") { "• " + describeFailed(failed.opt(it)) }
            val done = (sentCount - failed.length()).coerceAtLeast(0)
            return Outcome(
                OutcomeKind.PARTIAL,
                "Загружено $done из $sentCount, остальные не приняты сервером",
                list
            )
        }

        return Outcome(OutcomeKind.SUCCESS, "Загружено: $sentCount ${plural(sentCount, "файл", "файла", "файлов")}")
    }

    private fun describeFailed(entry: Any?): String {
        if (entry is JSONObject) {
            val name = entry.optString("name", "")
            val error = humanizeUploadError(entry.optString("error", ""))
            return if (name.isEmpty()) error else "$name: $error"
        }
        return entry.toString()
    }

    /** «Ошибка загрузки: 1» (код PHP UPLOAD_ERR_*) → понятный текст. */
    private fun humanizeUploadError(text: String): String {
        val code = Regex("^Ошибка загрузки: (\\d+)$").find(text.trim())?.groupValues?.get(1)?.toIntOrNull()
            ?: return text
        return when (code) {
            1, 2 -> "файл больше лимита сервера (upload_max_filesize / post_max_size)"
            3 -> "файл передан не полностью"
            4 -> "файл не был передан"
            6, 7, 8 -> "сервер не смог записать файл"
            else -> text
        }
    }

    /** Признаки того, что файл не принят из-за уже существующего имени. */
    fun isNameConflict(reply: ServerReply): Boolean {
        if (reply.code == 409) return true
        val text = reply.body.lowercase()
        return listOf("уже существует", "already exists", "file exists", "name is taken", "имя уже занято").any { it in text }
    }

    /** Общий итог по всем файлам для показа на экране. */
    fun summarize(results: List<FileResult>, expected: Int): Outcome {
        val ok = results.filter { it.outcome.kind == OutcomeKind.SUCCESS }
        val bad = results.filter { it.outcome.kind != OutcomeKind.SUCCESS }

        val lines = ArrayList<String>()
        for (r in ok) {
            if (r.finalName != r.file.name) {
                lines.add("• ${r.file.name} → ${r.finalName} (такое имя уже было на сервере)")
            }
        }
        for (r in bad) {
            val extra = if (r.outcome.details.isBlank()) "" else " — " + r.outcome.details.replace("\n", "; ")
            lines.add("• ${r.file.name}: ${r.outcome.title}$extra")
        }

        return when {
            bad.isEmpty() -> Outcome(
                OutcomeKind.SUCCESS,
                "Загружено: ${ok.size} ${plural(ok.size, "файл", "файла", "файлов")}",
                lines.joinToString("\n")
            )
            ok.isEmpty() -> Outcome(
                OutcomeKind.ERROR,
                if (bad.size == 1) bad[0].outcome.title else "Не удалось загрузить файлы",
                lines.joinToString("\n")
            )
            else -> Outcome(
                OutcomeKind.PARTIAL,
                "Загружено ${ok.size} из $expected",
                lines.joinToString("\n")
            )
        }
    }

    private fun messageFrom(json: JSONObject?): String? {
        if (json == null) return null
        for (key in arrayOf("error", "message", "msg")) {
            val v = json.opt(key)
            if (v != null && v != JSONObject.NULL && v.toString().isNotBlank()) return v.toString()
        }
        return null
    }

    private fun snippet(body: String): String {
        if (body.isBlank()) return "(пустой ответ)"
        // HTML-страницы ошибок режем до читаемого размера
        val flat = body.replace(Regex("<[^>]*>"), " ").replace(Regex("\\s+"), " ").trim()
        val text = flat.ifEmpty { body.trim() }
        return if (text.length > 400) text.substring(0, 400) + "…" else text
    }
}

fun plural(n: Int, one: String, few: String, many: String): String {
    val m100 = n % 100
    val m10 = n % 10
    return when {
        m100 in 11..14 -> many
        m10 == 1 -> one
        m10 in 2..4 -> few
        else -> many
    }
}
