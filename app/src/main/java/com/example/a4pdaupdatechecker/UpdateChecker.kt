package com.example.a4pdaupdatechecker

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.dnsoverhttps.DnsOverHttps
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class AppData(
    val version: String?,
    val name: String?,
    val packageName: String?,
    val isError: Boolean = false
)

/**
 * СЕТЕВАЯ ЧАСТЬ.
 *
 * Принципы, чтобы она не ломалась со временем:
 *  1. Ничего не «зашито» навсегда: версия браузера в User-Agent вычисляется по дате
 *     и обновляется сама, как обновляется настоящий Chrome.
 *  2. Несколько запасных путей: системный DNS -> 4 независимых DNS-over-HTTPS сервиса;
 *     два вида User-Agent; повторы с нарастающей паузой; уважение к Retry-After.
 *  3. Защита от «лавины»: если сайт полностью недоступен, остальные ссылки в том же
 *     обновлении не мучаются долгими повторами.
 *  4. Любая причина сбоя пишется в Logcat (тег UpdateChecker).
 */
object UpdateChecker {

    private const val TAG = "UpdateChecker"
    private const val BASE_URL = "https://4pda.to/forum/index.php?showtopic="

    private const val MAX_ATTEMPTS = 5
    private const val RETRY_DELAY_MS = 1500L
    private const val MAX_PAUSE_MS = 10_000L
    private const val MAX_BODY_BYTES = 3L * 1024 * 1024
    private const val CACHE_TTL_MS = 15_000L

    // ---------- User-Agent, который «стареет» вместе с реальным Chrome ----------

    // Опорная точка: Chrome 140 вышел 2 сентября 2025. Новый Chrome выходит примерно раз в 4 недели.
    private const val CHROME_BASE_MAJOR = 140
    private const val CHROME_BASE_TIME_MS = 1_756_771_200_000L
    private const val CHROME_CYCLE_MS = 28L * 24 * 60 * 60 * 1000

    private fun chromeMajor(): Int {
        val cyclesPassed = ((System.currentTimeMillis() - CHROME_BASE_TIME_MS) / CHROME_CYCLE_MS).toInt()
        // «-2» — запас, чтобы не назваться версией, которой ещё не существует.
        return (CHROME_BASE_MAJOR + cyclesPassed - 2)
            .coerceIn(CHROME_BASE_MAJOR, CHROME_BASE_MAJOR + 100)
    }

    private fun userAgents(): List<String> {
        val v = chromeMajor()
        return listOf(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/$v.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/$v.0.0.0 Mobile Safari/537.36"
        )
    }

    // ---------- Ошибки ----------

    /** Сервер ответил кодом ошибки (403, 429, 503 и т.д.). */
    private class HttpStatusException(val code: Int, val retryAfterSeconds: Long? = null) :
        IOException("HTTP $code")

    /** Вместо страницы темы сайт показал «проверку браузера». */
    private class BotCheckException :
        IOException("Сайт вернул страницу проверки браузера (защита от ботов)")

    // ---------- Клиенты ----------

    /** Простое хранилище cookie в памяти: сайт может выдать cookie и сразу сделать редирект. */
    private val cookieJar = object : CookieJar {
        private val store = HashMap<String, MutableMap<String, Cookie>>()

        @Synchronized
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val forHost = store.getOrPut(url.host) { HashMap() }
            cookies.forEach { forHost[it.name] = it }
        }

