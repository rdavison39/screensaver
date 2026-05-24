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

    suspend fun fetchSelectedAlbumSlideshowUrls(
        context: Context,
        useShuffle: Boolean,
        pageSize: Int = 200
    ): List<String> = withContext(Dispatchers.IO) {
        val settings = ImmichSettingsStore.loadSlideshowSettings(context)
        val albumIds = settings.selectedAlbumIds.toList()
        if (albumIds.isEmpty()) {
            return@withContext emptyList()
        }

        val config = ImmichSettingsStore.loadAuthConfig(context) ?: return@withContext emptyList()
        val collected = mutableListOf<String>()

        for (albumId in albumIds) {
            val page = apiClient.getAlbumAssetsPage(
                config = config,
                albumId = albumId,
                page = 0,
                size = pageSize
            )

            collected.addAll(
                page.items
                    .filter { it.type.equals("IMAGE", ignoreCase = true) }
                    .map { apiClient.buildOriginalUrl(config, it.id) }
            )
        }

        val distinctUrls = collected.distinct()
        if (!useShuffle || distinctUrls.size < 2) {
            return@withContext distinctUrls
        }

        distinctUrls.shuffled(Random(System.currentTimeMillis()))
    }

    fun hasConfiguredAuth(context: Context): Boolean {
        return ImmichSettingsStore.loadAuthConfig(context) != null
    }
}
