package com.example.a4pdaupdatechecker

import org.jsoup.Jsoup
import org.jsoup.nodes.Document

data class AppData(
    val version: String?,
    val name: String?,
    val packageName: String?
)

object UpdateChecker {

    private const val BASE_URL = "https://4pda.to/forum/index.php?showtopic="

    fun fetchAppData(topicIdOrUrl: String): AppData {
        return try {
            val url = if (topicIdOrUrl.startsWith("http")) topicIdOrUrl
            else "$BASE_URL$topicIdOrUrl"

            val doc = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .timeout(10000)
                .get()

            AppData(
                version = parseVersion(doc),
                name = parseAppNameFromDoc(doc),
                packageName = parsePackageNameFromDoc(doc)
            )
        } catch (e: Exception) {
            e.printStackTrace()
            AppData(null, null, null)
        }
    }

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
