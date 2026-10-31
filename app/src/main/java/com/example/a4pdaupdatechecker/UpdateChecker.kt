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
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

data class AppData(
    val version: String?,
    val name: String?,
    val packageName: String?,
    val isError: Boolean = false
)

/**
 * СЕТЕВАЯ ЧАСТЬ И ПРОВЕРКА ОБНОВЛЕНИЙ.
 *
 * Оптимизации:
 *  1. Запоминание рабочего способа (адаптивная стратегия):
 *     Если системный DNS заблокирован или DoH сработал, этот способ запоминается
 *     глобально и применяется для ВСЕХ остальных ссылок на 1-й же попытке.
 *  2. Быстрые тайм-ауты и быстрый переход между способами без долгих пауз.
 *  3. Ротация DoH-провайдеров с запоминанием ответившего сервера.
 *  4. Обратная совместимость с более старыми версиями Android (включая TLS 1.2 и сертификат ISRG Root X1).
 */
object UpdateChecker {

    private const val TAG = "UpdateChecker"
    private const val BASE_URL = "https://4pda.to/forum/index.php?showtopic="

    private const val MAX_BODY_BYTES = 3L * 1024 * 1024
    private const val CACHE_TTL_MS = 15_000L

    // Глобальное состояние успешно сработавшей стратегии.
    @Volatile
    private var preferredUseDoh: Boolean = false

    @Volatile
    private var preferredAgentIndex: Int = 0

    @Volatile
    private var preferredDohProviderIndex: Int = 0

    // ISRG Root X1 (Let's Encrypt) сертификат для совместимости со старыми версиями Android (Android 6-7.1)
    private const val ISRG_ROOT_X1_PEM =
        "-----BEGIN CERTIFICATE-----\n" +
        "MIIFazCCA1OgAwIBAgIRAIIQz7DSQONZRGPgu2OCiwAwDQYJKoZIhvcNAQELBQAw\n" +
        "TzELMAkGA1UEBhMCVVMxKTAnBgNVBAoTIEludGVybmV0IFNlY3VyaXR5IFJlc2Vh\n" +
        "cmNoIEdyb3VwMRUwEwYDVQQDEwxJU1JHIFJvb3QgWDEwHhcNMTUwNjA0MTEwNDM4\n" +
        "WhcNMzUwNjA0MTEwNDM4WjBPMQswCQYDVQQGEwJVUzEpMCcGA1UEChMgSW50ZXJu\n" +
        "ZXQgU2VjdXJpdHkgUmVzZWFyY2ggR3JvdXAxFTATBgNVBAMTDElTUkcgUm9vdCBY\n" +
        "MTCCAiIwDQYJKoZIhvcNAQEBBQADggIPADCCAgoCggIBAK3oJHP0FDfzm54rVygc\n" +
        "h77ct984kIxuPOZXoHj3dcKi/vVqbvYATyjb3miGbESTtrFj/RQSa78f0uoxmyF+\n" +
        "0TM8ukj13Xnfs7j/EvEhmkvBioZxaUpmZmyPfjxwv60pIgbz5MDmgK7iS4+3mX6U\n" +
        "A5/TR5d8S5JlCdGwjf4lUr8wq27N6q0EnsavLjDeSayruBpQuBmG41ZxdEBmnhy8\n" +
        "szS5345zCbqxnJBPZ40tbJ29SotlSoUmAMu62qJQUqwJEopo44QqmBN5vdY60bQk\n" +
        "1jZWG5tKn28xwTx8bkvezcgSNdHaoEiW9Du32YTfjqTPKhkUeyBlsf+/BRio5MtN\n" +
        "Hawt61TKAlSu59g9x81BiLL5C9NQ5SkIEAc9sUrxAfbNyWFsG4h50S458KSn+fa7\n" +
        "1e56qBc3gHKgZXK5M10fyzy5dF3aUEuDnUn2843nRGlZvZ7mW4TWjekAcny28pnU\n" +
        "r0+/P3bjirqnAOmGitw501t308w0fmFbF50OwKWgjPoHyJqDzccxn53CE5R9DAkW\n" +
        "V6+tquxxWBOFYsSy4CoVu3NWCpvVWm004m8ZDs7WBLHxV8++cTfShLqCHh47Pm5g\n" +
        "Bi8/HEN/x3zgkmw1vTVCxA==\n" +
        "-----END CERTIFICATE-----"

    private fun createCompatibilityTrustManager(): X509TrustManager {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(null as KeyStore?)
        val defaultTm = tmf.trustManagers.filterIsInstance<X509TrustManager>().first()

        return try {
            val certFactory = CertificateFactory.getInstance("X.509")
            val isrgCert = certFactory.generateCertificate(
                ByteArrayInputStream(ISRG_ROOT_X1_PEM.toByteArray(Charsets.US_ASCII))
            ) as X509Certificate

            val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, null)
                setCertificateEntry("isrg_root_x1", isrgCert)
            }
            val customTmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            customTmf.init(keyStore)
            val customTm = customTmf.trustManagers.filterIsInstance<X509TrustManager>().first()

