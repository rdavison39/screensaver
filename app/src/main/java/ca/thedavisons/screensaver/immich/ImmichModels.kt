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

enum class SlideshowTransitionMode(
    val storageValue: String,
    val displayName: String
) {
    CROSSFADE("crossfade", "Crossfade"),
    SLIDE_LEFT("slide_left", "Slide Left"),
    SLIDE_RIGHT("slide_right", "Slide Right"),
    SLIDE_UP("slide_up", "Slide Up"),
    SLIDE_DOWN("slide_down", "Slide Down"),
    CENTER_EXPAND("center_expand", "Center Expand"),
    PUSH_LEFT("push_left", "Push Left"),
    PUSH_RIGHT("push_right", "Push Right"),
    ZOOM_FADE("zoom_fade", "Zoom Fade");

    companion object {
        fun fromStorageValue(value: String?): SlideshowTransitionMode {
            return values().firstOrNull { it.storageValue == value } ?: CROSSFADE
        }
    }
}

data class ImmichSlideshowSettings(
    val selectedAlbumIds: Set<String>,
    val shuffle: Boolean,
    val intervalSeconds: Int,
    val enabledTransitions: Set<SlideshowTransitionMode> = SlideshowTransitionMode.values().toSet()
)
