package ca.thedavisons.screensaver.immich

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

data class ImmichPingResult(
    val success: Boolean,
    val message: String,
    val resolvedServerUrl: String? = null
)

data class ImmichAlbumsResult(
    val albums: List<ImmichAlbum>,
    val message: String? = null,
    val resolvedServerUrl: String? = null
)

class ImmichApiClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
) {

    private fun baseCandidates(serverUrl: String): List<String> {
        val direct = serverUrl.trimEnd('/')
        val origin = runCatching {
            val parsed = java.net.URI(direct)
            if (parsed.host.isNullOrBlank()) {
                null
            } else {
                java.net.URI(
                    parsed.scheme,
                    null,
                    parsed.host,
                    parsed.port,
                    null,
                    null,
                    null
                ).toString().trimEnd('/')
            }
        }.getOrNull()

        return listOfNotNull(direct, origin).distinct()
    }

    private fun isHtmlResponse(contentType: String?, body: String): Boolean {
        val media = contentType.orEmpty().lowercase()
        if (media.contains("text/html")) {
            return true
        }

        val trimmed = body.trimStart()
        return trimmed.startsWith("<!doctype html", ignoreCase = true) ||
            trimmed.startsWith("<html", ignoreCase = true)
    }

    private fun buildGetRequest(url: String, apiKey: String): Request? {
        return runCatching {
            Request.Builder()
                .url(url)
                .header("x-api-key", apiKey)
                .header("Cache-Control", "no-cache")
                .header("Pragma", "no-cache")
                .get()
                .build()
        }.getOrNull()
    }

    suspend fun ping(config: ImmichAuthConfig): Boolean = withContext(Dispatchers.IO) {
        pingWithDetails(config).success
    }

    suspend fun pingWithDetails(config: ImmichAuthConfig): ImmichPingResult = withContext(Dispatchers.IO) {
        var saw404 = false
        var sawServerResponse = false
        var sawHtml = false

        for (baseUrl in baseCandidates(config.serverUrl)) {
            val endpoints = listOf(
                "$baseUrl/api/users/me",
                "$baseUrl/api/albums",
                "$baseUrl/api/server-info",
                "$baseUrl/api/server-info/ping"
            )

            for (url in endpoints) {
                val request = buildGetRequest(url = url, apiKey = config.apiKey)
                    ?: return@withContext ImmichPingResult(false, "Invalid server URL")

                val result = runCatching {
                    client.newCall(request).execute().use { response ->
                        val body = response.body?.string().orEmpty()
                        when {
                            response.isSuccessful && !isHtmlResponse(response.header("content-type"), body) -> {
                                ImmichPingResult(true, "Connected", resolvedServerUrl = baseUrl)
                            }
                            response.isSuccessful -> {
                                sawServerResponse = true
                                sawHtml = true
                                null
                            }
                            response.code == 401 || response.code == 403 -> {
                                sawServerResponse = true
                                ImmichPingResult(false, "Server reachable, but API key is invalid or revoked")
                            }
                            response.code == 404 -> {
                                sawServerResponse = true
                                saw404 = true
                                null
                            }
                            else -> {
                                sawServerResponse = true
                                ImmichPingResult(
                                    false,
                                    "Server reachable, but returned HTTP ${response.code}"
                                )
                            }
                        }
                    }
                }

                val immediate = result.getOrElse { error ->
                    return@withContext ImmichPingResult(
                        false,
                        when (error) {
                            is UnknownHostException -> "Host not found. Check IP/hostname"
                            is ConnectException -> "Cannot connect to server/port"
                            is SocketTimeoutException -> "Connection timed out"
                            is SSLException -> "TLS/SSL error. Check https certificate or use http"
                            else -> "Connection error: ${error.message ?: "unknown"}"
                        }
                    )
                }

                if (immediate != null) {
                    return@withContext immediate
                }
            }
        }

        if (saw404) {
            return@withContext ImmichPingResult(
                false,
                "Server reachable, but Immich API path was not found. Use the Immich base URL (example: https://host or https://host/immich)"
            )
        }

        if (sawServerResponse) {
            if (sawHtml) {
                return@withContext ImmichPingResult(
                    false,
                    "Server returned web HTML instead of Immich API JSON. Try the server origin URL"
                )
            }
            return@withContext ImmichPingResult(false, "Server responded, but connection test failed")
        }

        ImmichPingResult(false, "Connection failed")
    }

    suspend fun getAlbums(config: ImmichAuthConfig): List<ImmichAlbum> = withContext(Dispatchers.IO) {
        getAlbumsWithDetails(config).albums
    }

    suspend fun getAlbumsWithDetails(config: ImmichAuthConfig): ImmichAlbumsResult = withContext(Dispatchers.IO) {
        var sawHtml = false
        var saw404 = false

        for (baseUrl in baseCandidates(config.serverUrl)) {
            val request = buildGetRequest(
                url = "$baseUrl/api/albums",
                apiKey = config.apiKey
            ) ?: return@withContext ImmichAlbumsResult(emptyList(), "Invalid server URL")

            val result = runCatching {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@use when (response.code) {
                            401, 403 -> ImmichAlbumsResult(
                                emptyList(),
                                "API key was rejected while loading albums"
                            )
                            404 -> {
                                saw404 = true
                                null
                            }
                            else -> ImmichAlbumsResult(
                                emptyList(),
                                "Album request failed with HTTP ${response.code}"
                            )
                        }
                    }

                    val body = response.body?.string().orEmpty()
                    if (body.isBlank()) {
                        return@use ImmichAlbumsResult(emptyList(), "Album response was empty")
                    }

                    if (isHtmlResponse(response.header("content-type"), body)) {
                        sawHtml = true
                        return@use null
                    }

                    val normalized = body.trim()
                    val jsonArray = when {
                        normalized.startsWith("[") -> JSONArray(normalized)
                        normalized.startsWith("{") -> {
                            val root = JSONObject(normalized)
                            root.optJSONArray("albums")
                                ?: root.optJSONArray("data")
                                ?: root.optJSONObject("data")?.optJSONArray("albums")
                                ?: root.optJSONObject("data")?.optJSONArray("items")
                                ?: root.optJSONArray("items")
                                ?: root.optJSONArray("results")
                                ?: JSONArray()
                        }
                        else -> {
                            return@use ImmichAlbumsResult(
                                emptyList(),
                                "Unexpected album response format"
                            )
                        }
                    }

                    val parsed = buildList {
                        for (i in 0 until jsonArray.length()) {
                            val item = jsonArray.optJSONObject(i) ?: continue
                            val id = item.optString("id")
                            val name = item.optString("albumName")
                                .ifBlank { item.optString("name") }
                                .ifBlank { "Untitled Album" }
                            if (id.isBlank()) {
                                continue
                            }

                            add(
                                ImmichAlbum(
                                    id = id,
                                    albumName = name,
                                    assetCount = item.optInt("assetCount", 0),
                                    isShared = item.optBoolean("shared", false)
                                )
                            )
                        }
                    }

                    if (parsed.isEmpty()) {
                        ImmichAlbumsResult(
                            emptyList(),
                            "Connected, but no albums are visible to this API key"
                        )
                    } else {
                        ImmichAlbumsResult(parsed, resolvedServerUrl = baseUrl)
                    }
                }
            }

            val resolved = result.getOrElse { error ->
                return@withContext ImmichAlbumsResult(
                    emptyList(),
                    "Failed to load albums: ${error.message ?: "unknown error"}"
                )
            }

            if (resolved != null) {
                return@withContext resolved
            }
        }

        if (sawHtml) {
            return@withContext ImmichAlbumsResult(
                emptyList(),
                "Server returned web HTML for albums API. Using server origin URL may fix this"
            )
        }

        if (saw404) {
            return@withContext ImmichAlbumsResult(
                emptyList(),
                "Albums API path was not found for this server URL"
            )
        }

        ImmichAlbumsResult(emptyList(), "Unable to load albums")
    }

    suspend fun getAlbumAssetsPage(
        config: ImmichAuthConfig,
        albumId: String,
        page: Int,
        size: Int
    ): ImmichAssetPage = withContext(Dispatchers.IO) {
        // Immich album detail endpoint currently returns all assets for the album.
        // We page client-side to keep app memory bounded in downstream callers.
        val request = buildGetRequest(
            url = "${config.serverUrl.trimEnd('/')}/api/albums/$albumId",
            apiKey = config.apiKey
        ) ?: return@withContext ImmichAssetPage(emptyList(), page, size, hasNext = false)

        runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@use ImmichAssetPage(emptyList(), page, size, hasNext = false)
                }

                val body = response.body?.string().orEmpty()
                val root = JSONObject(body.ifBlank { "{}" })
                val assetsJson = root.optJSONArray("assets") ?: JSONArray()
                val allAssets = mutableListOf<ImmichAsset>()

                for (i in 0 until assetsJson.length()) {
                    val item = assetsJson.optJSONObject(i) ?: continue
                    val id = item.optString("id")
                    if (id.isBlank()) {
                        continue
                    }

                    allAssets.add(
                        ImmichAsset(
                            id = id,
                            type = item.optString("type", "IMAGE"),
                            originalFileName = item.optString("originalFileName").ifBlank { null },
                            resizePath = item.optString("resizePath").ifBlank { null },
                            previewPath = item.optString("previewPath").ifBlank { null }
                        )
                    )
                }

                val safePage = page.coerceAtLeast(0)
                val safeSize = size.coerceIn(1, 500)
                val from = (safePage * safeSize).coerceAtMost(allAssets.size)
                val to = (from + safeSize).coerceAtMost(allAssets.size)

                ImmichAssetPage(
                    items = if (from < to) allAssets.subList(from, to) else emptyList(),
                    page = safePage,
                    size = safeSize,
                    hasNext = to < allAssets.size
                )
            }
        }.getOrElse {
            ImmichAssetPage(emptyList(), page, size, hasNext = false)
        }
    }

    fun buildThumbnailUrl(config: ImmichAuthConfig, assetId: String): String {
        return "${config.serverUrl.trimEnd('/')}/api/assets/$assetId/thumbnail"
    }

    fun buildOriginalUrl(config: ImmichAuthConfig, assetId: String): String {
        return "${config.serverUrl.trimEnd('/')}/api/assets/$assetId/original"
    }
}
