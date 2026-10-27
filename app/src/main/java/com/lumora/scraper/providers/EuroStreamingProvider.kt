// Copyright (c) 2026 Lumora (LumoraPlayer) - original work, noncommercial licence.
package com.lumora.scraper.providers

import android.util.Log
import com.lumora.scraper.adapters.AppAdapter
import com.lumora.scraper.extractors.Extractor
import com.lumora.scraper.models.Category
import com.lumora.scraper.models.Episode
import com.lumora.scraper.models.Genre
import com.lumora.scraper.models.Movie
import com.lumora.scraper.models.People
import com.lumora.scraper.models.Season
import com.lumora.scraper.models.TvShow
import com.lumora.scraper.models.Video
import com.lumora.scraper.utils.ArtworkRequestHeaders
import com.lumora.scraper.utils.DnsResolver
import com.lumora.scraper.utils.NetworkClient
import com.lumora.scraper.utils.TmdbUtils
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

import android.content.Context
import android.webkit.CookieManager
import com.lumora.scraper.ScraperApp
import com.lumora.scraper.utils.WebViewResolver
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URL

object EuroStreamingProvider : Provider {

    override val name = "euroStreaming"
    override var baseUrl = "https://eurostream.fun"
    override val logo: String
        get() = artworkUrl("$baseUrl/wp-content/uploads/2018/09/favicon18.jpg")
            ?: "$baseUrl/wp-content/uploads/2018/09/favicon18.jpg"
    override val language = "it"

    private const val TAG = "euroStreamingProvider"
    private val USER_AGENT: String get() = NetworkClient.USER_AGENT

    private val currentHost: String
        get() = runCatching { URL(baseUrl).host }.getOrDefault("")

    private val wpJson: String
        get() = "${baseUrl.trimEnd('/')}/wp-json/wp/v2"

    private val RECENT_POSTS_UL_REGEX = Regex("""<ul class="recent-posts">(.*?)</ul>""", RegexOption.DOT_MATCHES_ALL)
    private val RECENT_POST_ITEM_REGEX = Regex(
        """<li\s+id="post-(\d+)"[^>]*>.*?<div class="post-thumb">.*?<a\s+href="([^"]+)"\s+title="([^"]+)".*?<img\s+src="([^"]+)".*?</li>""",
        RegexOption.DOT_MATCHES_ALL
    )
    private val IMG_SRC_REGEX = Regex("""<img[^>]+src=["']([^"']+)["']""")
    private val PLOT_REGEX = Regex("""<p style="margin-bottom: 2px;">(.*?)(?:</center>|<div class="su-spoiler)""", RegexOption.DOT_MATCHES_ALL)
    private val PLOT_CLEANUP_REGEX = Regex("""(?i)(?:[-–—]\s*)?\b(?:continua\s+a\s+leggere|leggi\s+tutto)\b[.\s]*""")
    private val SPOILER_REGEX = Regex(
        """<div class="su-spoiler-title"[^>]*>(.*?)</div>\s*<div class="su-spoiler-content[^"]*"[^>]*>(.*?)</div>""",
        RegexOption.DOT_MATCHES_ALL
    )
    private val SEASON_NUM_REGEX = Regex("""STAGIONE\s+(\d+)""", RegexOption.IGNORE_CASE)
    private val EPISODE_LINE_REGEX = Regex("""(?:<strong>)?(\d+(?:&#215;|x|×)\d+)(?:</strong>)?\s*(.*?)(?:–|-|<a)""")
    private val EPISODE_CODE_REGEX = Regex("""\d+(?:&#215;|x|×)\d+""")
    private val GENERIC_EPISODE_TITLE_REGEX = Regex("""^(?i)episod(?:io|e)\s*\d+$""")
    private val LINK_REGEX = Regex("""<a\s+href=["']([^"']+)["'][^>]*>([^<]+)</a>""")
    private val SAFEGO_URL_REGEX = Regex("""https?://safego\.cc/safe\.php\?url=[^"'\s<>]+""")
    private val PROCEED_VIDEO_REGEX = Regex("""<a\s+href=["']([^"']+)["'][^>]*>\s*<button[^>]*>\s*Proceed to video""", RegexOption.IGNORE_CASE)