        @Synchronized
        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val now = System.currentTimeMillis()
            return store[url.host].orEmpty().values.filter { it.expiresAt > now && it.matches(url) }
        }
    }

    /** Обычный клиент: DNS берётся из системы. */
    private val systemClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .cookieJar(cookieJar)
            .build()
    }

    /**
     * Запасной клиент: адрес сайта узнаётся через DNS-over-HTTPS (как делают браузеры).
     * Нужен, когда системный DNS не отдаёт адрес сайта или отдаёт заглушку.
     */
    private val dohClient: OkHttpClient by lazy {
        systemClient.newBuilder().dns(buildDohDns()).build()
    }

    private fun buildDohDns(): Dns {
        val bootstrap = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()

        fun provider(url: String, vararg ips: String): DnsOverHttps =
            DnsOverHttps.Builder()
                .client(bootstrap)
                .url(url.toHttpUrl())
                .bootstrapDnsHosts(ips.map { InetAddress.getByName(it) })
                .includeIPv6(false)
                .build()

        // Четыре независимых сервиса: если один заблокирован или изменился — работает следующий.
        val providers by lazy {
            listOf(
                provider("https://cloudflare-dns.com/dns-query", "1.1.1.1", "1.0.0.1"),
                provider("https://dns.google/dns-query", "8.8.8.8", "8.8.4.4"),
                provider("https://dns.quad9.net/dns-query", "9.9.9.9", "149.112.112.112"),
                provider("https://dns.adguard-dns.com/dns-query", "94.140.14.14", "94.140.15.15")
            )
        }

        return object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                var last: Exception? = null
                for (p in providers) {
                    try {
                        val result = p.lookup(hostname)
                        if (result.isNotEmpty()) return result
                    } catch (e: Exception) {
                        last = e
                    }
                }
                throw UnknownHostException("DoH не смог найти адрес: $hostname").also { ex ->
                    last?.let { ex.initCause(it) }
                }
            }
        }
    }

    private val cache = ConcurrentHashMap<String, Pair<Long, AppData>>()

    // ---------- Запросы ----------

    /** Превращает номер темы или ссылку в полный адрес. */
    private fun buildUrl(input: String): String {
        val s = input.trim()
        return when {
            s.startsWith("http://", ignoreCase = true) || s.startsWith("https://", ignoreCase = true) -> s
            s.startsWith("//") -> "https:$s"
            Regex("""^\d+([&#].*)?$""").matches(s) -> "$BASE_URL$s"
            s.contains('.') || s.contains('/') -> "https://$s"
            else -> "$BASE_URL$s"
        }
    }

    /** Один запрос. Бросает исключение, если что-то пошло не так. */
    private fun requestOnce(client: OkHttpClient, url: String, userAgent: String): Document {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7")
            .header("Upgrade-Insecure-Requests", "1")
            .header("Referer", "https://4pda.to/forum/")
            .header("Sec-Fetch-Dest", "document")
            .header("Sec-Fetch-Mode", "navigate")
            .header("Sec-Fetch-Site", "same-origin")
            .header("Sec-Fetch-User", "?1")
            .build()

        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw HttpStatusException(
                    response.code,
                    response.header("Retry-After")?.trim()?.toLongOrNull()
                )
            }

            val body = response.body ?: throw IOException("Пустой ответ сервера")
            val charset = body.contentType()?.charset()?.name()
            val bytes = response.peekBody(MAX_BODY_BYTES).bytes()
            val doc = Jsoup.parse(ByteArrayInputStream(bytes), charset, response.request.url.toString())

            if (looksLikeBotCheck(doc)) throw BotCheckException()
            doc
        }
    }

    private fun looksLikeBotCheck(doc: Document): Boolean {
        val title = doc.title().lowercase()
        return listOf(
            "just a moment", "attention required", "ddos-guard", "checking your browser",
            "проверка браузера", "проверяем браузер"
        ).any { title.contains(it) }
    }

    /**
     * Скачивает страницу с повторами:
     *  - сетевая ошибка (нет адреса, обрыв, TLS) -> следующая попытка идёт другим способом
     *    (системный DNS <-> DNS-over-HTTPS);
     *  - 403/429/5xx -> пауза (по Retry-After, если сайт её указал) и другой вид User-Agent;
     *  - «проверка браузера» -> другой способ и другой User-Agent;
     *  - 404/410 -> тема недоступна, дальше не пробуем.
     * Ошибка одной ссылки не мешает остальным ссылкам предпринимать полные попытки загрузки.
     */
    private fun downloadDocument(url: String): Document {
        val agents = userAgents()
        var useDoh = false
        var agentIndex = 0
        var lastError: Exception? = null

        for (attempt in 1..MAX_ATTEMPTS) {
            var pauseMs = RETRY_DELAY_MS * attempt
            try {
                val doc = requestOnce(
                    if (useDoh) dohClient else systemClient,
                    url,
                    agents[agentIndex % agents.size]
                )
                if (attempt > 1) {
                    AppLog.i(TAG, "Открылось с попытки $attempt (${if (useDoh) "DoH" else "системный DNS"}): $url")
                }
                return doc
            } catch (e: HttpStatusException) {
                lastError = e
                AppLog.w(TAG, "Попытка $attempt/$MAX_ATTEMPTS: сервер ответил ${e.code} ($url)")
                if (e.code == 404 || e.code == 410) throw e
                agentIndex++
                e.retryAfterSeconds?.let { pauseMs = it.coerceIn(1L, 10L) * 1000L }
            } catch (e: IOException) {
                lastError = e
                AppLog.w(
                    TAG,
                    "Попытка $attempt/$MAX_ATTEMPTS (${if (useDoh) "DoH" else "системный DNS"}): " +
                        "${e.javaClass.simpleName}: ${e.message}"
                )
                useDoh = !useDoh
                if (e is BotCheckException) agentIndex++
            }
            if (attempt < MAX_ATTEMPTS) Thread.sleep(pauseMs.coerceAtMost(MAX_PAUSE_MS))
        }

        throw lastError ?: IOException("Неизвестная ошибка сети")
    }

    fun fetchAppData(topicIdOrUrl: String): AppData {
        val url = buildUrl(topicIdOrUrl)

        cache[url]?.let { (time, data) ->
            if (System.currentTimeMillis() - time < CACHE_TTL_MS) return data
        }

        return try {
            val doc = downloadDocument(url)
            val data = AppData(
                version = parseVersion(doc),
                name = parseAppNameFromDoc(doc),
                packageName = parsePackageNameFromDoc(doc),
                isError = false
            )
            if (data.version == null) {
                AppLog.w(TAG, "Страница загружена, но версия на ней не найдена (возможно, изменилась вёрстка сайта): $url")
            } else {
                AppLog.i(TAG, "OK: $url, версия на сайте: ${data.version}")
            }
            cache[url] = System.currentTimeMillis() to data
            data
        } catch (e: Exception) {
            AppLog.e(TAG, "Не удалось загрузить $url: ${e.javaClass.simpleName}: ${e.message}", e)
            AppData(null, null, null, isError = true)
        }
    }

    // ---------- Разбор страницы и сравнение версий (без изменений) ----------

    private fun parseVersion(doc: Document): String? {
        val title = doc.title()
        val versionRegex = Regex("""(\d+[a-zA-Z0-9\.\-]*[a-zA-Z0-9])""")
        
        var version = Regex("""(?:v|Версия)\s*(\d+[a-zA-Z0-9\.\-]*[a-zA-Z0-9])""", RegexOption.IGNORE_CASE).find(title)?.groupValues?.get(1)

        if (version == null) {
            val bodyText = doc.select("div.post_body").first()?.text() ?: ""
            val versionLabelRegex = Regex("""Версия:\s*(\d+[a-zA-Z0-9\.\-]*[a-zA-Z0-9])""", RegexOption.IGNORE_CASE)
            version = versionLabelRegex.find(bodyText)?.groupValues?.get(1)
        }
        
        if (version == null) {
            val firstPostBody = doc.select("div.post_body").first()?.text()?.take(400) ?: ""
            version = versionRegex.find(firstPostBody)?.groupValues?.get(1)
        }
        
        return version
    }

    private fun parseAppNameFromDoc(doc: Document): String? {
        val title = doc.title()
        return title.substringBefore("|").substringBefore("-").substringBefore("(").trim()
    }

    private fun parsePackageNameFromDoc(doc: Document): String? {
        val playLink = doc.select("a[href*=/store/apps/details?id=]").first()?.attr("href")
        if (playLink != null) {
            return playLink.substringAfter("details?id=").substringBefore("&")
        }
        
        val bodyText = doc.select("div.post_body").first()?.text() ?: ""
        val packageContextRegex = Regex("""(?:package|пакет|id)\s*[:=]?\s*([a-z][a-z0-9_]*\.[a-z0-9_]+(\.[a-z0-9_]+)+)""", RegexOption.IGNORE_CASE)
        return packageContextRegex.find(bodyText)?.groupValues?.get(1)
    }

    // Для совместимости, если где-то вызывается отдельно (хотя лучше использовать fetchAppData)
    fun parseVersionFromTopic(topicIdOrUrl: String): String? = fetchAppData(topicIdOrUrl).version
    fun parseAppName(topicIdOrUrl: String): String? = fetchAppData(topicIdOrUrl).name
    fun parsePackageName(topicIdOrUrl: String): String? = fetchAppData(topicIdOrUrl).packageName

    fun isUpdateAvailable(siteVersion: String?, installedVersion: String?): Boolean {
        if (siteVersion == null || installedVersion == null) return false
        
        val vSite = normalizeVersion(siteVersion)
        val vInstalled = normalizeVersion(installedVersion)
        
        if (vSite == vInstalled) return false
        
        return compareVersions(vSite, vInstalled) > 0
    }

    private fun normalizeVersion(v: String): String {
        return v.lowercase()
            .replace(Regex("^v"), "")
            .replace(Regex("^version"), "")
            .replace(Regex("^версия"), "")
            .trim()
            .filter { it.isDigit() || it == '.' || it == '-' || it.isLetter() }
    }

    private fun compareVersions(v1: String, v2: String): Int {
        val parts1 = v1.split('.', '-').filter { it.isNotEmpty() }
        val parts2 = v2.split('.', '-').filter { it.isNotEmpty() }
        
        val maxLength = maxOf(parts1.size, parts2.size)
        
        for (i in 0 until maxLength) {
            val p1Str = parts1.getOrNull(i) ?: "0"
            val p2Str = parts2.getOrNull(i) ?: "0"
            
            val p1 = p1Str.toIntOrNull()
            val p2 = p2Str.toIntOrNull()
            
            if (p1 != null && p2 != null) {
                if (p1 > p2) return 1
                if (p1 < p2) return -1
            } else {
                val res = p1Str.compareTo(p2Str)
                if (res != 0) return res
            }
        }
        return 0
    }
}
