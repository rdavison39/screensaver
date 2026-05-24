package ca.thedavisons.screensaver.immich

data class ImmichAuthConfig(
    val serverUrl: String,
    val apiKey: String
)

data class ImmichAlbum(
    val id: String,
    val albumName: String,
    val assetCount: Int,
    val isShared: Boolean
)

data class ImmichAsset(
    val id: String,
    val type: String,
    val originalFileName: String?,
    val resizePath: String?,
    val previewPath: String?
)

data class ImmichAssetPage(
    val items: List<ImmichAsset>,
    val page: Int,
    val size: Int,
    val hasNext: Boolean
)

data class ImmichSlideshowSettings(
    val selectedAlbumIds: Set<String>,
    val shuffle: Boolean,
    val intervalSeconds: Int
)
