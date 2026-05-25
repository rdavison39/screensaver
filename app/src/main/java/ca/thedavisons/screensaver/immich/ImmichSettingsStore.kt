package ca.thedavisons.screensaver.immich

import android.content.Context
import ca.thedavisons.screensaver.immich.SlideshowTransitionMode

private const val IMMICH_PREFS = "immich_prefs"
private const val KEY_SERVER_URL = "immich_server_url"
private const val KEY_API_KEY = "immich_api_key"
private const val KEY_SELECTED_ALBUM_IDS = "immich_selected_album_ids"
private const val KEY_SHUFFLE = "immich_shuffle"
private const val KEY_INTERVAL_SECONDS = "immich_interval_seconds"
private const val KEY_TRANSITION_MODE = "immich_transition_mode"
private const val KEY_TRANSITION_MODES = "immich_transition_modes"

object ImmichSettingsStore {

    fun saveAuthConfig(context: Context, config: ImmichAuthConfig) {
        context.getSharedPreferences(IMMICH_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SERVER_URL, config.serverUrl.trim())
            .putString(KEY_API_KEY, config.apiKey.trim())
            .apply()
    }

    fun loadAuthConfig(context: Context): ImmichAuthConfig? {
        val prefs = context.getSharedPreferences(IMMICH_PREFS, Context.MODE_PRIVATE)
        val serverUrl = prefs.getString(KEY_SERVER_URL, null).orEmpty().trim()
        val apiKey = prefs.getString(KEY_API_KEY, null).orEmpty().trim()

        if (serverUrl.isBlank() || apiKey.isBlank()) {
            return null
        }

        return ImmichAuthConfig(serverUrl = serverUrl, apiKey = apiKey)
    }

    fun clearAuthConfig(context: Context) {
        context.getSharedPreferences(IMMICH_PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_SERVER_URL)
            .remove(KEY_API_KEY)
            .apply()
    }

    fun saveSlideshowSettings(context: Context, settings: ImmichSlideshowSettings) {
        val serializedAlbums = settings.selectedAlbumIds
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString(",")

        context.getSharedPreferences(IMMICH_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SELECTED_ALBUM_IDS, serializedAlbums)
            .putBoolean(KEY_SHUFFLE, settings.shuffle)
            .putInt(KEY_INTERVAL_SECONDS, settings.intervalSeconds.coerceIn(3, 120))
            .putString(
                KEY_TRANSITION_MODES,
                settings.enabledTransitions
                    .map { it.storageValue }
                    .sorted()
                    .joinToString(",")
            )
            .apply()
    }

    fun loadSlideshowSettings(context: Context): ImmichSlideshowSettings {
        val prefs = context.getSharedPreferences(IMMICH_PREFS, Context.MODE_PRIVATE)
        val selected = prefs.getString(KEY_SELECTED_ALBUM_IDS, "")
            .orEmpty()
            .split(',')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSet()

        val enabledTransitions = prefs.getString(KEY_TRANSITION_MODES, null)
            ?.split(',')
            ?.mapNotNull { raw ->
                val value = raw.trim()
                if (value.isBlank()) {
                    null
                } else {
                    SlideshowTransitionMode.values().firstOrNull { it.storageValue == value }
                }
            }
            ?.toSet()
            ?.takeIf { it.isNotEmpty() }
            ?: prefs.getString(KEY_TRANSITION_MODE, null)
                ?.let { setOf(SlideshowTransitionMode.fromStorageValue(it)) }
            ?: SlideshowTransitionMode.values().toSet()

        return ImmichSlideshowSettings(
            selectedAlbumIds = selected,
            shuffle = prefs.getBoolean(KEY_SHUFFLE, true),
            intervalSeconds = prefs.getInt(KEY_INTERVAL_SECONDS, 20).coerceIn(3, 120),
            enabledTransitions = enabledTransitions
        )
    }
}