            object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                    defaultTm.checkClientTrusted(chain, authType)
                }

                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                    try {
                        defaultTm.checkServerTrusted(chain, authType)
                    } catch (e: CertificateException) {
                        try {
                            customTm.checkServerTrusted(chain, authType)
                        } catch (e2: Exception) {
                            throw e
                        }
                    }
                }

                override fun getAcceptedIssuers(): Array<X509Certificate> {
                    return defaultTm.acceptedIssuers + customTm.acceptedIssuers
                }
            }
        } catch (e: Exception) {
            defaultTm
        }
    }

    private fun configureCompatibilityTls(builder: OkHttpClient.Builder) {
        try {
            val trustManager = createCompatibilityTrustManager()
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, arrayOf<TrustManager>(trustManager), null)
            builder.sslSocketFactory(sslContext.socketFactory, trustManager)
        } catch (e: Exception) {
            AppLog.w(TAG, "Не удалось настроить compatibility TLS: ${e.message}")
        }
    }

    // ---------- User-Agent ----------

    private const val CHROME_BASE_MAJOR = 140
    private const val CHROME_BASE_TIME_MS = 1_756_771_200_000L
    private const val CHROME_CYCLE_MS = 28L * 24 * 60 * 60 * 1000

    private fun chromeMajor(): Int {
        val cyclesPassed = ((System.currentTimeMillis() - CHROME_BASE_TIME_MS) / CHROME_CYCLE_MS).toInt()
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

    private class HttpStatusException(val code: Int, val retryAfterSeconds: Long? = null) :
        IOException("HTTP $code")

    private class BotCheckException :
        IOException("Сайт вернул страницу проверки браузера (защита от ботов)")

    // ---------- Клиенты ----------

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

    private val systemClient: OkHttpClient by lazy {
        val builder = OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(16, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .cookieJar(cookieJar)

        configureCompatibilityTls(builder)
        builder.build()
    }

    private val dohClient: OkHttpClient by lazy {
        systemClient.newBuilder()
            .dns(buildDohDns())
            .build()
    }

    private fun buildDohDns(): Dns {
        val bootstrap = OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .apply { configureCompatibilityTls(this) }
            .build()

        fun provider(url: String, vararg ips: String): DnsOverHttps =
            DnsOverHttps.Builder()
                .client(bootstrap)
                .url(url.toHttpUrl())
                .bootstrapDnsHosts(ips.map { InetAddress.getByName(it) })
                .includeIPv6(false)
                .build()

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
                val list = providers
                val startIndex = preferredDohProviderIndex.coerceIn(0, list.size - 1)

                // 1. Сначала пробуем последний успешно сработавший DoH-сервер
                try {
                    val res = list[startIndex].lookup(hostname)
                    if (res.isNotEmpty()) return res
                } catch (ignored: Exception) {
                }

                // 2. Если не сработал, перебираем остальные и запоминаем первый рабочий
                var last: Exception? = null
                for (i in list.indices) {
                    if (i == startIndex) continue
                    try {
                        val result = list[i].lookup(hostname)
                        if (result.isNotEmpty()) {
                            preferredDohProviderIndex = i
                            return result
                        }
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
     * Скачивает страницу с адаптивной стратегией:
     * - Сразу использует доказанно рабочий способ (DNS и User-Agent).
     * - При сбое быстро пробует альтернативу (DoH <-> System DNS) без долгих пауз.
     * - При успехе альтернативы запоминает её для всех последующих ссылок.
     */
    private fun downloadDocument(url: String): Document {
        val agents = userAgents()
        var currentUseDoh = preferredUseDoh
        var currentAgentIndex = preferredAgentIndex
        var lastError: Exception? = null

        val maxAttempts = 4

        for (attempt in 1..maxAttempts) {
            try {
                val client = if (currentUseDoh) dohClient else systemClient
                val ua = agents[currentAgentIndex % agents.size]
                val doc = requestOnce(client, url, ua)

                // Успех! Запоминаем эту конфигурацию как рабочую для всех ссылок
                if (preferredUseDoh != currentUseDoh) {
                    preferredUseDoh = currentUseDoh
                    AppLog.i(TAG, "Приоритетный DNS обновлен на: ${if (currentUseDoh) "DoH" else "Системный DNS"}")
                }
                if (preferredAgentIndex != currentAgentIndex) {
                    preferredAgentIndex = currentAgentIndex
                }

                if (attempt > 1) {
                    AppLog.i(TAG, "Открылось с попытки $attempt (${if (currentUseDoh) "DoH" else "системный DNS"}): $url")
                }
                return doc
            } catch (e: HttpStatusException) {
                lastError = e
                AppLog.w(TAG, "Попытка $attempt/$maxAttempts: сервер ответил ${e.code} ($url)")
                if (e.code == 404 || e.code == 410) throw e
                currentAgentIndex++
                val pauseMs = (e.retryAfterSeconds?.coerceIn(1L, 5L) ?: 1L) * 1000L
                if (attempt < maxAttempts) Thread.sleep(pauseMs)
            } catch (e: IOException) {
                lastError = e
                AppLog.w(
                    TAG,
                    "Попытка $attempt/$maxAttempts (${if (currentUseDoh) "DoH" else "системный DNS"}): " +
                        "${e.javaClass.simpleName}: ${e.message}"
                )
                if (e is BotCheckException) {
                    currentAgentIndex++
                    currentUseDoh = !currentUseDoh
                } else {
                    currentUseDoh = !currentUseDoh
                }
                // Минимальная пауза только перед 3-й и 4-й попытками
                if (attempt >= 2 && attempt < maxAttempts) {
                    Thread.sleep(400)
                }
            }
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
                AppLog.w(TAG, "Страница загружена, но версия на ней не найдена: $url")
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

    // ---------- Разбор страницы и сравнение версий ----------

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
            .replace(Regex("""\([^)]*\)"""), "") // Удаляем текст в скобках, например (stable)
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
