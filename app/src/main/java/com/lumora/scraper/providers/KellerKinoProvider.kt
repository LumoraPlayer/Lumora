// Copyright (c) 2026 Lumora (LumoraPlayer)
package com.lumora.scraper.providers

import android.util.Log
import android.webkit.CookieManager
import com.lumora.scraper.ScraperApp
import com.lumora.scraper.adapters.AppAdapter
import com.lumora.scraper.extractors.Extractor
import com.lumora.scraper.models.Category
import com.lumora.scraper.models.Episode
import com.lumora.scraper.models.Genre
import com.lumora.scraper.models.Movie
import com.lumora.scraper.models.People
import com.lumora.scraper.models.TvShow
import com.lumora.scraper.models.Video
import com.lumora.scraper.utils.UserPreferences
import com.lumora.scraper.utils.DnsResolver
import com.lumora.scraper.utils.NetworkClient
import com.lumora.scraper.utils.WebViewResolver
import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Url
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

object KellerKinoProvider : Provider {

    private const val TAG = "KellerKino"

    override val name = "Kellerkino"

    private const val DEFAULT_BASE_URL = "https://www.kellerkino.com/"

    // KELLERKINO_PLAYBACK_BRIDGE_V11 limits wrapper traversal only.
    // It does not limit the visible Kellerkino server list.
    private const val KELLERKINO_PLAYBACK_MAX_DEPTH = 3
    private const val KELLERKINO_PLAYBACK_MAX_CANDIDATES = 12

    private val KELLERKINO_PLAYBACK_HOST_MARKERS = listOf(
        "playmogo",
        "dood",
        "voe",
        "streamtape",
        "streamta",
        "lulu",
        "vidara",
        "vinovo",
        "bigwarp",
        "filemoon",
        "vidoza",
        "mixdrop",
        "streamwish",
        "uqload",
        "vidmoly",
        "supervideo",
        "yourupload",
        "rpmvid",
        "streamhide",
        "upstream",
    )

    override val baseUrl: String = DEFAULT_BASE_URL

    override val logo: String
        get() = "${baseUrl}favicon.ico"

    override val language = "de"

    private val mutex = Mutex()

    @Volatile
    private var webViewResolver: WebViewResolver? = null

    private fun resolver(): WebViewResolver =
        webViewResolver
            ?: WebViewResolver(ScraperApp.instance).also {
                webViewResolver = it
            }

    private interface Service {
        @GET
        suspend fun get(@Url url: String): Document

        companion object {
            fun build(baseUrl: String): Service {
                val client = OkHttpClient.Builder()
                    .dns(DnsResolver.doh)
                    .connectTimeout(20, TimeUnit.SECONDS)
                    .readTimeout(25, TimeUnit.SECONDS)
                    .followRedirects(true)
                    .followSslRedirects(true)
                    .addInterceptor(CookieInterceptor())
                    .build()

                return Retrofit.Builder()
                    .baseUrl(baseUrl)
                    .client(client)
                    .addConverterFactory(
                        JsoupConverterFactory.create()
                    )
                    .build()
                    .create(Service::class.java)
            }
        }
    }

    private class CookieInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val requestBuilder = chain.request()
                .newBuilder()
                .header("User-Agent", NetworkClient.USER_AGENT)
                .header(
                    "Accept",
                    "text/html,application/xhtml+xml," +
                        "application/xml;q=0.9,*/*;q=0.8"
                )
                .header("Accept-Language", "de-DE,de;q=0.9,en;q=0.7")

            CookieManager.getInstance()
                .getCookie(chain.request().url.toString())
                ?.takeIf { it.isNotBlank() }
                ?.let {
                    requestBuilder.header("Cookie", it)
                }

