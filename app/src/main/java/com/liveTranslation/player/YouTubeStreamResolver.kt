package com.liveTranslation.player

import android.net.Uri
import android.util.Log
import androidx.media3.common.MimeTypes
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Universal video stream resolver.
 *
 * Handles three categories of input:
 *
 *   1. **YouTube** watch/shorts/live/share URLs
 *      → resolved via InnerTube API (ANDROID → IOS → TV_EMBEDDED → web scraping)
 *
 *   2. **Generic web pages** that embed a video
 *      → HTML is fetched and parsed for <video>/<source> tags, og:video meta,
 *        .m3u8 / .mpd / .mp4 URLs in JavaScript, etc.
 *
 *   3. **Direct video URLs** (.mp4, .m3u8, .mpd, .mkv, .webm)
 *      → returned as-is — ExoPlayer plays them natively.
 */
object YouTubeStreamResolver {

    data class ResolvedStream(
        val uri: Uri,
        val mimeType: String? = null,
        /** HTTP headers the player should send (e.g. Referer for CDN auth). */
        val headers: Map<String, String> = emptyMap()
    )

    // ── Public API ─────────────────────────────────────────────────────

    fun isYouTubeUri(uri: Uri): Boolean {
        val host = uri.host?.lowercase() ?: return false
        return host.contains("youtube.com") || host.contains("youtu.be")
    }

    fun isVimeoUri(uri: Uri): Boolean {
        val host = uri.host?.lowercase() ?: return false
        return host.contains("vimeo.com")
    }

    /**
     * Resolve any URI to a playable video stream.
     *
     * - YouTube → InnerTube API with multi-client fallback
     * - Direct video URL → returned as-is
     * - Web page → HTML parsed for embedded video stream
     */
    fun resolvePlayableUri(uri: Uri): ResolvedStream? {
        Log.i(TAG, "PERF | resolvePlayableUri start: $uri")
        val t0 = System.currentTimeMillis()

        val result = when {
            isYouTubeUri(uri) -> resolveYouTubeUri(uri)
            isVimeoUri(uri) -> resolveVimeoUri(uri)
            looksLikeDirectVideo(uri) -> {
                Log.i(TAG, "Direct video URL detected")
                ResolvedStream(uri, guessMimeTypeFromUrl(uri.toString()))
            }
            else -> resolveGenericUri(uri)
        }

        val elapsed = System.currentTimeMillis() - t0
        if (result != null) {
            Log.i(TAG, "PERF | resolved in ${elapsed}ms → ${result.uri} " +
                "(mime=${result.mimeType}, headers=${result.headers.size})")
        } else {
            Log.w(TAG, "PERF | resolution FAILED in ${elapsed}ms")
        }
        return result
    }

    // ── YouTube Resolution ─────────────────────────────────────────────

    private fun resolveYouTubeUri(uri: Uri): ResolvedStream? {
        val videoId = extractVideoId(uri) ?: run {
            Log.w(TAG, "Cannot extract video ID from: $uri")
            return null
        }

        Log.i(TAG, "Resolving YouTube videoId=$videoId")

        // Try InnerTube API with each client configuration
        for (client in INNERTUBE_CLIENTS) {
            try {
                val result = tryInnerTubeApi(videoId, client)
                if (result != null) {
                    Log.i(TAG, "Resolved via ${client.label}")
                    return result
                }
            } catch (e: Exception) {
                Log.w(TAG, "InnerTube ${client.label} failed: ${e.message}")
            }
        }

        // Fallback: web page scraping
        Log.i(TAG, "All InnerTube clients failed, trying web scraping")
        try {
            val result = tryWebPageScraping(videoId)
            if (result != null) return result
        } catch (e: Exception) {
            Log.w(TAG, "Web scraping failed: ${e.message}")
        }

        Log.e(TAG, "All YouTube resolution methods failed for videoId=$videoId")
        return null
    }

    // ── Vimeo Resolution ─────────────────────────────────────────────

