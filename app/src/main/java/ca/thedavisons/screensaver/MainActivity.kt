package ca.thedavisons.screensaver

import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import ca.thedavisons.screensaver.immich.ImmichAuthConfig
import ca.thedavisons.screensaver.immich.ImmichAlbum
import ca.thedavisons.screensaver.immich.ImmichRepository
import ca.thedavisons.screensaver.immich.ImmichSettingsStore
import ca.thedavisons.screensaver.immich.SlideshowTransitionMode
import coil.load
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var playbackSettingsText: TextView
    private lateinit var previewImage: ImageView
    private lateinit var previewStatusText: TextView
    private lateinit var immichStatusText: TextView
    private lateinit var immichServerUrlInput: EditText
    private lateinit var immichApiKeyInput: EditText
    private lateinit var shuffleCheckBox: CheckBox
    private lateinit var immichAlbumListContainer: LinearLayout
    private lateinit var saveImmichSettingsButton: Button

    private val isTvDevice: Boolean by lazy {
        packageManager.hasSystemFeature("android.software.leanback")
    }

    private val immichRepository = ImmichRepository()
    private val immichAlbumCheckboxes = linkedMapOf<String, CheckBox>()
    private val transitionModeCheckboxes = linkedMapOf<SlideshowTransitionMode, CheckBox>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(createContentView())
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        if (isTvDevice) {
            saveImmichSettingsButton.post {
                saveImmichSettingsButton.requestFocus()
            }
        }
    }

    private fun createContentView(): ScrollView {
        val root = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = dp(20)
            setPadding(padding, padding, padding, padding)
        }

        val title = TextView(this).apply {
            text = "Immich TV Setup v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
        }

        val immichSectionTitle = TextView(this).apply {
            text = "Immich Setup"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        }

        immichStatusText = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            text = "Immich: not configured"
        }

        immichServerUrlInput = EditText(this).apply {
            hint = "Immich server URL (e.g. https://192.168.0.50:2283)"
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine = true
        }

        immichApiKeyInput = EditText(this).apply {
            hint = "Immich API key"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSingleLine = true
        }

        saveImmichSettingsButton = Button(this).apply {
            text = "Save Immich Settings"
            setOnClickListener { saveImmichSettings() }
        }

        val testImmichConnectionButton = Button(this).apply {
            text = "Test Immich Connection"
            setOnClickListener { testImmichConnection() }
        }

        val loadImmichAlbumsButton = Button(this).apply {
            text = "Load Immich Albums"
            setOnClickListener { loadImmichAlbums() }
        }

        val saveImmichAlbumSelectionButton = Button(this).apply {
            text = "Save Selected Immich Albums"
            setOnClickListener { saveSelectedImmichAlbums() }
        }

        immichAlbumListContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }

        shuffleCheckBox = CheckBox(this).apply {
            text = "Shuffle playback order"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        }

        statusText = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        }

        playbackSettingsText = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        }

        previewStatusText = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            text = "Preview: not loaded"
        }

        previewImage = ImageView(this).apply {
            val previewSize = dp(280)
            layoutParams = LinearLayout.LayoutParams(previewSize, previewSize).apply {
                topMargin = dp(8)
                bottomMargin = dp(8)
            }
            contentDescription = "Preview image from selected Immich albums"
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
        }

        val previewButton = Button(this).apply {
            text = "Preview Photo From Selected Immich Albums"
            setOnClickListener { previewSelectedImmichSource() }
        }

        val intervalLabel = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            text = "Slideshow interval"
        }

        val intervalButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, dp(6))
        }

        listOf(2, 3, 5, 10, 15, 30).forEach { seconds ->
            val button = Button(this).apply {
                text = "${seconds}s"
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(6)
                }
                setOnClickListener { setSlideshowIntervalSeconds(seconds) }
            }
            intervalButtons.addView(button)
        }

        val customIntervalRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, dp(4))
        }

        val customIntervalInput = EditText(this).apply {
            hint = "Custom seconds"
            inputType = InputType.TYPE_CLASS_NUMBER
            isSingleLine = true
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(6)
            }
        }

        val setCustomIntervalButton = Button(this).apply {
            text = "Set"
            setOnClickListener {
                val customSeconds = customIntervalInput.text?.toString()?.toIntOrNull()
                if (customSeconds == null || customSeconds < 2 || customSeconds > 120) {
                    immichStatusText.text = "Immich: enter a valid interval between 2 and 120 seconds"
                } else {
                    setSlideshowIntervalSeconds(customSeconds)
                }
            }
        }

        customIntervalRow.addView(customIntervalInput)
        customIntervalRow.addView(setCustomIntervalButton)

        val transitionLabel = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            text = "Transitions to use"
        }

        val transitionHint = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            text = "The screensaver randomly uses the checked transitions"
        }

        val transitionButtons = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }

        val transitionBulkButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, dp(4))
        }

        val selectAllTransitionsButton = Button(this).apply {
            text = "Select All"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(6)
            }
            setOnClickListener { selectAllTransitions() }
        }

        val clearAllTransitionsButton = Button(this).apply {
            text = "Clear All"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { clearAllTransitions() }
        }

        transitionBulkButtons.addView(selectAllTransitionsButton)
        transitionBulkButtons.addView(clearAllTransitionsButton)

        val selectedTransitions = ImmichSettingsStore.loadSlideshowSettings(this).enabledTransitions
        SlideshowTransitionMode.values().forEach { mode ->
            val checkbox = CheckBox(this).apply {
                text = mode.displayName
                isChecked = selectedTransitions.contains(mode)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setOnCheckedChangeListener { _, _ ->
                    persistSelectedTransitions(showStatus = true)
                }
            }
            transitionModeCheckboxes[mode] = checkbox
            transitionButtons.addView(checkbox)
        }

        val clearButton = Button(this).apply {
            text = "Clear Immich Setup"
            setOnClickListener {
                ImmichSettingsStore.clearAuthConfig(this@MainActivity)
                ImmichSettingsStore.saveSlideshowSettings(
                    context = this@MainActivity,
                    settings = ImmichSettingsStore.loadSlideshowSettings(this@MainActivity).copy(
                        selectedAlbumIds = emptySet(),
                        enabledTransitions = SlideshowTransitionMode.values().toSet()
                    )
                )
                immichAlbumCheckboxes.values.forEach { it.isChecked = false }
                transitionModeCheckboxes.values.forEach { it.isChecked = true }
                previewStatusText.text = "Preview: not loaded"
                previewImage.setImageDrawable(null)
                previewImage.visibility = View.GONE
                refreshStatus()
            }
        }

        val spacerTop = TextView(this).apply {
            text = "\n"
        }

        val spacerBottom = TextView(this).apply {
            text = "\n"
        }

        ImmichSettingsStore.loadAuthConfig(this)?.let { config ->
            immichServerUrlInput.setText(config.serverUrl)
            immichApiKeyInput.setText(config.apiKey)
        }

        content.addView(title)
        content.addView(spacerTop)
        content.addView(immichSectionTitle)
        content.addView(immichStatusText)
        content.addView(immichServerUrlInput)
        content.addView(immichApiKeyInput)
        content.addView(saveImmichSettingsButton)
        content.addView(testImmichConnectionButton)
        content.addView(loadImmichAlbumsButton)
        content.addView(saveImmichAlbumSelectionButton)
        content.addView(immichAlbumListContainer)
        content.addView(shuffleCheckBox)
        content.addView(TextView(this).apply { text = "\n" })
        content.addView(statusText)
        content.addView(playbackSettingsText)
        content.addView(previewStatusText)
        content.addView(previewImage)
        content.addView(spacerBottom)
        content.addView(previewButton)
        content.addView(intervalLabel)
        content.addView(intervalButtons)
        content.addView(customIntervalRow)
        content.addView(transitionLabel)
        content.addView(transitionHint)
        content.addView(transitionBulkButtons)
        content.addView(transitionButtons)
        content.addView(clearButton)

        root.addView(content)
        return root
    }

    private fun refreshStatus() {
        val immichAuth = ImmichSettingsStore.loadAuthConfig(this)
        val settings = ImmichSettingsStore.loadSlideshowSettings(this)
        immichStatusText.text = if (immichAuth == null) {
            "Immich: not configured"
        } else {
            "Immich: configured for ${immichAuth.serverUrl}"
        }

        statusText.text = "Selected albums: ${settings.selectedAlbumIds.size}"
        val transitionSummary = settings.enabledTransitions
            .sortedBy { it.displayName }
            .joinToString { it.displayName }
        playbackSettingsText.text = "Playback: ${if (settings.shuffle) "random order" else "fixed order"} | Change every ${settings.intervalSeconds} seconds | Transitions: $transitionSummary"
        shuffleCheckBox.isChecked = settings.shuffle
        restoreTransitionSelectionState(settings.enabledTransitions)

        restoreImmichAlbumSelectionState()
    }

    private fun saveImmichSettings(): Boolean {
        val serverUrlInput = immichServerUrlInput.text?.toString().orEmpty()
        val serverUrl = normalizeServerUrl(serverUrlInput)
        val apiKey = immichApiKeyInput.text?.toString().orEmpty().trim()

        if (serverUrl == null) {
            immichStatusText.text = "Immich: enter a valid server URL (http:// or https://)"
            return false
        }

        if (apiKey.isBlank()) {
            immichStatusText.text = "Immich: API key is required"
            return false
        }

        ImmichSettingsStore.saveAuthConfig(
            context = this,
            config = ImmichAuthConfig(serverUrl = serverUrl, apiKey = apiKey)
        )
        immichServerUrlInput.setText(serverUrl)
        immichStatusText.text = "Immich: settings saved"
        return true
    }

    private fun normalizeServerUrl(raw: String): String? {
        val trimmed = raw.trim().trimEnd('/')
        if (trimmed.isBlank()) {
            return null
        }

        val withScheme = if (
            trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) {
            trimmed
        } else {
            "http://$trimmed"
        }

        val parsed = runCatching { java.net.URI(withScheme) }.getOrNull() ?: return null
        val scheme = parsed.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            return null
        }

        if (parsed.host.isNullOrBlank()) {
            return null
        }

        val basePath = parsed.path
            .orEmpty()
            .trim()
            .trimEnd('/')
            .let { path ->
                when {
                    path.endsWith("/auth/login", ignoreCase = true) -> {
                        path.removeSuffix("/auth/login")
                    }
                    path.endsWith("/login", ignoreCase = true) -> {
                        path.removeSuffix("/login")
                    }
                    else -> path
                }
            }
            .let { if (it.isBlank() || it == "/") "" else it }

        return runCatching {
            java.net.URI(
                scheme,
                null,
                parsed.host,
                parsed.port,
                if (basePath.isBlank()) null else basePath,
                null,
                null
            ).toString().trimEnd('/')
        }.getOrNull()
    }

    private fun testImmichConnection() {
        if (!saveImmichSettings()) {
            return
        }

        lifecycleScope.launch {
            immichStatusText.text = "Immich: testing connection..."
            val result = immichRepository.testConnection(this@MainActivity)
            if (result.success && !result.resolvedServerUrl.isNullOrBlank()) {
                val current = ImmichSettingsStore.loadAuthConfig(this@MainActivity)
                if (current != null && current.serverUrl != result.resolvedServerUrl) {
                    ImmichSettingsStore.saveAuthConfig(
                        context = this@MainActivity,
                        config = current.copy(serverUrl = result.resolvedServerUrl)
                    )
                    immichServerUrlInput.setText(result.resolvedServerUrl)
                }
            }
            immichStatusText.text = if (result.success) {
                "Immich: connection successful"
            } else {
                "Immich: connection failed (${result.message})"
            }
        }
    }

    private fun loadImmichAlbums() {
        if (!saveImmichSettings()) {
            return
        }

        lifecycleScope.launch {
            immichStatusText.text = "Immich: loading albums..."
            val result = immichRepository.fetchAlbumsDetailed(this@MainActivity)
            val albums = result.albums

            if (!result.resolvedServerUrl.isNullOrBlank()) {
                val current = ImmichSettingsStore.loadAuthConfig(this@MainActivity)
                if (current != null && current.serverUrl != result.resolvedServerUrl) {
                    ImmichSettingsStore.saveAuthConfig(
                        context = this@MainActivity,
                        config = current.copy(serverUrl = result.resolvedServerUrl)
                    )
                    immichServerUrlInput.setText(result.resolvedServerUrl)
                }
            }

            renderImmichAlbumPicker(albums)

            immichStatusText.text = if (albums.isEmpty()) {
                "Immich: no albums found (${result.message ?: "unknown reason"})"
            } else {
                "Immich: loaded ${albums.size} album(s)"
            }
        }
    }

    private fun renderImmichAlbumPicker(albums: List<ImmichAlbum>) {
        immichAlbumListContainer.removeAllViews()
        immichAlbumCheckboxes.clear()

        if (albums.isEmpty()) {
            immichAlbumListContainer.addView(TextView(this).apply {
                text = "No Immich albums to display"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            })
            return
        }

        val selected = ImmichSettingsStore.loadSlideshowSettings(this).selectedAlbumIds

        albums.sortedBy { it.albumName.lowercase() }.forEach { album ->
            val checkbox = CheckBox(this).apply {
                text = "${album.albumName} (${album.assetCount})"
                isChecked = selected.contains(album.id)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setOnCheckedChangeListener { _, _ ->
                    persistSelectedImmichAlbums(showStatus = true)
                }
            }
            immichAlbumCheckboxes[album.id] = checkbox
            immichAlbumListContainer.addView(checkbox)
        }
    }

    private fun restoreImmichAlbumSelectionState() {
        if (immichAlbumCheckboxes.isEmpty()) {
            return
        }

        val selected = ImmichSettingsStore.loadSlideshowSettings(this).selectedAlbumIds
        immichAlbumCheckboxes.forEach { (albumId, checkbox) ->
            checkbox.isChecked = selected.contains(albumId)
        }
    }

    private fun saveSelectedImmichAlbums() {
        persistSelectedImmichAlbums(showStatus = true)
        refreshStatus()
    }

    private fun restoreTransitionSelectionState(selectedModes: Set<SlideshowTransitionMode>) {
        if (transitionModeCheckboxes.isEmpty()) {
            return
        }

        transitionModeCheckboxes.forEach { (mode, checkbox) ->
            checkbox.setOnCheckedChangeListener(null)
            checkbox.isChecked = selectedModes.contains(mode)
            checkbox.setOnCheckedChangeListener { _, _ ->
                persistSelectedTransitions(showStatus = true)
            }
        }
    }

    private fun persistSelectedTransitions(showStatus: Boolean) {
        val enabledTransitions = transitionModeCheckboxes
            .filterValues { it.isChecked }
            .keys
            .ifEmpty { setOf(SlideshowTransitionMode.CROSSFADE) }

        if (transitionModeCheckboxes.values.none { it.isChecked }) {
            transitionModeCheckboxes[SlideshowTransitionMode.CROSSFADE]?.apply {
                setOnCheckedChangeListener(null)
                isChecked = true
                setOnCheckedChangeListener { _, _ ->
                    persistSelectedTransitions(showStatus = true)
                }
            }
        }

        val existing = ImmichSettingsStore.loadSlideshowSettings(this)
        ImmichSettingsStore.saveSlideshowSettings(
            context = this,
            settings = existing.copy(
                enabledTransitions = enabledTransitions,
                shuffle = shuffleCheckBox.isChecked
            )
        )

        if (showStatus) {
            immichStatusText.text = "Immich: saved ${enabledTransitions.size} transition(s)"
        }
    }

    private fun selectAllTransitions() {
        transitionModeCheckboxes.values.forEach { checkbox ->
            checkbox.setOnCheckedChangeListener(null)
            checkbox.isChecked = true
            checkbox.setOnCheckedChangeListener { _, _ ->
                persistSelectedTransitions(showStatus = true)
            }
        }

        persistSelectedTransitions(showStatus = true)
        refreshStatus()
    }

    private fun clearAllTransitions() {
        transitionModeCheckboxes.values.forEach { checkbox ->
            checkbox.setOnCheckedChangeListener(null)
            checkbox.isChecked = false
            checkbox.setOnCheckedChangeListener { _, _ ->
                persistSelectedTransitions(showStatus = true)
            }
        }

        persistSelectedTransitions(showStatus = true)
        immichStatusText.text = "Immich: cleared transitions (Crossfade kept as default)"
        refreshStatus()
    }

    private fun persistSelectedImmichAlbums(showStatus: Boolean) {
        val selectedIds = immichAlbumCheckboxes
            .filterValues { it.isChecked }
            .keys
            .toSet()

        val existing = ImmichSettingsStore.loadSlideshowSettings(this)
        ImmichSettingsStore.saveSlideshowSettings(
            context = this,
            settings = existing.copy(
                selectedAlbumIds = selectedIds,
                shuffle = shuffleCheckBox.isChecked
            )
        )

        if (showStatus) {
            immichStatusText.text = "Immich: saved ${selectedIds.size} selected album(s)"
        }
    }

    private fun previewSelectedImmichSource() {
        lifecycleScope.launch {
            val settings = ImmichSettingsStore.loadSlideshowSettings(this@MainActivity)
            if (settings.selectedAlbumIds.isEmpty()) {
                previewStatusText.text = "Preview: no selected Immich albums yet"
                previewImage.setImageDrawable(null)
                previewImage.visibility = View.GONE
                return@launch
            }

            previewStatusText.text = "Preview: loading from selected Immich albums..."

            val urls = immichRepository.fetchSelectedAlbumSlideshowUrls(
                context = this@MainActivity,
                useShuffle = settings.shuffle
            )
            val firstUrl = urls.firstOrNull()

            if (firstUrl.isNullOrBlank()) {
                previewStatusText.text = "Preview: no images returned by Immich"
                previewImage.setImageDrawable(null)
                previewImage.visibility = View.GONE
                return@launch
            }

            previewStatusText.text = "Preview: loaded ${urls.size} photo(s), showing first"
            previewImage.visibility = View.VISIBLE
            val apiKey = ImmichSettingsStore.loadAuthConfig(this@MainActivity)?.apiKey.orEmpty()
            if (apiKey.isNotBlank()) {
                previewImage.load(firstUrl) {
                    addHeader("x-api-key", apiKey)
                }
            } else {
                previewImage.load(firstUrl)
            }
        }
    }

    private fun setSlideshowIntervalSeconds(seconds: Int) {
        val existing = ImmichSettingsStore.loadSlideshowSettings(this)
        ImmichSettingsStore.saveSlideshowSettings(
            context = this,
            settings = existing.copy(
                intervalSeconds = seconds,
                shuffle = shuffleCheckBox.isChecked
            )
        )
        immichStatusText.text = "Immich: slideshow interval set to ${seconds} seconds"
        refreshStatus()
    }

    private fun dp(value: Int): Int {
        val density = resources.displayMetrics.density
        return (value * density).toInt()
    }
}