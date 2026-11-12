package ru.spkmd.myfiles

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import java.io.FileNotFoundException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.Executors
import javax.net.ssl.SSLException

class ShareReceiverActivity : Activity() {

    private lateinit var filesView: TextView
    private lateinit var statusView: TextView
    private lateinit var detailsView: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var retryButton: Button
    private lateinit var closeButton: Button

    private val executor = Executors.newSingleThreadExecutor()
    private val closeRunnable = Runnable { finish() }

    private var uris: List<Uri> = emptyList()
    private var uploader: Uploader? = null

    // Итоги по файлам копятся между попытками: «Повторить» отправляет только неудавшиеся
    private val results = LinkedHashMap<Uri, FileResult>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildScreen()

        uris = SharedFiles.collectUris(intent)
        if (uris.isEmpty()) {
            showFailure("Файлы не получены", "Приложение, из которого вы поделились, не передало ни одного файла.")
            return
        }
        startUpload()
    }

    // ------------------------------------------------------------------ экран

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun buildScreen() {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(32), dp(24), dp(24))
        }

        val title = TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 25f
            gravity = Gravity.CENTER
        }

        filesView = TextView(this).apply {
            textSize = 14f
            setTextColor(0xFF5F6B76.toInt())
            setPadding(0, dp(12), 0, 0)
        }

        statusView = TextView(this).apply {
            text = "Принимаю файлы…"
            textSize = 17f
            gravity = Gravity.CENTER
            setPadding(0, dp(20), 0, dp(12))
        }

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            isIndeterminate = true
        }

        detailsView = TextView(this).apply {
            textSize = 14f
            setTextIsSelectable(true)
            setPadding(0, dp(16), 0, 0)
            visibility = View.GONE
        }

        retryButton = Button(this).apply {
            text = "Повторить"
            visibility = View.GONE
            setOnClickListener { startUpload() }
        }

        closeButton = Button(this).apply {
            text = "Закрыть"
            setOnClickListener { finish() }
        }

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(20), 0, 0)
            addView(retryButton)
            addView(closeButton)
        }

        column.addView(title)
        column.addView(filesView, matchWidth())
        column.addView(statusView, matchWidth())
        column.addView(progressBar, matchWidth())
        column.addView(detailsView, matchWidth())
        column.addView(buttons, matchWidth())

        val scroll = ScrollView(this).apply {
            setBackgroundColor(0xFFF4F6F8.toInt())
            isFillViewport = true
            // targetSdk 35 включает edge-to-edge: отступаем от системных панелей
            fitsSystemWindows = true
            addView(column)
        }
        setContentView(scroll)
    }

    private fun matchWidth() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )

    // ------------------------------------------------------------------ загрузка

    private fun ui(block: () -> Unit) {
        runOnUiThread { if (!isFinishing && !isDestroyed) block() }
    }

    private fun startUpload() {
        statusView.removeCallbacks(closeRunnable)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        retryButton.visibility = View.GONE
        detailsView.visibility = View.GONE
        progressBar.visibility = View.VISIBLE
        progressBar.isIndeterminate = true
        statusView.text = "Подготовка…"

        val up = Uploader(contentResolver)
        uploader = up

        executor.execute {
            try {
                val files = SharedFiles.describe(this, uris)
                if (files.isEmpty()) {
                    ui { showFailure("Файлы не получены", "Не найдено ни одного доступного файла.") }
                    return@execute
                }
                ui {
                    filesView.text = renderFileList(files)
                    statusView.text = "Загружаю ${files.size} ${plural(files.size, "файл", "файла", "файлов")}…"
                }

                val pending = files.filter { results[it.uri]?.outcome?.kind != OutcomeKind.SUCCESS }
                up.upload(pending, "", { r -> results[r.file.uri] = r }) { sent, total, current ->
                    ui { showProgress(sent, total, current) }
                }
                val ordered = files.mapNotNull { results[it.uri] }
                val outcome = ServerReplies.summarize(ordered, files.size)
                ui { showOutcome(outcome) }
            } catch (e: UploadCancelledException) {
                // пользователь закрыл экран — показывать нечего
            } catch (e: Exception) {
                ui { showFailure("Ошибка загрузки", describeError(e)) }
            }
        }
    }

    private fun showProgress(sent: Long, total: Long, current: String) {
        if (total > 0) {
            progressBar.isIndeterminate = false
            progressBar.progress = (sent * 1000 / total).toInt()
            val percent = (sent * 100 / total).toInt()
            statusView.text = "Загружаю: $percent%\n${Formatter.formatShortFileSize(this, sent)} из " +
                Formatter.formatShortFileSize(this, total) + "\n" + current
        } else {
            progressBar.isIndeterminate = true
            statusView.text = "Загружаю: ${Formatter.formatShortFileSize(this, sent)}\n$current"
        }
    }

    private fun showOutcome(outcome: Outcome) {
        when (outcome.kind) {
            OutcomeKind.SUCCESS -> {
                finishScreen("✅ " + outcome.title, outcome.details)
                retryButton.visibility = View.GONE
                statusView.postDelayed(closeRunnable, if (outcome.details.isBlank()) 2500L else 5000L)
            }
            OutcomeKind.PARTIAL -> {
                finishScreen("⚠️ " + outcome.title, outcome.details)
                retryButton.visibility = View.VISIBLE
            }
            OutcomeKind.ERROR -> showFailure(outcome.title, outcome.details)
        }
    }

    private fun showFailure(title: String, details: String) {
        finishScreen("❌ $title", details)
        retryButton.visibility = if (uris.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun finishScreen(title: String, details: String) {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        progressBar.visibility = View.GONE
        statusView.text = title
        detailsView.text = details
        detailsView.visibility = if (details.isBlank()) View.GONE else View.VISIBLE
    }

    private fun renderFileList(files: List<SharedFile>): String {
        val shown = files.take(8).joinToString("\n") { f ->
            val size = if (f.size >= 0) " (${Formatter.formatShortFileSize(this, f.size)})" else ""
            "• ${f.name}$size"
        }
        val more = if (files.size > 8) "\n… и ещё ${files.size - 8}" else ""
        return shown + more
    }

    private fun describeError(e: Exception): String = when (e) {
        is UnknownHostException -> "Нет подключения к интернету или сервер files.spkmd.ru недоступен."
        is SocketTimeoutException -> "Превышено время ожидания ответа сервера. Проверьте соединение и повторите."
        is ConnectException -> "Не удалось подключиться к серверу."
        is SSLException -> "Ошибка защищённого соединения: ${e.message ?: e.javaClass.simpleName}"
        is SecurityException -> "Нет доступа к файлу: приложение-источник больше не разрешает его читать."
        is FileNotFoundException -> "Файл недоступен: ${e.message ?: ""}"
        else -> e.message ?: e.javaClass.simpleName
    }

    override fun onDestroy() {
        statusView.removeCallbacks(closeRunnable)
        uploader?.cancel()
        executor.shutdownNow()
        super.onDestroy()
    }
}