    private fun resolveVimeoUri(uri: Uri): ResolvedStream? {
        val urlStr = uri.toString()
        val videoId = extractVimeoId(uri) ?: run {
            Log.w(TAG, "Cannot extract Vimeo video ID from: $urlStr")
            return null
        }

        Log.i(TAG, "Resolving Vimeo videoId=$videoId")

        // Construct the player config URL which contains the JSON stream info directly
        val configUrl = "https://player.vimeo.com/video/$videoId/config"

        val request = Request.Builder()
            .url(configUrl)
            .header("User-Agent", WEB_USER_AGENT)
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()

        val responseBody = executeRequest(request) ?: return null

        try {
            val configJson = JSONObject(responseBody)
            val requestObj = configJson.optJSONObject("request")
            val filesObj = requestObj?.optJSONObject("files")
            val progressiveArray = filesObj?.optJSONArray("progressive")

            if (progressiveArray != null && progressiveArray.length() > 0) {
                var bestUrl: String? = null
                var bestWidth = 0

                for (i in 0 until progressiveArray.length()) {
                    val item = progressiveArray.optJSONObject(i) ?: continue
                    val url = item.optString("url")
                    val width = item.optInt("width", 0)
                    if (url.isNotBlank() && width > bestWidth) {
                        bestWidth = width
                        bestUrl = url
                    }
                }

                if (bestUrl != null) {
                    Log.i(TAG, "Vimeo: found progressive mp4 URL (width=$bestWidth)")
                    val headers = mapOf(
                        "User-Agent" to WEB_USER_AGENT,
                        "Referer" to "https://player.vimeo.com/"
                    )
                    return ResolvedStream(Uri.parse(bestUrl), MimeTypes.VIDEO_MP4, headers)
                }
            }

            // HLS fallback
            val hlsObj = filesObj?.optJSONObject("hls")
            val hlsUrl = hlsObj?.optJSONObject("cdns")
                ?.optJSONObject(hlsObj.optString("default_cdn"))
                ?.optString("url")
                ?: hlsObj?.optString("url") // some Vimeo structures have it directly

            if (!hlsUrl.isNullOrBlank()) {
                Log.i(TAG, "Vimeo: found HLS manifest")
                val headers = mapOf(
                    "User-Agent" to WEB_USER_AGENT,
                    "Referer" to "https://player.vimeo.com/"
                )
                return ResolvedStream(Uri.parse(hlsUrl), MimeTypes.APPLICATION_M3U8, headers)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse Vimeo config JSON", e)
        }

        Log.w(TAG, "No valid streaming data found in Vimeo config: $configUrl")
        return null
    }

    private fun extractVimeoId(uri: Uri): String? {
        val path = uri.path ?: return null
        // Match numbers in the path (e.g. /video/123456789 or /123456789)
        val match = Regex("""/(\d+)""").find(path)
        return match?.groupValues?.get(1)
    }

    // ── Generic URI Resolution (non-YouTube web pages) ─────────────────

    /**
     * Resolve a non-YouTube web-page URL by fetching its HTML and extracting
     * video stream URLs (HLS manifests, MP4 sources, etc.)
     */
    private fun resolveGenericUri(uri: Uri): ResolvedStream? {
        val urlStr = uri.toString()

        // HEAD request to check Content-Type
        val contentType = httpHead(urlStr)
        if (contentType != null) {
            if (isVideoMimeType(contentType)) {
                val mime = contentType.substringBefore(';').trim()
                Log.i(TAG, "HEAD says Content-Type=$mime → direct video")
                return ResolvedStream(uri, mime)
            }
            if (!contentType.contains("text/html") && !contentType.contains("text/xml") &&
                !contentType.contains("application/xhtml")
            ) {
                Log.w(TAG, "URL is not video and not HTML (Content-Type=$contentType)")
                return null
            }
        }

        // Fetch HTML and extract video
        Log.i(TAG, "Fetching HTML to extract video: $urlStr")
        val request = Request.Builder()
            .url(urlStr)
            .header("User-Agent", WEB_USER_AGENT)
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Accept", "text/html,application/xhtml+xml,*/*")
            .build()

        val html = executeRequest(request) ?: return null

        val videoUrl = extractVideoUrlFromHtml(html, urlStr)
        if (videoUrl != null) {
            val mime = guessMimeTypeFromUrl(videoUrl)
            val headers = mapOf(
                "Referer" to urlStr,
                "User-Agent" to WEB_USER_AGENT
            )
            Log.i(TAG, "Extracted video URL from HTML: $videoUrl (mime=$mime)")
            return ResolvedStream(Uri.parse(videoUrl), mime, headers)
        }

        Log.w(TAG, "No video URL found in HTML page: $urlStr")
        return null
    }

    // ── HTML Video Extraction ──────────────────────────────────────────

    /**
     * Extract a video stream URL from an HTML page.
     *
     * Strategy (ordered by reliability):
     *   1. OpenGraph / Twitter meta tags
     *   2. `<video>` and `<source>` HTML tags
     *   3. HLS manifest URLs (.m3u8) in JavaScript
     *   4. DASH manifest URLs (.mpd) in JavaScript
     *   5. Direct .mp4 URLs in JavaScript
     */
    private fun extractVideoUrlFromHtml(html: String, baseUrl: String): String? {
        // 1. Meta tags (og:video, twitter:player:stream)
        extractFromMetaTags(html, baseUrl)?.let { return it }

        // 2. <video> and <source> tags
        extractFromVideoTags(html, baseUrl)?.let { return it }

        // 3. HLS .m3u8 in JavaScript or HTML
        extractPatternUrl(html, baseUrl, HLS_URL_REGEX, ".m3u8")?.let { return it }

        // 4. DASH .mpd in JavaScript or HTML
        extractPatternUrl(html, baseUrl, DASH_URL_REGEX, ".mpd")?.let { return it }

        // 5. Direct .mp4 in JavaScript (avoid thumbnails, ads)
        extractMp4Url(html, baseUrl)?.let { return it }

        return null
    }

    private fun extractFromMetaTags(html: String, baseUrl: String): String? {
        // og:video, og:video:url, og:video:secure_url
        for (regex in OG_VIDEO_REGEXES) {
            val match = regex.find(html)
            if (match != null) {
                val url = resolveUrl(match.groupValues[1], baseUrl)
                if (looksLikeVideoUrl(url)) {
                    Log.d(TAG, "Found og:video meta tag: $url")
                    return url
                }
            }
        }
        // twitter:player:stream
        TWITTER_STREAM_REGEX.find(html)?.let { match ->
            val url = resolveUrl(match.groupValues[1], baseUrl)
            if (looksLikeVideoUrl(url)) {
                Log.d(TAG, "Found twitter:player:stream: $url")
                return url
            }
        }
        return null
    }

    private fun extractFromVideoTags(html: String, baseUrl: String): String? {
        // <source src="..." type="video/mp4"> or <source src="..." type="application/x-mpegURL">
        for (match in SOURCE_TAG_REGEX.findAll(html)) {
            val src = match.groupValues[1]
            val url = resolveUrl(src, baseUrl)
            if (looksLikeVideoUrl(url)) {
                Log.d(TAG, "Found <source> tag: $url")
                return url
            }
        }
        // <video src="...">
        for (match in VIDEO_SRC_REGEX.findAll(html)) {
            val src = match.groupValues[1]
            val url = resolveUrl(src, baseUrl)
            if (looksLikeVideoUrl(url)) {
                Log.d(TAG, "Found <video src>: $url")
                return url
            }
        }
        return null
    }

    private fun extractPatternUrl(
        html: String, baseUrl: String, regex: Regex, label: String
    ): String? {
        // Collect all matches and prefer the longest URL (usually the main content, not ads)
        val matches = regex.findAll(html)
            .map { resolveUrl(it.groupValues[1], baseUrl) }
            .filter { it.startsWith("http") }
            .toList()

        if (matches.isEmpty()) return null

        // Prefer URLs that look like main content (longer path, contains common CDN patterns)
        val best = matches.maxByOrNull { it.length }
        Log.d(TAG, "Found ${matches.size} $label URLs, best: $best")
        return best
    }

    private fun extractMp4Url(html: String, baseUrl: String): String? {
        val matches = MP4_URL_REGEX.findAll(html)
            .map { resolveUrl(it.groupValues[1], baseUrl) }
            .filter { url ->
                url.startsWith("http") &&
                    // Filter out likely thumbnails / ads
                    !url.contains("thumbnail", ignoreCase = true) &&
                    !url.contains("poster", ignoreCase = true) &&
                    !url.contains("preview", ignoreCase = true) &&
                    !url.contains("/ads/", ignoreCase = true) &&
                    !url.contains("pixel", ignoreCase = true)
            }
            .toList()

        if (matches.isEmpty()) return null

        val best = matches.maxByOrNull { it.length }
        Log.d(TAG, "Found ${matches.size} .mp4 URLs, best: $best")
        return best
    }

    // ── URL Helpers ────────────────────────────────────────────────────

    /** Check if a URL path extension suggests a directly playable video. */
    private fun looksLikeDirectVideo(uri: Uri): Boolean {
        val path = uri.path?.lowercase() ?: return false
        return DIRECT_VIDEO_EXTENSIONS.any { path.endsWith(it) }
    }

    /** Check if a URL string looks like a video stream (not a web page). */
    private fun looksLikeVideoUrl(url: String): Boolean {
        val lower = url.lowercase()
        return VIDEO_URL_KEYWORDS.any { lower.contains(it) }
    }

    /** Check if a MIME type indicates video content. */
    private fun isVideoMimeType(contentType: String): Boolean {
        val lower = contentType.lowercase()
        return lower.startsWith("video/") ||
            lower.contains("mpegurl") ||
            lower.contains("dash+xml") ||
            lower.contains("x-matroska")
    }

    /** Guess MIME type from URL extension. */
    private fun guessMimeTypeFromUrl(url: String): String? {
        val lower = url.lowercase()
        return when {
            lower.contains(".m3u8") -> MimeTypes.APPLICATION_M3U8
            lower.contains(".mpd") -> MimeTypes.APPLICATION_MPD
            lower.contains(".mp4") -> MimeTypes.VIDEO_MP4
            lower.contains(".webm") -> MimeTypes.VIDEO_WEBM
            lower.contains(".mkv") -> MimeTypes.VIDEO_MATROSKA
            lower.contains(".ts") -> MimeTypes.VIDEO_MP2T
            else -> null
        }
    }

    /** Resolve a potentially relative URL against a base URL. */
    private fun resolveUrl(url: String, baseUrl: String): String {
        val trimmed = url.trim()
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return trimmed
        }
        if (trimmed.startsWith("//")) {
            return "https:$trimmed"
        }
        return try {
            java.net.URL(java.net.URL(baseUrl), trimmed).toString()
        } catch (e: Exception) {
            trimmed
        }
    }

    // ── InnerTube Client Configurations ────────────────────────────────

    private data class InnerTubeClient(
        val label: String,
        val clientName: String,
        val clientVersion: String,
        val userAgent: String,
        val apiKey: String,
        val clientId: Int,
        val configureClient: (JSONObject) -> Unit = {},
        val thirdPartyEmbedUrl: String? = null
    )

    private const val ANDROID_API_KEY = "AIzaSyA8eiZmM1FaDVjRy-df2KTyQ_vz_yYM39w"
    private const val IOS_API_KEY = "AIzaSyB-63vPrdThhKuerbB2N_l7Kwwcxj6yUAc"
    private const val DEFAULT_API_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"

    private val INNERTUBE_CLIENTS = listOf(
        InnerTubeClient(
            label = "ANDROID",
            clientName = "ANDROID",
            clientVersion = "19.09.37",
            apiKey = ANDROID_API_KEY,
            clientId = 3,
            userAgent = "com.google.android.youtube/19.09.37 " +
                "(Linux; U; Android 11; en_US; sdk_gphone64_arm64) gzip",
            configureClient = { client ->
                client.put("androidSdkVersion", 30)
                client.put("osName", "Android")
                client.put("osVersion", "11")
                client.put("platform", "MOBILE")
            }
        ),
        InnerTubeClient(
            label = "IOS",
            clientName = "IOS",
            clientVersion = "19.09.3",
            apiKey = IOS_API_KEY,
            clientId = 5,
            userAgent = "com.google.ios.youtube/19.09.3 " +
                "(iPhone14,3; U; CPU iOS 15_6 like Mac OS X)",
            configureClient = { client ->
                client.put("deviceMake", "Apple")
                client.put("deviceModel", "iPhone14,3")
                client.put("osName", "iPhone")
                client.put("osVersion", "15.6.0.19G71")
                client.put("platform", "MOBILE")
            }
        ),
        InnerTubeClient(
            label = "TV_EMBEDDED",
            clientName = "TVHTML5_SIMPLY_EMBEDDED_PLAYER",
            clientVersion = "2.0",
            apiKey = DEFAULT_API_KEY,
            clientId = 85,
            userAgent = "Mozilla/5.0 (SMART-TV; LINUX; Tizen 6.5) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "85.0.4183.93/6.5 TV Safari/537.36",
            thirdPartyEmbedUrl = "https://www.google.com"
        ),
        InnerTubeClient(
            label = "WEB_EMBEDDED",
            clientName = "WEB_EMBEDDED_PLAYER",
            clientVersion = "1.20240304.00.00",
            apiKey = DEFAULT_API_KEY,
            clientId = 56,
            userAgent = "Mozilla/5.0 (Linux; Android 14) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.0.0 Mobile Safari/537.36",
            thirdPartyEmbedUrl = "https://www.google.com"
        )
    )

    // ── InnerTube API Call ──────────────────────────────────────────

    private fun tryInnerTubeApi(videoId: String, client: InnerTubeClient): ResolvedStream? {
        val clientObj = JSONObject().apply {
            put("clientName", client.clientName)
            put("clientVersion", client.clientVersion)
            put("hl", "en")
            put("gl", "US")
            client.configureClient(this)
        }

        val contextObj = JSONObject().apply {
            put("client", clientObj)
            if (client.thirdPartyEmbedUrl != null) {
                put("thirdParty", JSONObject().apply {
                    put("embedUrl", client.thirdPartyEmbedUrl)
                })
            }
        }

        val payload = JSONObject().apply {
            put("context", contextObj)
            put("videoId", videoId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
        }

        val url = "$INNERTUBE_BASE_URL?key=${client.apiKey}&prettyPrint=false"

        val request = Request.Builder()
            .url(url)
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .header("User-Agent", client.userAgent)
            .header("X-YouTube-Client-Name", client.clientId.toString())
            .header("X-YouTube-Client-Version", client.clientVersion)
            .header("Origin", "https://www.youtube.com")
            .header("Referer", "https://www.youtube.com/")
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()

        val responseBody = executeRequest(request) ?: return null

        val response = try {
            JSONObject(responseBody)
        } catch (e: Exception) {
            Log.w(TAG, "${client.label}: Failed to parse JSON response", e)
            return null
        }

        val playability = response.optJSONObject("playabilityStatus")
        val status = playability?.optString("status")

        if (status != "OK") {
            val reason = playability?.optString("reason")
                ?: playability?.optJSONObject("errorScreen")
                    ?.optJSONObject("playerErrorMessageRenderer")
                    ?.optJSONObject("subreason")
                    ?.optString("simpleText")
                ?: "unknown"
            Log.w(TAG, "${client.label}: status=$status, reason=$reason")
            return null
        }

        val streamingData = response.optJSONObject("streamingData") ?: run {
            Log.w(TAG, "${client.label}: No streamingData in OK response")
            return null
        }

        val headers = mapOf(
            "User-Agent" to client.userAgent,
            "Referer" to "https://www.youtube.com/"
        )

        return pickBestStream(streamingData, client.label, headers)
    }

    // ── Stream Selection ───────────────────────────────────────────

    private fun pickBestStream(streamingData: JSONObject, source: String, headers: Map<String, String> = emptyMap()): ResolvedStream? {
        // 1. HLS manifest
        streamingData.optString("hlsManifestUrl").takeIf { it.isNotBlank() }?.let { hlsUrl ->
            Log.i(TAG, "$source: Found HLS manifest URL")
            return ResolvedStream(Uri.parse(hlsUrl), MimeTypes.APPLICATION_M3U8, headers)
        }

        // 2. Progressive formats (video + audio)
        val formats = streamingData.optJSONArray("formats")
        if (formats != null && formats.length() > 0) {
            pickBestProgressiveFormat(formats, headers)?.let { return it }
        }

        // 3. Adaptive formats (video only)
        val adaptive = streamingData.optJSONArray("adaptiveFormats")
        if (adaptive != null && adaptive.length() > 0) {
            pickBestAdaptiveFormat(adaptive, headers)?.let { return it }
        }

        Log.w(TAG, "$source: No playable stream found")
        return null
    }

    private fun pickBestProgressiveFormat(formats: JSONArray, headers: Map<String, String> = emptyMap()): ResolvedStream? {
        data class C(val url: String, val height: Int, val bitrate: Int)
        val candidates = mutableListOf<C>()
        for (i in 0 until formats.length()) {
            val item = formats.optJSONObject(i) ?: continue
            val url = extractUrlFromFormat(item) ?: continue
            val mime = item.optString("mimeType", "")
            val h = item.optInt("height", 0)
            val ac = item.optInt("audioChannels", 0)
            val br = item.optInt("bitrate", 0)
            if (ac <= 0 || !mime.startsWith("video/")) continue
            candidates.add(C(url, h, br))
        }
        if (candidates.isEmpty()) return null
        val best = candidates.sortedBy { abs(it.height - PREFERRED_HEIGHT) }.first()
        Log.d(TAG, "Progressive: picked ${best.height}p from ${candidates.size}")
        return ResolvedStream(Uri.parse(best.url), headers = headers)
    }

    private fun pickBestAdaptiveFormat(formats: JSONArray, headers: Map<String, String> = emptyMap()): ResolvedStream? {
        data class C(val url: String, val height: Int, val bitrate: Int)
        val candidates = mutableListOf<C>()
        for (i in 0 until formats.length()) {
            val item = formats.optJSONObject(i) ?: continue
            val url = extractUrlFromFormat(item) ?: continue
            val mime = item.optString("mimeType", "")
            val h = item.optInt("height", 0)
            val br = item.optInt("bitrate", 0)
            if (!mime.startsWith("video/")) continue
            candidates.add(C(url, h, br))
        }
        if (candidates.isEmpty()) return null
        val best = candidates.sortedBy { abs(it.height - PREFERRED_HEIGHT) }.first()
        Log.d(TAG, "Adaptive: picked ${best.height}p from ${candidates.size}")
        return ResolvedStream(Uri.parse(best.url), headers = headers)
    }

    // ── Web Page Scraping (YouTube legacy fallback) ─────────────────

    private fun tryWebPageScraping(videoId: String): ResolvedStream? {
        val playerResponse = fetchPlayerResponseFromWatchPage(videoId)
            ?: fetchPlayerResponseFromPbj(videoId)
            ?: return null
        val streamingData = playerResponse.optJSONObject("streamingData") ?: return null
        
        val headers = mapOf(
            "User-Agent" to WEB_USER_AGENT,
            "Referer" to "https://www.youtube.com/"
        )
        return pickBestStream(streamingData, "WEB_SCRAPE", headers)
    }

    private fun fetchPlayerResponseFromWatchPage(videoId: String): JSONObject? {
        val watchUrl = "https://www.youtube.com/watch?v=" +
            URLEncoder.encode(videoId, StandardCharsets.UTF_8.name()) + "&hl=en"
        val request = Request.Builder()
            .url(watchUrl)
            .header("User-Agent", WEB_USER_AGENT)
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()
        val html = executeRequest(request) ?: return null
        val anchors = listOf(
            "var ytInitialPlayerResponse", "ytInitialPlayerResponse",
            "window[\"ytInitialPlayerResponse\"]"
        )
        for (anchor in anchors) {
            var idx = 0
            while (true) {
                val mi = html.indexOf(anchor, idx); if (mi < 0) break
                val ei = html.indexOf('=', mi + anchor.length)
                if (ei < 0) { idx = mi + anchor.length; continue }
                var c = ei + 1
                while (c < html.length && html[c].isWhitespace()) c++
                if (c >= html.length || html[c] != '{') { idx = ei + 1; continue }
                val end = (c + QUICK_SCAN_CHARS).coerceAtMost(html.length)
                if (html.substring(c, end).indexOf("\"streamingData\"") < 0) { idx = c + 1; continue }
                val je = findMatchingJsonBrace(html, c)
                if (je <= c) { idx = c + 1; continue }
                try {
                    val cand = JSONObject(html.substring(c, je + 1))
                    if (cand.has("streamingData")) return cand
                } catch (_: Exception) {}
                idx = je + 1
            }
        }
        return null
    }

    private fun fetchPlayerResponseFromPbj(videoId: String): JSONObject? {
        val url = "https://www.youtube.com/watch?v=" +
            URLEncoder.encode(videoId, StandardCharsets.UTF_8.name()) + "&pbj=1&hl=en"
        val request = Request.Builder().url(url)
            .header("User-Agent", WEB_USER_AGENT)
            .header("X-YouTube-Client-Name", "1")
            .header("X-YouTube-Client-Version", "2.20240304.00.00").build()
        val body = executeRequest(request) ?: return null
        return try {
            val arr = org.json.JSONArray(body)
            for (i in 0 until arr.length()) {
                val p = arr.optJSONObject(i)?.optJSONObject("playerResponse")
                if (p?.has("streamingData") == true) return p
            }; null
        } catch (_: Exception) { null }
    }

    // ── Format URL Extraction ──────────────────────────────────────

    private fun extractUrlFromFormat(format: JSONObject): String? {
        val direct = format.optString("url")
        if (direct.isNotBlank()) return direct
        val cipher = format.optString("signatureCipher").ifBlank { format.optString("cipher") }
        if (cipher.isBlank()) return null
        val parts = parseQuery(cipher)
        val baseUrl = parts["url"] ?: return null
        val sig = parts["sig"] ?: parts["signature"]
        val sp = parts["sp"] ?: "signature"
        if (sig.isNullOrBlank()) return null
        return Uri.parse(baseUrl).buildUpon().appendQueryParameter(sp, sig).build().toString()
    }

    // ── Video ID Extraction ────────────────────────────────────────

    private fun extractVideoId(uri: Uri): String? {
        val host = uri.host?.lowercase() ?: return null
        if (host.contains("youtu.be")) {
            return uri.pathSegments.firstOrNull()?.takeIf { it.isNotBlank() }
        }
        if (host.contains("youtube.com")) {
            uri.getQueryParameter("v")?.takeIf { it.isNotBlank() }?.let { return it }
            val segs = uri.pathSegments
            if (segs.size >= 2 && segs[0] in listOf("embed", "shorts", "live", "v")) {
                return segs[1].takeIf { it.isNotBlank() }
            }
        }
        return null
    }

    // ── HTTP Utilities ─────────────────────────────────────────────

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
        .writeTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private fun executeRequest(request: Request): String? {
        return try {
            httpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "HTTP ${resp.code} for ${request.url}")
                    return null
                }
                resp.body?.string()
            }
        } catch (e: Exception) {
            Log.w(TAG, "HTTP request failed: ${request.url}", e)
            null
        }
    }

    /**
     * Send a HEAD request and return the Content-Type header, or null on failure.
     */
    private fun httpHead(url: String): String? {
        return try {
            val request = Request.Builder()
                .url(url)
                .head()
                .header("User-Agent", WEB_USER_AGENT)
                .build()
            httpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) null
                else resp.header("Content-Type")
            }
        } catch (e: Exception) {
            Log.w(TAG, "HEAD request failed: $url", e)
            null
        }
    }

    // ── String Utilities ───────────────────────────────────────────

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (pair in query.split('&')) {
            if (pair.isBlank()) continue
            val idx = pair.indexOf('=')
            val k = if (idx >= 0) pair.substring(0, idx) else pair
            val v = if (idx >= 0) pair.substring(idx + 1) else ""
            out[URLDecoder.decode(k, "UTF-8")] = URLDecoder.decode(v, "UTF-8")
        }
        return out
    }

    private fun findMatchingJsonBrace(text: String, start: Int): Int {
        if (start < 0 || start >= text.length || text[start] != '{') return -1
        var depth = 0; var inStr = false; var esc = false
        for (i in start until text.length) {
            val c = text[i]
            if (esc) { esc = false; continue }
            if (inStr) { when (c) { '\\' -> esc = true; '"' -> inStr = false }; continue }
            when (c) { '"' -> inStr = true; '{' -> depth++; '}' -> { depth--; if (depth == 0) return i } }
        }
        return -1
    }

    // ── Regex Patterns for HTML Extraction ─────────────────────────

    private val OG_VIDEO_REGEXES = listOf(
        Regex("""<meta[^>]+property\s*=\s*["']og:video(?::(?:secure_)?url)?["'][^>]+content\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE),
        Regex("""<meta[^>]+content\s*=\s*["']([^"']+)["'][^>]+property\s*=\s*["']og:video(?::(?:secure_)?url)?["']""", RegexOption.IGNORE_CASE)
    )
    private val TWITTER_STREAM_REGEX =
        Regex("""<meta[^>]+name\s*=\s*["']twitter:player:stream["'][^>]+content\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)

    private val SOURCE_TAG_REGEX =
        Regex("""<source[^>]+src\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val VIDEO_SRC_REGEX =
        Regex("""<video[^>]+src\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)

    /** Matches .m3u8 URLs inside quotes (single or double) or as JSON values. */
    private val HLS_URL_REGEX =
        Regex("""["']?(https?://[^\s"'<>]+\.m3u8[^\s"'<>]*)["']?""")
    private val DASH_URL_REGEX =
        Regex("""["']?(https?://[^\s"'<>]+\.mpd[^\s"'<>]*)["']?""")
    private val MP4_URL_REGEX =
        Regex("""["'](https?://[^\s"'<>]+\.mp4[^\s"'<>]*)["']""")

    // ── Constants ──────────────────────────────────────────────────

    private const val TAG = "VideoResolver"
    private const val INNERTUBE_BASE_URL = "https://www.youtube.com/youtubei/v1/player"
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    private const val CONNECT_TIMEOUT_S = 8L
    private const val READ_TIMEOUT_S = 10L
    private const val QUICK_SCAN_CHARS = 200_000
    private const val PREFERRED_HEIGHT = 720

    private const val WEB_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    private val DIRECT_VIDEO_EXTENSIONS = listOf(
        ".mp4", ".m3u8", ".mpd", ".mkv", ".webm", ".avi", ".mov", ".ts", ".flv"
    )

    private val VIDEO_URL_KEYWORDS = listOf(
        ".mp4", ".m3u8", ".mpd", ".mkv", ".webm", ".ts", ".mov",
        "/video/", "/stream/", "/media/", "manifest", "playlist"
    )
}