    private fun extractPoster(embedded: JSONObject?, content: String): String? {
        val featuredImg = embedded?.optJSONArray("wp:featuredmedia")?.optJSONObject(0)?.optString("source_url")
        return featuredImg?.takeIf { it.isNotBlank() }
            ?: IMG_SRC_REGEX.find(content)?.groupValues?.getOrNull(1)
    }

    private val providerMutex = Mutex()
    private var webViewResolver: WebViewResolver? = null
    @Volatile
    private var clearanceCookieHeader: String? = null

    fun init(context: Context) {
        webViewResolver = WebViewResolver(context)
        syncClearanceCookieState()
    }

    private fun getResolver(): WebViewResolver {
        return webViewResolver ?: WebViewResolver(ScraperApp.instance).also {
            webViewResolver = it
        }
    }

    private class ChallengeRequiredException(
        message: String,
        val rejectedClearance: String?,
    ) : IllegalStateException(message)

    private fun clearanceToken(cookieHeader: String?): String? {
        return cookieHeader
            ?.split(';')
            ?.map(String::trim)
            ?.firstOrNull { it.startsWith("cf_clearance=", ignoreCase = true) }
            ?.substringAfter('=')
            ?.takeIf(String::isNotBlank)
    }

    private val knownHosts = java.util.concurrent.ConcurrentHashMap.newKeySet<String>().apply {
        runCatching { URL(baseUrl).host.lowercase() }.getOrNull()?.takeIf { it.isNotBlank() }?.let { add(it) }
    }

    private fun registerHost(host: String?) {
        val h = host?.lowercase()?.trim() ?: return
        if (h.isNotBlank() && !h.contains("cloudflare") && !h.contains("google") && !h.contains("doubleclick")) {
            knownHosts.add(h)
        }
    }

    private fun updateBaseUrl(newBaseUrl: String) {
        val uri = runCatching { URL(newBaseUrl) }.getOrNull() ?: return
        val host = uri.host.lowercase()
        if (host.isNotBlank() && !host.contains("cloudflare")) {
            registerHost(host)
            val cleanBase = "${uri.protocol}://$host"
            if (!baseUrl.equals(cleanBase, ignoreCase = true)) {
                Log.i(TAG, "Base URL dynamically redirected: $baseUrl -> $cleanBase")
                baseUrl = cleanBase
            }
        }
    }

    private fun clearStaleClearanceCookie() {
        val cookieManager = CookieManager.getInstance()
        val hosts = (knownHosts + listOfNotNull(currentHost)).map { it.lowercase() }.filter { it.isNotBlank() }.distinct()

        hosts.forEach { host ->
            cookieManager.setCookie("https://$host/", "cf_clearance=; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/; Domain=.$host; Max-Age=0")
            cookieManager.setCookie("https://$host/", "cf_clearance=; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/; Max-Age=0")
        }
        cookieManager.flush()
        clearanceCookieHeader = null
    }

    private fun syncClearanceCookieState(targetUrl: String? = null): String? {
        val cookieManager = CookieManager.getInstance()
        val hosts = mutableListOf<String>()
        val curr = currentHost
        if (curr.isNotBlank()) hosts.add(curr)
        targetUrl?.let { runCatching { URL(it).host }.getOrNull() }?.let { if (it.isNotBlank()) hosts.add(it) }
        hosts.addAll(knownHosts)

        val distinctHosts = hosts.map { it.lowercase() }.filter { it.isNotBlank() }.distinct()
        distinctHosts.forEach { registerHost(it) }

        val candidates = distinctHosts.flatMap { host ->
            listOf(
                "https://$host",
                "https://$host/",
                "https://.$host",
                "https://.$host/"
            )
        }.distinct()

        val liveCookieHeaders = candidates.mapNotNull { candidate ->
            cookieManager.getCookie(candidate)?.trim()?.takeIf(String::isNotBlank)
        }.distinct()
        val liveCookieHeader = liveCookieHeaders.firstOrNull { header ->
            !clearanceToken(header).isNullOrBlank()
        } ?: liveCookieHeaders.firstOrNull()

        if (!liveCookieHeader.isNullOrBlank()) {
            clearanceCookieHeader = liveCookieHeader
            val token = clearanceToken(liveCookieHeader)
            if (!token.isNullOrBlank()) {
                distinctHosts.forEach { host ->
                    cookieManager.setCookie("https://$host/", "cf_clearance=$token; Path=/; Domain=.$host")
                    cookieManager.setCookie("https://$host/", "cf_clearance=$token; Path=/")
                }
            }
        }
        return clearanceCookieHeader
    }

