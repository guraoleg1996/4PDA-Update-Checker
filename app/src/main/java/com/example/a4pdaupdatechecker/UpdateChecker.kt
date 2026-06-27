package com.example.a4pdaupdatechecker

import org.jsoup.Jsoup

object UpdateChecker {

    private const val BASE_URL = "https://4pda.to/forum/index.php?showtopic="

    fun parseVersionFromTopic(topicIdOrUrl: String): String? {
        return try {
            val url = if (topicIdOrUrl.startsWith("http")) topicIdOrUrl
            else "$BASE_URL$topicIdOrUrl"

            val doc = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .timeout(10000)
                .get()

            val title = doc.title()
            // Регулярное выражение для версии: цифры, точки, тире и латинские буквы.
            // Должно начинаться и заканчиваться цифрой или буквой.
            val versionRegex = Regex("""(\d+[a-zA-Z0-9\.\-]*[a-zA-Z0-9])""")
            
            // 1. Пытаемся найти в заголовке. Ищем "v1.2n" или "Версия 1.2n"
            var version = Regex("""(?:v|Версия)\s*(\d+[a-zA-Z0-9\.\-]*[a-zA-Z0-9])""", RegexOption.IGNORE_CASE).find(title)?.groupValues?.get(1)

            if (version == null) {
                // 2. Ищем в теле сообщения текст "Версия: 2.2n"
                val bodyText = doc.select("div.post_body").first()?.text() ?: ""
                val versionLabelRegex = Regex("""Версия:\s*(\d+[a-zA-Z0-9\.\-]*[a-zA-Z0-9])""", RegexOption.IGNORE_CASE)
                version = versionLabelRegex.find(bodyText)?.groupValues?.get(1)
            }
            
            if (version == null) {
                // 3. Просто ищем любое вхождение похожее на версию в начале первого поста
                val firstPostBody = doc.select("div.post_body").first()?.text()?.take(400) ?: ""
                version = versionRegex.find(firstPostBody)?.groupValues?.get(1)
            }
            
            version

        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun parseAppName(topicIdOrUrl: String): String? {
        return try {
            val url = if (topicIdOrUrl.startsWith("http")) topicIdOrUrl
            else "$BASE_URL$topicIdOrUrl"

            val doc = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .timeout(10000)
                .get()

            val title = doc.title()
            // Обычно заголовок на 4PDA выглядит так: "PipePipe | Просмотр и загрузка..." или "App Name - 4PDA"
            // Извлекаем текст до первого разделителя
            title.substringBefore("|").substringBefore("-").substringBefore("(").trim()
        } catch (e: Exception) {
            null
        }
    }

    fun parsePackageName(topicIdOrUrl: String): String? {
        return try {
            val url = if (topicIdOrUrl.startsWith("http")) topicIdOrUrl
            else "$BASE_URL$topicIdOrUrl"

            val doc = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .timeout(10000)
                .get()

            // Ищем ссылки на Google Play
            val playLink = doc.select("a[href*=/store/apps/details?id=]").first()?.attr("href")
            if (playLink != null) {
                return playLink.substringAfter("details?id=").substringBefore("&")
            }
            
            // Ищем в тексте сообщения что-то похожее на имя пакета
            val bodyText = doc.select("div.post_body").first()?.text() ?: ""
            // Ищем контекстно: "пакет: com.example", "package: com.example" и т.д.
            val packageContextRegex = Regex("""(?:package|пакет|id)\s*[:=]?\s*([a-z][a-z0-9_]*\.[a-z0-9_]+(\.[a-z0-9_]+)+)""", RegexOption.IGNORE_CASE)
            packageContextRegex.find(bodyText)?.groupValues?.get(1)

        } catch (e: Exception) {
            null
        }
    }

    fun isUpdateAvailable(siteVersion: String?, installedVersion: String?): Boolean {
        if (siteVersion == null || installedVersion == null) return false
        
        val vSite = normalizeVersion(siteVersion)
        val vInstalled = normalizeVersion(installedVersion)
        
        if (vSite == vInstalled) return false
        
        return compareVersions(vSite, vInstalled) > 0
    }

    private fun normalizeVersion(v: String): String {
        // Очищаем от мусора, сохраняем цифры, точки, тире и буквы (для 2.2n)
        return v.lowercase()
            .replace(Regex("^v"), "")
            .replace(Regex("^version"), "")
            .replace(Regex("^версия"), "")
            .trim()
            .filter { it.isDigit() || it == '.' || it == '-' || it.isLetter() }
    }

    private fun compareVersions(v1: String, v2: String): Int {
        // Разбиваем по точкам и тире
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
                // Если не числа, сравниваем как строки
                val res = p1Str.compareTo(p2Str)
                if (res != 0) return res
            }
        }
        return 0
    }
}