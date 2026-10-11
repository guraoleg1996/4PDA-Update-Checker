package com.example.a4pdaupdatechecker

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Настраиваемый журнал работы сети.
 * Поддерживает лимиты размера файла от 50 КБ до 1 ГБ (по умолчанию 100 КБ).
 * Автоматически ротирует и очищает устаревшие записи, предотвращая бесконечный рост файла.
 */
object AppLog {

    private const val PREFS_NAME = "theme_prefs"
    private const val KEY_LOG_SIZE_LIMIT = "log_size_limit_kb"
    private const val DEFAULT_LIMIT_KB = 100

    private const val FILE_NAME = "network_log.txt"
    private const val MAX_LINE_LENGTH = 500

    private val lock = Any()
    private val lines = ArrayList<String>()
    private var file: File? = null
    private var appContext: Context? = null
    private val timeFormat = SimpleDateFormat("dd.MM HH:mm:ss", Locale.getDefault())

    /** Подключает файл для хранения. Вызывается один раз при старте приложения (см. App). */
    fun attach(context: Context) {
        synchronized(lock) {
            appContext = context.applicationContext
            if (file == null) {
                file = File(context.applicationContext.noBackupFilesDir, FILE_NAME)
            }
            val f = file ?: return
            try {
                if (f.exists()) {
                    lines.clear()
                    lines.addAll(f.readLines().takeLast(getMaxLines()))
                    trimToLimit()
                }
                rewriteFile()
            } catch (ignored: Exception) {
                // Журнал вспомогательный: если файл недоступен, работаем только в памяти.
            }
        }
    }

    fun getLimitKb(): Int {
        val ctx = appContext ?: return DEFAULT_LIMIT_KB
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_LOG_SIZE_LIMIT, DEFAULT_LIMIT_KB)
    }

    fun setLimitKb(context: Context, limitKb: Int) {
        synchronized(lock) {
            appContext = context.applicationContext
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putInt(KEY_LOG_SIZE_LIMIT, limitKb).apply()
            trimToLimit()
            rewriteFile()
        }
    }

    private fun getMaxFileSizeBytes(): Long = getLimitKb() * 1024L
    private fun getMaxLines(): Int = getLimitKb() * 2

    fun i(tag: String, message: String) = add('I', tag, message, null)

    fun w(tag: String, message: String) = add('W', tag, message, null)

    fun e(tag: String, message: String, throwable: Throwable? = null) = add('E', tag, message, throwable)

    private fun add(level: Char, tag: String, message: String, throwable: Throwable?) {
        try {
            when (level) {
                'I' -> Log.i(tag, message)
                'W' -> Log.w(tag, message)
                else -> Log.e(tag, message, throwable)
            }
        } catch (ignored: Throwable) {
            // Например, в юнит-тестах без Android.
        }

        synchronized(lock) {
            val text = message.replace('\n', ' ').replace('\r', ' ')
            val line = "${timeFormat.format(Date())} $level $text".take(MAX_LINE_LENGTH)
            lines.add(line)
            val maxLines = getMaxLines()
            val overflow = lines.size > maxLines
            trimToLimit()
            try {
                val f = file
                if (f != null) {
                    if (overflow || f.length() > getMaxFileSizeBytes()) {
                        rewriteFile()
                    } else {
                        f.appendText(line + "\n")
                    }
                }
            } catch (ignored: Exception) {
            }
        }
    }

    private fun trimToLimit() {
        val maxLines = getMaxLines()
        while (lines.size > maxLines) lines.removeAt(0)
    }

    private fun rewriteFile() {
        val f = file ?: return
        val content = if (lines.isEmpty()) "" else lines.joinToString("\n") + "\n"
        f.writeText(content)
    }

    /**
     * Возвращает текст журнала. Для плавной работы UI ограничивается последними maxDisplayLines (по умолч. 1000).
     */
    fun getText(maxDisplayLines: Int = 1000): String = synchronized(lock) {
        if (lines.isEmpty()) return ""
        if (lines.size <= maxDisplayLines) return lines.joinToString("\n")

        val tail = lines.takeLast(maxDisplayLines)
        return "[... показаны последние $maxDisplayLines из ${lines.size} записей ...]\n" + tail.joinToString("\n")
    }

    /**
     * Возвращает полный текст журнала без ограничений по количеству отображаемых строк (для отчетов).
     */
    fun getFullText(): String = synchronized(lock) { lines.joinToString("\n") }

    /**
     * Возвращает отформатированную информацию о текущем размере файла журнала на диске и лимитах.
     */
    fun getLogSizeInfo(): String = synchronized(lock) {
        val f = file
        val bytes = f?.length() ?: 0L
        val limitKb = getLimitKb()
        val lineCount = lines.size
        val maxLines = getMaxLines()

        val sizeFormatted = formatBytes(bytes)
        val limitFormatted = formatKb(limitKb)

        return "$sizeFormatted / макс. $limitFormatted ($lineCount / $maxLines записей)"
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes Б"
            bytes < 1024 * 1024 -> "%.1f КБ".format(Locale.US, bytes / 1024.0)
            bytes < 1024 * 1024 * 1024 -> "%.1f МБ".format(Locale.US, bytes / (1024.0 * 1024.0))
            else -> "%.2f ГБ".format(Locale.US, bytes / (1024.0 * 1024.0 * 1024.0))
        }
    }

    private fun formatKb(kb: Int): String {
        return when {
            kb < 1000 -> "$kb КБ"
            kb < 1_000_000 -> "${kb / 1000} МБ"
            else -> "${kb / 1_000_000} ГБ"
        }
    }

    fun clear() {
        synchronized(lock) {
            lines.clear()
            try {
                rewriteFile()
            } catch (ignored: Exception) {
            }
        }
    }

    /** Журнал с шапкой (версия приложения, Android и размер) — для копирования и отправки. */
    @Suppress("DEPRECATION")
    fun buildReport(context: Context): String {
        val versionName = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (ignored: Exception) {
            "?"
        }
        val header = "4PDA Update Checker $versionName\n" +
            "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}\n" +
            "Размер журнала: ${getLogSizeInfo()}\n" +
            "----------------------------------------\n"
        return header + getFullText()
    }
}