    private fun currentClearanceCookie(): String? = syncClearanceCookieState()

    fun hasCurrentClearanceCookie(): Boolean {
        return !clearanceToken(syncClearanceCookieState()).isNullOrBlank()
    }

    private fun promoteClearanceCookieHeader(cookieHeader: String?) {
        val normalized = cookieHeader?.trim()?.takeIf(String::isNotBlank) ?: return
        if (normalized == clearanceCookieHeader) return
        clearanceCookieHeader = normalized
    }

    private fun requiresClearance(html: String): Boolean {
        return html.contains("cf-browser-verification", ignoreCase = true) ||
                html.contains("Just a moment...", ignoreCase = true) ||
                html.contains("Checking your browser", ignoreCase = true) ||
                html.contains("window._cf_chl_opt", ignoreCase = true) ||
                html.contains("challenge-form", ignoreCase = true) ||
                html.contains("challenges.cloudflare.com", ignoreCase = true)
    }

    private fun hasUsableSiteContent(html: String, currentUrl: String): Boolean {
        if (html.length < 500) return false
        if (currentUrl.contains("/wp-json/", ignoreCase = true)) {
            val trimmed = html.trim()
            return trimmed.startsWith("{") || trimmed.startsWith("[")
        }
        return html.contains("recent-posts", ignoreCase = true) ||
                html.contains("su-spoiler", ignoreCase = true) ||
                html.contains("wp-content", ignoreCase = true) ||
                html.contains("post-", ignoreCase = true)
    }

