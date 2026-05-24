package ca.thedavisons.screensaver.immich

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ImmichApiClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
) {

    suspend fun ping(config: ImmichAuthConfig): Boolean = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${config.serverUrl.trimEnd('/')}/api/server-info")
            .header("x-api-key", config.apiKey)
            .get()
            .build()

        runCatching {
            client.newCall(request).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    suspend fun getAlbums(config: ImmichAuthConfig): List<ImmichAlbum> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${config.serverUrl.trimEnd('/')}/api/albums")
            .header("x-api-key", config.apiKey)
            .get()
            .build()

        runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@use emptyList()
                }

                val body = response.body?.string().orEmpty()
                val json = JSONArray(body.ifBlank { "[]" })
                buildList {
                    for (i in 0 until json.length()) {
                        val item = json.optJSONObject(i) ?: continue
                        val id = item.optString("id")
                        val name = item.optString("albumName")
                        if (id.isBlank() || name.isBlank()) {
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
            }
        }.getOrElse { emptyList() }
    }

    suspend fun getAlbumAssetsPage(
        config: ImmichAuthConfig,
        albumId: String,
        page: Int,
        size: Int
    ): ImmichAssetPage = withContext(Dispatchers.IO) {
        // Immich album detail endpoint currently returns all assets for the album.
        // We page client-side to keep app memory bounded in downstream callers.
        val request = Request.Builder()
            .url("${config.serverUrl.trimEnd('/')}/api/albums/$albumId")
            .header("x-api-key", config.apiKey)
            .get()
            .build()

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