            return chain.proceed(requestBuilder.build())
        }
    }

    @Volatile
    private var cachedService: Service? = null

    @Volatile
    private var cachedServiceBaseUrl: String? = null

    private val service: Service
        get() {
            val current = baseUrl

            val cached = cachedService
            if (
                cached != null &&
                cachedServiceBaseUrl == current
            ) {
                return cached
            }

            return synchronized(this) {
                val again = cachedService

                if (
                    again != null &&
                    cachedServiceBaseUrl == current
                ) {
                    again
                } else {
                    Service.build(current).also {
                        cachedService = it
                        cachedServiceBaseUrl = current
                    }
                }
            }
        }

    override suspend fun getHome(): List<Category> {
        val doc = getDocument(baseUrl)
        val categories = mutableListOf<Category>()

        val cinema = parseSection(
            doc,
            listOf(
                "Aktuelle Kinofilme",
                "Aktuell im Kino"
            )
        )

        if (cinema.isNotEmpty()) {
            categories += Category(
                name = "Aktuelle Kinofilme",
                list = cinema
            )
        }

        val premieres = parseSection(
            doc,
            listOf(
                "Neuerscheinungen",
                "Streaming-Premieren"
            )
        )

        if (premieres.isNotEmpty()) {
            categories += Category(
                name = "Neuerscheinungen",
                list = premieres
            )
        }

        val latest = parseSection(
            doc,
            listOf("Zuletzt hinzugefügt")
        )

        if (latest.isNotEmpty()) {
            categories += Category(
                name = "Zuletzt hinzugefügt",
                list = latest
            )
        }

        if (categories.isEmpty()) {
            val fallback = parseMovies(doc)
            if (fallback.isNotEmpty()) {
                categories += Category(
                    name = "Filme",
                    list = fallback
                )
            }
        }

        return categories
    }

    override suspend fun search(
        query: String,
        page: Int
    ): List<AppAdapter.Item> {

        if (query.isBlank()) {
            if (page > 1) return emptyList()

            val doc = getDocument("${baseUrl}kategorien/")

            return parseGenres(doc)
        }

        val encoded = URLEncoder.encode(
            query.trim(),
            Charsets.UTF_8.name()
        )

        val candidates = listOf(
            "${baseUrl}?s=$encoded",
            "${baseUrl}suche/?s=$encoded",
            "${baseUrl}search/?s=$encoded"
        )

        for (url in candidates) {
            val result = runCatching {
                parseMovies(getDocument(url))
            }.getOrDefault(emptyList())

            if (result.isNotEmpty()) {
                return result
            }
        }

        // Fallback: Archiv laden und lokal Titel filtern.
        val archive = getMovies(page)

        return archive.filter {
            it.title.contains(
                query,
                ignoreCase = true
            )
        }
    }

    override suspend fun getMovies(page: Int): List<Movie> {
        val url =
            if (page <= 1) {
                "${baseUrl}archiv/"
            } else {
                "${baseUrl}archiv/seite/$page/?sort=newest"
            }

        return parseMovies(getDocument(url))
    }

    override suspend fun getTvShows(
        page: Int
    ): List<TvShow> = emptyList()

    override suspend fun getMovie(id: String): Movie {
        val url = absolute(id)
        val doc = getDocument(url)

        val title =
            doc.selectFirst("h1")?.text()?.trim()
                ?: doc.selectFirst("meta[property=og:title]")
                    ?.attr("content")
                    ?.trim()
                ?: url.substringAfterLast('/')
                    .replace('-', ' ')

        val description =
            doc.selectFirst(
                "meta[name=description]"
            )?.attr("content")?.trim()
                ?: doc.selectFirst(
                    "meta[property=og:description]"
                )?.attr("content")?.trim()

        val poster =
            doc.selectFirst(
                "meta[property=og:image]"
            )?.attr("content")?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: doc.selectFirst(
                    "img[src*=poster], img[class*=poster]"
                )?.let {
                    imageUrl(it)
                }

        val bodyText = doc.body()?.text().orEmpty()

        val year =
            Regex("""\b(?:19|20)\d{2}\b""")
                .find(bodyText)
                ?.value

        val rating =
            Regex(
                """IMDb\s*([0-9]+(?:[.,][0-9]+)?)""",
                RegexOption.IGNORE_CASE
            )
                .find(bodyText)
                ?.groupValues
                ?.getOrNull(1)
                ?.replace(',', '.')
                ?.toDoubleOrNull()

        val genres =
            doc.select(
                "a[href*=/kategorie/], " +
                    "a[href*=/category/], " +
                    "a[href*=/genre/]"
            )
                .mapNotNull { element ->
                    val genreName = element.text().trim()
                    val genreUrl = element.attr("href").trim()

                    if (genreName.isBlank() || genreUrl.isBlank()) {
                        null
                    } else {
                        Genre(
                            id = absolute(genreUrl),
                            name = genreName
                        )
                    }
                }
                .distinctBy { it.id }

        return Movie(
            id = url,
            title = title,
            overview = description,
            released = year,
            rating = rating,
            poster = poster,
            genres = genres
        )
    }

    override suspend fun getTvShow(id: String): TvShow {
        throw UnsupportedOperationException(
            "Kellerkino supports movies only"
        )
    }

    override suspend fun getEpisodesBySeason(
        seasonId: String
    ): List<Episode> = emptyList()

    override suspend fun getGenre(
        id: String,
        page: Int
    ): Genre {

        val baseGenreUrl = absolute(id)
            .trimEnd('/')

        val url =
            if (page <= 1) {
                "$baseGenreUrl/"
            } else {
                "$baseGenreUrl/seite/$page/"
            }

        val doc = getDocument(url)

        val name =
            doc.selectFirst("h1")?.text()?.trim()
                ?: id.substringAfterLast('/')
                    .replace('-', ' ')

        return Genre(
            id = id,
            name = name,
            shows = parseMovies(doc)
        )
    }

    override suspend fun getPeople(
        id: String,
        page: Int
    ): People =
        People(
            id = id,
            name = id,
            filmography = emptyList()
        )

    override suspend fun getServers(
        id: String,
        videoType: Video.Type
    ): List<Video.Server> {

        if (videoType !is Video.Type.Movie) {
            return emptyList()
        }

        val movieUrl = absolute(id)
        val doc = getDocument(movieUrl)

        val servers = linkedMapOf<String, Video.Server>()

        fun addServer(
            url: String,
            label: String?
        ) {
            val absolute = absolute(url)

            if (!absolute.startsWith("http")) {
                return
            }

            val host =
                runCatching {
                    java.net.URI(absolute)
                        .host
                        ?.removePrefix("www.")
                }.getOrNull()

            val name =
                label
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: host
                    ?: "Server"

            // KELLERKINO_SERVER_FILTER_V11
            val lowerName = name.lowercase()
            val lowerUrl = absolute.lowercase()
            val ownHost = runCatching {
                java.net.URI(DEFAULT_BASE_URL).host.orEmpty().removePrefix("www.")
            }.getOrDefault("kellerkino.com")
            val candidateHost = runCatching {
                java.net.URI(absolute).host.orEmpty().removePrefix("www.")
            }.getOrDefault("")

            val isTrailer =
                lowerName == "trailer" ||
                    lowerName.startsWith("trailer ") ||
                    lowerUrl.contains("/trailer")

            val isKellerkinoNavigation =
                candidateHost.equals(ownHost, ignoreCase = true) &&
                    (
                        lowerName.contains("kellerkino filmarchiv") ||
                        lowerName.contains("kellerkino startseite") ||
                        lowerName == "startseite" ||
                        lowerUrl.trimEnd('/') == DEFAULT_BASE_URL.trimEnd('/').lowercase()
                    )

            if (isTrailer || isKellerkinoNavigation) {
                return
            }

            servers.putIfAbsent(
                absolute,
                Video.Server(
                    id = absolute,
                    name = name,
                    src = absolute
                )
            )
        }

        doc.select("iframe[src]").forEach {
            addServer(
                it.attr("src"),
                it.attr("title")
            )
        }

        doc.select("a[href]").forEach { link ->
            val href = link.attr("href").trim()
            val text = link.text().trim()

            val combined =
                "$href $text".lowercase()

            val looksLikeServer =
                listOf(
                    "voe",
                    "streamtape",
                    "dood",
                    "lulu",
                    "vidara",
                    "vinovo",
                    "stream",
                    "watch",
                    "embed"
                ).any {
                    combined.contains(it)
                }

            if (looksLikeServer) {
                addServer(href, text)
            }
        }

        // Manche Seiten verstecken Player-URLs in data-* Attributen.
        doc.select("[data-url], [data-src], [data-link]").forEach {
            listOf(
                it.attr("data-url"),
                it.attr("data-src"),
                it.attr("data-link")
            ).filter { value ->
                value.startsWith("http") ||
                    value.startsWith("//")
            }.forEach { value ->
                addServer(value, it.text())
            }
        }

        Log.d(
            TAG,
            "getServers: ${servers.size} servers for $movieUrl"
        )

        return servers.values.toList()
    }

    // KELLERKINO_PLAYBACK_BRIDGE_V11
    //
    // Kellerkino often exposes a wrapper/redirect URL instead of the final
    // hoster URL. Direct links still use the existing extractor first, so
    // working Playmogo/VOE/Vidara/Lulu/Streamtape links remain untouched.
    // Only after that extractor fails do we follow the wrapper and inspect
    // redirects/iframes/data-* attributes/meta refresh/JS location redirects.
    override suspend fun getVideo(
        server: Video.Server
    ): Video {
        val initialUrl = absolute(
            server.src.ifBlank { server.id }
        ).trim()

        if (initialUrl.isBlank()) {
            throw Exception("Kellerkino server URL is empty")
        }

        val queue = java.util.ArrayDeque<Pair<String, Int>>()
        val visited = linkedSetOf<String>()
        var lastError: Throwable? = null

        queue.add(initialUrl to 0)

        while (queue.isNotEmpty()) {
            val (candidate, depth) = queue.removeFirst()
            val normalized = normalizeKellerKinoPlaybackUrl(
                candidate,
                server
            )

            if (
                normalized.isBlank() ||
                !visited.add(normalized)
            ) {
                continue
            }

            try {
                return Extractor.extract(
                    normalized,
                    server.copy(src = normalized)
                )
            } catch (error: Throwable) {
                lastError = error
                Log.d(
                    TAG,
                    "Extractor failed for ${server.name}: $normalized -> ${error.message}"
                )
            }

            if (depth >= KELLERKINO_PLAYBACK_MAX_DEPTH) {
                continue
            }

            val discovered = runCatching {
                resolveKellerKinoPlaybackCandidates(normalized)
            }.onFailure { error ->
                Log.d(
                    TAG,
                    "Wrapper resolve failed for $normalized: ${error.message}"
                )
            }.getOrDefault(emptyList())

            discovered
                .asSequence()
                .map { normalizeKellerKinoPlaybackUrl(it, server) }
                .filter { it.isNotBlank() }
                .filterNot { visited.contains(it) }
                .take(KELLERKINO_PLAYBACK_MAX_CANDIDATES)
                .forEach { next ->
                    queue.add(next to (depth + 1))
                }
        }

        throw Exception(
            "Kellerkino playback failed for ${server.name}",
            lastError
        )
    }

    private data class KellerKinoBridgePage(
        val finalUrl: String,
        val html: String,
    )

    private suspend fun resolveKellerKinoPlaybackCandidates(
        url: String
    ): List<String> {
        val page = fetchKellerKinoBridgePage(url)
        val result = linkedSetOf<String>()

        fun add(raw: String?) {
            val value = raw
                ?.trim()
                ?.replace("\\/", "/")
                .orEmpty()

            if (
                value.isBlank() ||
                value.startsWith("#") ||
                value.startsWith("javascript:", ignoreCase = true) ||
                value.startsWith("data:", ignoreCase = true)
            ) {
                return
            }

            val absoluteUrl = runCatching {
                when {
                    value.startsWith("//") -> "https:$value"
                    value.startsWith("http://", ignoreCase = true) ||
                        value.startsWith("https://", ignoreCase = true) -> value
                    else -> java.net.URI(page.finalUrl)
                        .resolve(value)
                        .toString()
                }
            }.getOrNull() ?: return

            if (
                !absoluteUrl.startsWith("http://", ignoreCase = true) &&
                !absoluteUrl.startsWith("https://", ignoreCase = true)
            ) {
                return
            }

            val lower = absoluteUrl.lowercase()
            if (
                lower.endsWith(".css") ||
                lower.endsWith(".js") ||
                lower.endsWith(".jpg") ||
                lower.endsWith(".jpeg") ||
                lower.endsWith(".png") ||
                lower.endsWith(".gif") ||
                lower.endsWith(".svg") ||
                lower.endsWith(".webp") ||
                lower.endsWith(".woff") ||
                lower.endsWith(".woff2")
            ) {
                return
            }

            result += absoluteUrl
        }

        if (!sameKellerKinoUrl(page.finalUrl, url)) {
            add(page.finalUrl)
        }

        val document = org.jsoup.Jsoup.parse(
            page.html,
            page.finalUrl
        )

        document.select(
            "iframe[src], video[src], source[src], " +
                "[data-player-url], [data-url], [data-src], [data-link]"
        ).forEach { element ->
            listOf(
                "src",
                "data-player-url",
                "data-url",
                "data-src",
                "data-link",
            ).forEach { attribute ->
                add(element.attr(attribute))
            }
        }

        document.select("a[href]").forEach { anchor ->
            val href = anchor.attr("href")
            val combined = (
                href + " " + anchor.text() + " " + anchor.attr("title")
                ).lowercase()

            if (
                KELLERKINO_PLAYBACK_HOST_MARKERS.any {
                    combined.contains(it)
                }
            ) {
                add(href)
            }
        }

        document.select("meta[http-equiv]").forEach { meta ->
            if (
                meta.attr("http-equiv")
                    .equals("refresh", ignoreCase = true)
            ) {
                Regex(
                    "(?i)\\burl\\s*=\\s*['\"]?([^'\";\\s]+)"
                ).find(meta.attr("content"))
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.let(::add)
            }
        }

        val scriptPatterns = listOf(
            Regex(
                "(?is)(?:window\\.)?location(?:\\.href)?\\s*=\\s*[\"']([^\"']+)[\"']"
            ),
            Regex(
                "(?is)(?:window\\.)?location\\.replace\\(\\s*[\"']([^\"']+)[\"']\\s*\\)"
            ),
            Regex(
                "(?is)(?:window\\.)?location\\.assign\\(\\s*[\"']([^\"']+)[\"']\\s*\\)"
            ),
        )

        document.select("script").forEach { script ->
            val body = script.data()
                .ifBlank { script.html() }

            scriptPatterns.forEach { pattern ->
                pattern.findAll(body).forEach { match ->
                    add(match.groupValues[1])
                }
            }

            Regex(
                "https?://[^\\s\"'<>\\\\]+",
                RegexOption.IGNORE_CASE
            ).findAll(body).forEach { match ->
                val raw = match.value
                if (
                    KELLERKINO_PLAYBACK_HOST_MARKERS.any {
                        raw.contains(it, ignoreCase = true)
                    }
                ) {
                    add(raw)
                }
            }
        }

        return result
            .filterNot { sameKellerKinoUrl(it, url) }
            .take(KELLERKINO_PLAYBACK_MAX_CANDIDATES)
    }

    private suspend fun fetchKellerKinoBridgePage(
        url: String
    ): KellerKinoBridgePage {
        val direct = runCatching {
            kotlinx.coroutines.withContext(
                kotlinx.coroutines.Dispatchers.IO
            ) {
                val client = okhttp3.OkHttpClient.Builder()
                    .followRedirects(true)
                    .followSslRedirects(true)
                    .build()

                val request = okhttp3.Request.Builder()
                    .url(url)
                    .header(
                        "User-Agent",
                        NetworkClient.USER_AGENT
                    )
                    .header(
                        "Accept",
                        "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                    )
                    .header(
                        "Accept-Language",
                        "de-DE,de;q=0.9,en;q=0.8"
                    )
                    .header(
                        "Referer",
                        DEFAULT_BASE_URL
                    )
                    .build()

                client.newCall(request)
                    .execute()
                    .use { response ->
                        KellerKinoBridgePage(
                            finalUrl = response.request.url.toString(),
                            html = response.body?.string().orEmpty(),
                        )
                    }
            }
        }.getOrNull()

        if (
            direct != null &&
            (
                direct.html.isNotBlank() ||
                !sameKellerKinoUrl(direct.finalUrl, url)
            )
        ) {
            return direct
        }

        val document = getDocument(url)

        return KellerKinoBridgePage(
            finalUrl = document.location().ifBlank { url },
            html = document.outerHtml(),
        )
    }

    private fun normalizeKellerKinoPlaybackUrl(
        url: String,
        server: Video.Server
    ): String {
        val cleaned = url.trim().replace("\\/", "/")
        val label = (server.name + " " + server.id).lowercase()

        if (
            !label.contains("voe") &&
            !label.contains("veo")
        ) {
            return cleaned
        }

        val uri = runCatching {
            java.net.URI(cleaned)
        }.getOrNull() ?: return cleaned

        val host = uri.host
            ?.lowercase()
            ?.removePrefix("www.")
            .orEmpty()

        if (
            host.isBlank() ||
            host.contains("kellerkino")
        ) {
            return cleaned
        }

        val path = uri.rawPath.orEmpty().trim('/')
        if (path.isBlank()) {
            return cleaned
        }

        val code = path
            .removePrefix("e/")
            .removePrefix("d/")
            .substringAfterLast('/')

        if (code.isBlank()) {
            return cleaned
        }

        return buildString {
            append("https://voe.sx/e/")
            append(code)

            if (!uri.rawQuery.isNullOrBlank()) {
                append('?')
                append(uri.rawQuery)
            }
        }
    }

    private fun sameKellerKinoUrl(
        left: String,
        right: String
    ): Boolean =
        runCatching {
            java.net.URI(left)
                .normalize()
                .toString()
                .trimEnd('/')
                .equals(
                    java.net.URI(right)
                        .normalize()
                        .toString()
                        .trimEnd('/'),
                    ignoreCase = true
                )
        }.getOrDefault(
            left.trimEnd('/')
                .equals(
                    right.trimEnd('/'),
                    ignoreCase = true
                )
        )


    private fun parseSection(
        doc: Document,
        titles: List<String>
    ): List<Movie> {

        for (title in titles) {
            val heading =
                doc.select("h1,h2,h3,h4")
                    .firstOrNull {
                        it.text().contains(
                            title,
                            ignoreCase = true
                        )
                    }
                    ?: continue

            var current: Element? = heading.parent()

            repeat(4) {
                val container = current ?: return@repeat
                val movies = parseMovies(container)

                if (movies.isNotEmpty()) {
                    return movies
                }

                current = container.parent()
            }
        }

        return emptyList()
    }

    private fun parseMovies(
        root: Element
    ): List<Movie> {

        val result = linkedMapOf<String, Movie>()

        root.select("a[href]").forEach { anchor ->
            val href = anchor.attr("href").trim()

            if (!isMovieUrl(href)) {
                return@forEach
            }

            val url = absolute(href)

            if (result.containsKey(url)) {
                return@forEach
            }

            val card =
                anchor.parents()
                    .firstOrNull {
                        it.select("img").isNotEmpty() &&
                            it.text().trim().length > 2
                    }
                    ?: anchor.parent()
                    ?: anchor

            val title =
                anchor.attr("title")
                    .trim()
                    .ifBlank {
                        card.selectFirst(
                            "h1,h2,h3,h4,.title,.movie-title,[class*=title]"
                        )?.text()?.trim().orEmpty()
                    }
                    .ifBlank {
                        anchor.text()
                            .trim()
                            .replace(
                                Regex(
                                    """\s+(?:(?:19|20)\d{2}\s+)?(?:\d{1,3}\s*Min(?:uten)?\.?\s+)?IMDb\s*[0-9]+(?:[.,][0-9]+)?\s*$""",
                                    RegexOption.IGNORE_CASE
                                ),
                                ""
                            )
                            .trim()
                    }

            val displayTitle =
                title
                    .replace(
                        Regex(
                            """\s+(?:(?:19|20)\d{2})(?:\s+\d{1,3}\s*Min(?:uten)?\.?)?\s+IMDb\s*[0-9]+(?:[.,][0-9]+)?\s*$""",
                            RegexOption.IGNORE_CASE
                        ),
                        ""
                    )
                    .trim()

            if (
                displayTitle.isBlank() ||
                displayTitle.equals(
                    "Film ansehen",
                    ignoreCase = true
                )
            ) {
                return@forEach
            }

            val poster =
                card.selectFirst("img")
                    ?.let { imageUrl(it) }

            val text = card.text()

            val year =
                Regex("""\b(?:19|20)\d{2}\b""")
                    .find(text)
                    ?.value

            val rating =
                Regex(
                    """IMDb\s*([0-9]+(?:[.,][0-9]+)?)""",
                    RegexOption.IGNORE_CASE
                )
                    .find(text)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.replace(',', '.')
                    ?.toDoubleOrNull()

            result[url] = Movie(
                id = url,
                title = displayTitle,
                released = year,
                rating = rating,
                poster = poster
            )
        }

        return result.values.toList()
    }

    private fun parseGenres(
        doc: Document
    ): List<Genre> {

        val ignored = setOf(
            "alle filme",
            "a-z",
            "imdb",
            "forum",
            "anmelden",
            "registrieren"
        )

        return doc.select("a[href]")
            .mapNotNull { anchor ->
                val href = anchor.attr("href").trim()
                val text = anchor.text().trim()

                if (
                    text.isBlank() ||
                    text.lowercase() in ignored
                ) {
                    return@mapNotNull null
                }

                val lower = href.lowercase()

                val looksGenre =
                    lower.contains("/kategorie/") ||
                        lower.contains("/category/") ||
                        lower.contains("/genre/") ||
                        (
                            lower.startsWith(baseUrl.lowercase()) &&
                                !lower.contains("/archiv") &&
                                !lower.contains("/login") &&
                                !lower.contains("/register")
                        )

                if (!looksGenre) {
                    return@mapNotNull null
                }

                Genre(
                    id = absolute(href),
                    name = text
                )
            }
            .distinctBy { it.id }
    }

    private suspend fun getDocument(
        url: String
    ): Document {

        return try {
            val doc = service.get(url)

            if (requiresClearance(doc.outerHtml())) {
                throw IllegalStateException(
                    "Cloudflare clearance required"
                )
            }

            doc.apply {
                setBaseUri(url)
            }
        } catch (error: Exception) {

            val shouldUseWebView =
                error is HttpException &&
                    error.code() in listOf(403, 503) ||
                    requiresClearance(
                        error.message.orEmpty()
                    )

            if (!shouldUseWebView) {
                throw error
            }

            Log.d(
                TAG,
                "Using WebViewResolver for $url: ${error.message}"
            )

            val result = mutex.withLock {
                resolver().getResult(
                    url = url,
                    headers = mapOf(
                        "User-Agent" to NetworkClient.USER_AGENT
                    )
                )
            }

            promoteCookies(
                result.finalUrl ?: url
            )

            Jsoup.parse(
                result.html,
                result.finalUrl ?: url
            )
        }
    }

    private fun requiresClearance(
        html: String
    ): Boolean {

        val lower = html.lowercase()

        return lower.contains("cf-browser-verification") ||
            lower.contains("challenge-running") ||
            lower.contains("just a moment") ||
            lower.contains("checking your browser") ||
            lower.contains("cf-mitigated")
    }

    private fun promoteCookies(
        url: String
    ) {
        val manager = CookieManager.getInstance()

        val cookie =
            manager.getCookie(url)
                ?: manager.getCookie(baseUrl)
                ?: return

        cookie.split(";")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .forEach {
                manager.setCookie(
                    baseUrl,
                    "$it; Path=/"
                )
            }

        manager.flush()
    }

    private fun isMovieUrl(
        href: String
    ): Boolean {

        if (href.isBlank()) return false

        val lower = absolute(href).lowercase()

        if (!lower.startsWith(baseUrl.lowercase())) {
            return false
        }

        val ignored = listOf(
            "/archiv",
            "/kategorien",
            "/login",
            "/register",
            "/registrieren",
            "/anmelden",
            "/forum",
            "/imdb",
            "/kontakt",
            "/seite/"
        )

        if (ignored.any { lower.contains(it) }) {
            return false
        }

        // Kellerkino-Filme liegen derzeit unter Kategorie/Slug,
        // z. B. /komoedie/film-name/
        val path =
            runCatching {
                java.net.URI(lower).path
            }.getOrNull().orEmpty()

        return path.trim('/')
            .split('/')
            .size >= 2
    }

    private fun imageUrl(
        element: Element
    ): String? {

        val value =
            element.attr("data-src")
                .ifBlank {
                    element.attr("data-lazy-src")
                }
                .ifBlank {
                    element.attr("src")
                }
                .trim()

        if (value.isBlank()) return null

        return absolute(value)
    }

    private fun absolute(
        value: String
    ): String {

        val url = value.trim()

        if (url.startsWith("//")) {
            return "https:$url"
        }

        if (
            url.startsWith("http://") ||
            url.startsWith("https://")
        ) {
            return url
        }

        return if (url.startsWith("/")) {
            baseUrl.trimEnd('/') + url
        } else {
            baseUrl + url
        }
    }
}