    private fun pageHeaders(referer: String): Map<String, String> = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "it-IT,it;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to referer
    )

    private fun artworkUrl(image: String?): String? {
        val raw = image?.takeIf { it.isNotBlank() } ?: return null
        val normalized = when {
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("http", ignoreCase = true) -> raw
            raw.startsWith("/") -> "$baseUrl$raw"
            else -> "$baseUrl/$raw"
        }

        return ArtworkRequestHeaders.withHeaders(
            url = normalized,
            referer = "$baseUrl/",
            origin = baseUrl,
            userAgent = NetworkClient.USER_AGENT,
            accept = "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8",
            cookie = currentClearanceCookie()
        )
    }

    private fun rebase(url: String): String {
        return runCatching {
            val u = URL(url)
            val b = URL(baseUrl)
            val uHost = u.host.lowercase()
            val bHost = b.host.lowercase()
            if (knownHosts.contains(uHost) && uHost != bHost) {
                "${b.protocol}://${b.host}${u.file}"
            } else url
        }.getOrDefault(url)
    }

    private suspend fun solveCloudflareChallenge(
        url: String,
        referer: String,
        rejectedClearance: String?,
    ): String? {
        val result = providerMutex.withLock {
            val currentClearance = clearanceToken(currentClearanceCookie())
            if (!currentClearance.isNullOrBlank() && currentClearance != rejectedClearance) {
                Log.d(TAG, "Another request already renewed Cloudflare clearance; skipping WebView")
                return@withLock null
            }

            clearStaleClearanceCookie()
            val challengeUrl = if (url.contains("/wp-json/", ignoreCase = true)) baseUrl else rebase(url)
            Log.d(TAG, "Solving confirmed Cloudflare challenge in WebView -> url=$challengeUrl")

            getResolver().getResult(
                url = challengeUrl,
                headers = pageHeaders(referer),
                completion = { currentUrl, html, cookies ->
                    val newClearance = clearanceToken(cookies)
                    val isNewClearance = !newClearance.isNullOrBlank() && newClearance != rejectedClearance
                    val hasUsableContent = hasUsableSiteContent(html, currentUrl)
                    val isStillOnChallenge = html.contains("cf-browser-verification", ignoreCase = true) ||
                            html.contains("challenge-running", ignoreCase = true) ||
                            html.contains("challenges.cloudflare.com", ignoreCase = true) ||
                            html.contains("Just a moment...", ignoreCase = true) ||
                            currentUrl.contains("/cdn-cgi/", ignoreCase = true)

                    if (isNewClearance) promoteClearanceCookieHeader(cookies)
                    Log.d(
                        TAG,
                        "WebView challenge poll -> url=$currentUrl isNewClearance=$isNewClearance " +
                                "content=$hasUsableContent challenge=$isStillOnChallenge"
                    )
                    !isStillOnChallenge && (hasUsableContent || isNewClearance)
                },
                shouldAllowNavigation = { targetUrl, isMainFrame ->
                    runCatching {
                        val target = URL(targetUrl)
                        val targetHost = target.host.lowercase()
                        val initialHost = currentHost.lowercase()
                        val isKnown = knownHosts.contains(targetHost) || targetHost == initialHost || targetHost.endsWith(".$initialHost")
                        val isAllowed = isKnown ||
                                target.path.contains("/cdn-cgi/", ignoreCase = true) ||
                                targetHost.contains("cloudflare") ||
                                (isMainFrame && !targetHost.contains("google") && !targetHost.contains("doubleclick"))

                        if (isAllowed && isMainFrame && !targetHost.contains("cloudflare")) {
                            registerHost(targetHost)
                        }
                        isAllowed
                    }.getOrDefault(true)
                },
            )
        }

        if (result != null) {
            val clearanceInResult = clearanceToken(CookieManager.getInstance().getCookie(url))
            if (!clearanceInResult.isNullOrBlank()) {
                promoteClearanceCookieHeader(CookieManager.getInstance().getCookie(url))
            }
            CookieManager.getInstance().flush()
            val pageUrl = result.finalUrl ?: url
            runCatching {
                val finalUri = URL(pageUrl)
                val finalHost = finalUri.host
                if (finalHost.isNotBlank() && !finalHost.contains("cloudflare")) {
                    updateBaseUrl("${finalUri.protocol}://$finalHost")
                }
            }
            syncClearanceCookieState(pageUrl)
            if (result.html.isNotBlank() && hasUsableSiteContent(result.html, pageUrl) && !pageUrl.contains("/wp-json/")) {
                return result.html
            }
        }

        val resolvedClearance = clearanceToken(currentClearanceCookie())
        if (resolvedClearance.isNullOrBlank() || resolvedClearance == rejectedClearance) {
            throw ChallengeRequiredException(
                message = "Cloudflare clearance was not obtained for $url",
                rejectedClearance = rejectedClearance,
            )
        }
        return null
    }

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .dns(DnsResolver.doh)
            .cookieJar(NetworkClient.cookieJar)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private val noRedirectClient by lazy {
        OkHttpClient.Builder()
            .dns(DnsResolver.doh)
            .cookieJar(NetworkClient.cookieJar)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    private fun fetchDirect(rawUrl: String, referer: String? = null): String {
        val url = rebase(rawUrl)
        val reqBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json, text/html, */*")
            .header("Accept-Language", "it-IT,it;q=0.9,en-US;q=0.8,en;q=0.7")

        val effectiveReferer = referer ?: baseUrl
        reqBuilder.header("Referer", effectiveReferer)
        syncClearanceCookieState(url)?.takeIf { it.isNotBlank() }?.let {
            reqBuilder.header("Cookie", it)
        }

        httpClient.newCall(reqBuilder.build()).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            val finalUrl = resp.request.url.toString()
            val finalHost = resp.request.url.host
            val initialHost = currentHost

            if (initialHost.isNotBlank() && !finalHost.equals(initialHost, ignoreCase = true) && !finalHost.contains("cloudflare")) {
                updateBaseUrl("${resp.request.url.scheme}://$finalHost")
            }

            if (resp.isSuccessful && !requiresClearance(body) && !finalUrl.contains("/cdn-cgi/", ignoreCase = true)) {
                return body
            }

            if (requiresClearance(body) || finalUrl.contains("/cdn-cgi/", ignoreCase = true) || resp.code in listOf(403, 503)) {
                Log.w(TAG, "Cloudflare challenge detected -> code=${resp.code} url=$url finalUrl=$finalUrl")
                throw ChallengeRequiredException(
                    message = "euroStreaming Cloudflare challenge detected for $url",
                    rejectedClearance = clearanceToken(currentClearanceCookie()),
                )
            }

            if (!resp.isSuccessful) {
                throw Exception("HTTP ${resp.code} for $url")
            }

            return body
        }
    }

    private suspend fun httpGet(url: String, referer: String? = null): String {
        syncClearanceCookieState(url)
        val target = rebase(url)
        return try {
            fetchDirect(target, referer)
        } catch (challenge: ChallengeRequiredException) {
            val resolvedHtml = solveCloudflareChallenge(target, referer ?: baseUrl, challenge.rejectedClearance)
            resolvedHtml ?: fetchDirect(rebase(target), referer)
        }
    }

    override suspend fun getHome(): List<Category> {
        val categories = mutableListOf<Category>()

        // 1. Latest updates from homepage
        try {
            val html = httpGet(baseUrl)
            val ulMatch = RECENT_POSTS_UL_REGEX.find(html)
            if (ulMatch != null) {
                val shows = RECENT_POST_ITEM_REGEX.findAll(ulMatch.groupValues[1]).mapNotNull { m ->
                    val id = m.groupValues[1]
                    val title = org.jsoup.parser.Parser.unescapeEntities(m.groupValues[3].trim(), false)
                    val poster = m.groupValues[4]
                    TvShow(
                        id = id,
                        title = title,
                        poster = artworkUrl(poster)
                    )
                }.toList()

                if (shows.isNotEmpty()) {
                    categories.add(Category(name = "Ultimi Aggiornamenti", list = shows))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching homepage: ${e.message}")
        }

        // 2. TV Show archive (first page)
        try {
            val archiveShows = getTvShows(page = 1)
            if (archiveShows.isNotEmpty()) {
                categories.add(Category(name = "SERIE TV ARCHIVIO", list = archiveShows))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching archive for home: ${e.message}")
        }

        return categories
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isBlank()) return emptyList()
        val encoded = URLEncoder.encode(query, "UTF-8")
        // Note: placing per_page and orderby=relevance before search avoids Cloudflare WAF signature
        val url = "$wpJson/posts?page=$page&per_page=18&orderby=relevance&search=$encoded&_embed"

        return try {
            val jsonStr = httpGet(url)
            val jsonArray = JSONArray(jsonStr)
            val results = mutableListOf<TvShow>()

            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val id = obj.optInt("id").toString()
                val rawTitle = obj.optJSONObject("title")?.optString("rendered").orEmpty()
                val title = org.jsoup.parser.Parser.unescapeEntities(rawTitle, false)
                val content = obj.optJSONObject("content")?.optString("rendered").orEmpty()
                val poster = extractPoster(obj.optJSONObject("_embedded"), content)

                results.add(
                    TvShow(
                        id = id,
                        title = title,
                        poster = artworkUrl(poster)
                    )
                )
            }
            results
        } catch (e: Exception) {
            Log.e(TAG, "Search error: ${e.message}")
            emptyList()
        }
    }

    override suspend fun getMovies(page: Int): List<Movie> = emptyList()

    override suspend fun getMovie(id: String): Movie = throw NotImplementedError("euroStreaming only supports TV Shows")

    override suspend fun getTvShows(page: Int): List<TvShow> {
        val url = "$wpJson/posts?categories=1253&page=$page&per_page=18&orderby=date&order=desc&_embed"
        return try {
            val jsonStr = httpGet(url)
            val jsonArray = JSONArray(jsonStr)
            val results = mutableListOf<TvShow>()

            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val id = obj.optInt("id").toString()
                val rawTitle = obj.optJSONObject("title")?.optString("rendered").orEmpty()
                val title = org.jsoup.parser.Parser.unescapeEntities(rawTitle, false)
                val content = obj.optJSONObject("content")?.optString("rendered").orEmpty()
                val poster = extractPoster(obj.optJSONObject("_embedded"), content)

                results.add(
                    TvShow(
                        id = id,
                        title = title,
                        poster = artworkUrl(poster)
                    )
                )
            }
            results
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching TV show archive: ${e.message}")
            emptyList()
        }
    }

    override suspend fun getTvShow(id: String): TvShow {
        val url = "$wpJson/posts/$id?_embed"
        val jsonStr = httpGet(url)
        val obj = JSONObject(jsonStr)

        val rawTitle = obj.optJSONObject("title")?.optString("rendered").orEmpty()
        val title = org.jsoup.parser.Parser.unescapeEntities(rawTitle, false).trim()
        val content = obj.optJSONObject("content")?.optString("rendered").orEmpty()
        val localPoster = extractPoster(obj.optJSONObject("_embedded"), content)

        val plotMatch = PLOT_REGEX.find(content)
        val rawPlot = plotMatch?.groupValues?.getOrNull(1).orEmpty()
        val localPlot = Jsoup.parse(rawPlot).text()
            .replace(PLOT_CLEANUP_REGEX, "")
            .trim()
            .takeIf { it.isNotBlank() }

        val tmdbShow = try {
            TmdbUtils.getTvShow(title, language = language)
        } catch (_: Exception) {
            null
        }

        // Parse seasons (su-spoiler shortcode)
        val spoilers = SPOILER_REGEX.findAll(content).toList()

        val seasons = mutableListOf<Season>()
        for ((idx, match) in spoilers.withIndex()) {
            val rawHead = Jsoup.parse(match.groupValues[1]).text().trim()
            val parsedNum = SEASON_NUM_REGEX.find(rawHead)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: (idx + 1)

            val seasonPoster = tmdbShow?.seasons?.find { it.number == parsedNum }?.poster ?: artworkUrl(localPoster)

            seasons.add(
                Season(
                    id = "$id#s$idx",
                    number = parsedNum,
                    title = rawHead,
                    poster = artworkUrl(seasonPoster)
                )
            )
        }

        return TvShow(
            id = id,
            title = title,
            poster = tmdbShow?.poster ?: artworkUrl(localPoster),
            banner = tmdbShow?.banner,
            overview = tmdbShow?.overview ?: localPlot,
            rating = tmdbShow?.rating,
            released = tmdbShow?.released?.let { "${it.get(java.util.Calendar.YEAR)}-${it.get(java.util.Calendar.MONTH) + 1}-${it.get(java.util.Calendar.DAY_OF_MONTH)}" },
            runtime = tmdbShow?.runtime,
            genres = tmdbShow?.genres ?: emptyList(),
            seasons = seasons
        )
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        val postId = seasonId.substringBefore("#s")
        val seasonIdx = seasonId.substringAfter("#s").toIntOrNull() ?: 0

        val url = "$wpJson/posts/$postId"
        val jsonStr = httpGet(url)
        val obj = JSONObject(jsonStr)
        val rawTitle = obj.optJSONObject("title")?.optString("rendered").orEmpty()
        val showTitle = org.jsoup.parser.Parser.unescapeEntities(rawTitle, false).trim()
        val content = obj.optJSONObject("content")?.optString("rendered").orEmpty()

        val spoilers = SPOILER_REGEX.findAll(content).toList()
        val seasonSpoiler = spoilers.getOrNull(seasonIdx) ?: return emptyList()

        val headText = Jsoup.parse(seasonSpoiler.groupValues[1]).text().trim()
        val seasonNum = SEASON_NUM_REGEX.find(headText)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: (seasonIdx + 1)

        val tmdbEpisodes = try {
            val tmdbShow = TmdbUtils.getTvShow(showTitle, language = language)
            if (tmdbShow != null) TmdbUtils.getEpisodesBySeason(tmdbShow.id, seasonNum, language = language) else emptyList()
        } catch (_: Exception) {
            emptyList()
        }

        val sContent = seasonSpoiler.groupValues[2]
        val lines = sContent.split("<br />", "<br>", "\n")
        val episodes = mutableListOf<Episode>()

        var epIdx = 0
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isBlank()) continue

            // E.g.: 1&#215;01 or 1x01
            val epMatch = EPISODE_LINE_REGEX.find(trimmed) ?: continue

            val epCode = epMatch.groupValues[1].replace("&#215;", "x").replace("×", "x")
            val epNumber = epCode.substringAfter("x").toIntOrNull() ?: (epIdx + 1)
            val epName = Jsoup.parse(epMatch.groupValues[2]).text().trim()

            // Use euroStreaming episode title when TMDB title is missing or generic (e.g. "Episodio 1")
            val tmdbEp = tmdbEpisodes.find { it.number == epNumber }
            val tmdbTitle = tmdbEp?.title?.trim().orEmpty()
            val isGenericTmdbTitle = tmdbTitle.isBlank() || tmdbTitle.matches(GENERIC_EPISODE_TITLE_REGEX)

            val resolvedTitle = when {
                epName.isNotBlank() && isGenericTmdbTitle -> epName
                tmdbTitle.isNotBlank() -> tmdbTitle
                epName.isNotBlank() -> epName
                else -> "Episodio $epNumber"
            }

            episodes.add(
                Episode(
                    id = "$postId#s$seasonIdx#e$epIdx",
                    number = epNumber,
                    title = resolvedTitle,
                    poster = tmdbEp?.poster ?: artworkUrl(null),
                    overview = tmdbEp?.overview
                )
            )
            epIdx++
        }

        return episodes
    }

    override suspend fun getGenre(id: String, page: Int): Genre = throw NotImplementedError()

    override suspend fun getPeople(id: String, page: Int): People = throw NotImplementedError()

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        val postId = id.substringBefore("#s")
        val part = id.substringAfter("#s")
        val seasonIdx = part.substringBefore("#e").toIntOrNull() ?: 0
        val targetEpIdx = part.substringAfter("#e").toIntOrNull() ?: 0

        val url = "$wpJson/posts/$postId"
        val jsonStr = httpGet(url)
        val obj = JSONObject(jsonStr)
        val content = obj.optJSONObject("content")?.optString("rendered").orEmpty()

        val spoilers = SPOILER_REGEX.findAll(content).toList()
        val seasonSpoiler = spoilers.getOrNull(seasonIdx) ?: return emptyList()

        val sContent = seasonSpoiler.groupValues[2]
        val lines = sContent.split("<br />", "<br>", "\n")

        var currentEpIdx = 0
        var targetLine: String? = null
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isBlank()) continue
            if (EPISODE_CODE_REGEX.containsMatchIn(trimmed)) {
                if (currentEpIdx == targetEpIdx) {
                    targetLine = trimmed
                    break
                }
                currentEpIdx++
            }
        }

        if (targetLine == null) {
            Log.w(TAG, "[SERVERS] Target line for episode not found in HTML")
            return emptyList()
        }

        // Extract all links: <a href="URL">HOST</a>
        val rawLinks = LINK_REGEX.findAll(targetLine).map {
            Pair(it.groupValues[1].trim(), it.groupValues[2].trim())
        }.toList()

        Log.d(TAG, "[SERVERS] Found ${rawLinks.size} candidate link(s): ${rawLinks.joinToString { "${it.second} -> ${it.first}" }}")

        val servers = coroutineScope {
            rawLinks.map { (href, rawHost) ->
                async(Dispatchers.IO) {
                    if (href.isBlank()) return@async null
                    val lowerHost = rawHost.lowercase()

                    // Skip download links (e.g. host "DL" or uprot "/msd/")
                    if (lowerHost == "dl" || href.contains("/msd/", ignoreCase = true)) {
                        Log.d(TAG, "[SERVERS] Skipping download link: host='$rawHost', href='$href'")
                        return@async null
                    }

                    when {
                        // 1. Clicka.cc link (DeltaBit, MixDrop, Turbovid)
                        href.contains("clicka.cc", ignoreCase = true) -> {
                            Log.d(TAG, "[SERVERS] Resolving Clicka candidate: host='$rawHost', href='$href'")
                            val resolvedUrl = resolveClickaUrl(href)
                            if (!resolvedUrl.isNullOrBlank()) {
                                val finalHost = when {
                                    resolvedUrl.contains("deltabit", ignoreCase = true) -> "DeltaBit"
                                    resolvedUrl.contains("mixdrop", ignoreCase = true) -> "MixDrop"
                                    resolvedUrl.contains("turbovid", ignoreCase = true) -> "Turbovid"
                                    else -> rawHost
                                }
                                Log.i(TAG, "[SERVERS] Clicka resolved to: $finalHost ($resolvedUrl)")
                                Video.Server(id = resolvedUrl, name = finalHost, src = resolvedUrl)
                            } else {
                                Log.w(TAG, "[SERVERS] Clicka resolution failed for href: '$href'")
                                null
                            }
                        }
                        // 2. MaxStream / Uprot.net link
                        href.contains("uprot.net", ignoreCase = true) || lowerHost.contains("maxstream") || href.contains("maxstream", ignoreCase = true) -> {
                            Log.d(TAG, "[SERVERS] Resolving MaxStream candidate: host='$rawHost', href='$href'")
                            val finalMaxstream = href
                            if (!finalMaxstream.isNullOrBlank()) {
                                Log.i(TAG, "[SERVERS] MaxStream resolved to: $finalMaxstream")
                                Video.Server(id = finalMaxstream, name = "MaxStream", src = finalMaxstream)
                            } else {
                                Log.w(TAG, "[SERVERS] MaxStream resolution failed for href: '$href'")
                                null
                            }
                        }
                        else -> {
                            Log.d(TAG, "[SERVERS] Skipping unsupported candidate: host='$rawHost', href='$href'")
                            null
                        }
                    }
                }
            }.awaitAll().filterNotNull()
        }

        Log.i(TAG, "[SERVERS] Total resolved servers: ${servers.size} (${servers.joinToString { it.name }})")
        return servers
    }

    // RESOLUTION OF CLICKA.CC -> SAFEGO -> NO-CAPTCHA -> FINAL HOST
    private fun resolveClickaUrl(clickaUrl: String): String? {
        return try {
            // Step 1: Request clicka.cc with redirects disabled to capture Location header to safego.cc
            val req1 = Request.Builder()
                .url(clickaUrl)
                .header("User-Agent", USER_AGENT)
                .header("Referer", baseUrl)
                .build()

            var safegoUrl: String? = null
            noRedirectClient.newCall(req1).execute().use { resp ->
                val loc = resp.header("Location")
                if (!loc.isNullOrBlank() && loc.contains("safego.cc")) {
                    safegoUrl = loc
                } else {
                    val body = resp.body?.string().orEmpty()
                    val m = SAFEGO_URL_REGEX.find(body)
                    safegoUrl = m?.value
                }
            }

            if (safegoUrl.isNullOrBlank()) {
                Log.w(TAG, "Unable to find safego URL from $clickaUrl")
                return null
            }

            // Step 2: Replace safe.php with safe.php_no_captcha.php
            val noCaptchaUrl = safegoUrl!!.replace("safe.php", "safe.php_no_captcha.php")

            // Step 3: Request safe.php_no_captcha.php and extract "Proceed to video" button
            val reqSafe = Request.Builder()
                .url(noCaptchaUrl)
                .header("User-Agent", USER_AGENT)
                .header("Referer", clickaUrl)
                .build()

            val safeHtml = httpClient.newCall(reqSafe).execute().use { it.body?.string().orEmpty() }

            val proceedUrl = PROCEED_VIDEO_REGEX.find(safeHtml)?.groupValues?.getOrNull(1)
            if (proceedUrl.isNullOrBlank()) {
                Log.w(TAG, "Proceed to video button not found in $noCaptchaUrl")
                return null
            }

            // Step 4: If link is still clicka.cc (e.g. adelta), follow the final redirect
            if (proceedUrl.contains("clicka.cc", ignoreCase = true)) {
                val reqAdelta = Request.Builder()
                    .url(proceedUrl)
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", noCaptchaUrl)
                    .build()

                noRedirectClient.newCall(reqAdelta).execute().use { resp ->
                    resp.header("Location")?.takeIf { it.isNotBlank() } ?: proceedUrl
                }
            } else {
                proceedUrl
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error resolving clicka.cc ($clickaUrl): ${e.message}")
            null
        }
    }

    override suspend fun getVideo(server: Video.Server): Video {
        return Extractor.extract(server.src, server)
    }
}
