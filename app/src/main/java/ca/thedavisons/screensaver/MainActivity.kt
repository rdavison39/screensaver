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

        listOf(5, 10, 20, 30, 60).forEach { seconds ->
            val button = Button(this).apply {
                text = "${seconds}s"
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(6)
                }
                setOnClickListener { setSlideshowIntervalSeconds(seconds) }
            }
            intervalButtons.addView(button)
        }

        val clearButton = Button(this).apply {
            text = "Clear Immich Setup"
            setOnClickListener {
                ImmichSettingsStore.clearAuthConfig(this@MainActivity)
                ImmichSettingsStore.saveSlideshowSettings(
                    context = this@MainActivity,
                    settings = ImmichSettingsStore.loadSlideshowSettings(this@MainActivity).copy(
                        selectedAlbumIds = emptySet()
                    )
                )
                immichAlbumCheckboxes.values.forEach { it.isChecked = false }
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
        playbackSettingsText.text = "Playback: ${if (settings.shuffle) "random order" else "fixed order"} | Change every ${settings.intervalSeconds} seconds"
        shuffleCheckBox.isChecked = settings.shuffle

        restoreImmichAlbumSelectionState()
    }

    private fun saveImmichSettings(): Boolean {
        val serverUrl = immichServerUrlInput.text?.toString().orEmpty().trim().trimEnd('/')
        val apiKey = immichApiKeyInput.text?.toString().orEmpty().trim()

        if (serverUrl.isBlank() || apiKey.isBlank()) {
            immichStatusText.text = "Immich: server URL and API key are required"
            return false
        }

        ImmichSettingsStore.saveAuthConfig(
            context = this,
            config = ImmichAuthConfig(serverUrl = serverUrl, apiKey = apiKey)
        )
        immichStatusText.text = "Immich: settings saved"
        return true
    }

    private fun testImmichConnection() {
        if (!saveImmichSettings()) {
            return
        }

        lifecycleScope.launch {
            immichStatusText.text = "Immich: testing connection..."
            val reachable = immichRepository.isConfiguredAndReachable(this@MainActivity)
            immichStatusText.text = if (reachable) {
                "Immich: connection successful"
            } else {
                "Immich: connection failed"
            }
        }
    }

    private fun loadImmichAlbums() {
        if (!saveImmichSettings()) {
            return
        }

        lifecycleScope.launch {
            immichStatusText.text = "Immich: loading albums..."
            val albums = immichRepository.fetchAlbums(this@MainActivity)
            renderImmichAlbumPicker(albums)

            immichStatusText.text = if (albums.isEmpty()) {
                "Immich: no albums found"
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

        immichStatusText.text = "Immich: saved ${selectedIds.size} selected album(s)"
        refreshStatus()
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
            previewImage.load(firstUrl)
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