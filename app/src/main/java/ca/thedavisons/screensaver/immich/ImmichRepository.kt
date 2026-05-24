package ca.thedavisons.screensaver.immich

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.random.Random

class ImmichRepository(
    private val apiClient: ImmichApiClient = ImmichApiClient()
) {

    suspend fun isConfiguredAndReachable(context: Context): Boolean {
        val config = ImmichSettingsStore.loadAuthConfig(context) ?: return false
        return apiClient.ping(config)
    }

    suspend fun fetchAlbums(context: Context): List<ImmichAlbum> {
        val config = ImmichSettingsStore.loadAuthConfig(context) ?: return emptyList()
        return apiClient.getAlbums(config)
    }

    suspend fun fetchSlideshowUrls(
        context: Context,
        albumId: String,
        page: Int,
        pageSize: Int,
        useShuffle: Boolean
    ): List<String> = withContext(Dispatchers.IO) {
        val config = ImmichSettingsStore.loadAuthConfig(context) ?: return@withContext emptyList()
        val assetPage = apiClient.getAlbumAssetsPage(
            config = config,
            albumId = albumId,
            page = page,
            size = pageSize
        )

        val images = assetPage.items
            .filter { it.type.equals("IMAGE", ignoreCase = true) }
            .map { apiClient.buildOriginalUrl(config, it.id) }

        if (!useShuffle || images.size < 2) {
            return@withContext images
        }

        images.shuffled(Random(System.currentTimeMillis()))
    }
}
