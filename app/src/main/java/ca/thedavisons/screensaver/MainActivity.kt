package ca.thedavisons.screensaver

import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import ca.thedavisons.screensaver.immich.ImmichAuthConfig
import ca.thedavisons.screensaver.immich.ImmichRepository
import ca.thedavisons.screensaver.immich.ImmichSettingsStore
import coil.load
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

class MainActivity : AppCompatActivity() {

    companion object {
        private const val ALL_PHOTOS_ALBUM_ID = "__ALL_PHOTOS__"
        private const val SHARED_LINK_ALBUM_ID = "__SHARED_LINK__"
    }

    private lateinit var statusText: TextView
    private lateinit var albumText: TextView
    private lateinit var indexStatusText: TextView
    private lateinit var playbackSettingsText: TextView
    private lateinit var diagnosticText: TextView
    private lateinit var pairingCodeText: TextView
    private lateinit var pairingUrlText: TextView
    private lateinit var pairingStateText: TextView
    private lateinit var pairingQrImage: ImageView
    private lateinit var previewImage: ImageView
    private lateinit var previewStatusText: TextView
    private lateinit var immichStatusText: TextView
    private lateinit var immichServerUrlInput: EditText
    private lateinit var immichApiKeyInput: EditText
    private lateinit var startPairingButton: Button

    private val isTvDevice: Boolean by lazy {
        packageManager.hasSystemFeature("android.software.leanback")
    }

    private var activePairingSessionId: String? = null
    private var activePairingUserCode: String? = null
    private var activePairingUrl: String? = null
    private var pairingPollJob: Job? = null

    private val immichRepository = ImmichRepository()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(createContentView())
        refreshStatus()
    }

    override fun onDestroy() {
        pairingPollJob?.cancel()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        if (isTvDevice) {
            startPairingButton.post {
                startPairingButton.requestFocus()
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
            text = "Google Photos Setup v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
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

        val saveImmichSettingsButton = Button(this).apply {
            text = "Save Immich Settings"
            setOnClickListener { saveImmichSettings() }
        }

        val testImmichConnectionButton = Button(this).apply {
            text = "Test Immich Connection"
            setOnClickListener { testImmichConnection() }
        }

        statusText = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        }

        albumText = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        }

        indexStatusText = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        }

        playbackSettingsText = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        }

        diagnosticText = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        }

        pairingCodeText = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextIsSelectable(!isTvDevice)
        }

        pairingUrlText = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextIsSelectable(!isTvDevice)
        }

        pairingStateText = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        }

        pairingQrImage = ImageView(this).apply {
            val qrSize = dp(280)
            layoutParams = LinearLayout.LayoutParams(qrSize, qrSize).apply {
                topMargin = dp(8)
                bottomMargin = dp(8)
            }
            contentDescription = "QR code for pairing URL"
            visibility = View.GONE
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
            contentDescription = "Preview image from selected Google Photos source"
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
        }

        startPairingButton = Button(this).apply {
            text = "Start Pairing"
            setOnClickListener { startPairingSession() }
        }

        val openPairingUrlButton = Button(this).apply {
            text = "Open Pairing URL On This Device"
            setOnClickListener {
                val url = activePairingUrl
                if (url.isNullOrBlank()) {
                    pairingStateText.text = "Pairing status: no active pairing URL yet"
                    return@setOnClickListener
                }

                openPairingUrlOnDevice(url)
            }
        }

        val copyPairingCodeButton = Button(this).apply {
            text = "Copy Pairing Code"
            setOnClickListener {
                val code = activePairingUserCode
                if (code.isNullOrBlank()) {
                    pairingStateText.text = "Pairing status: no pairing code to copy yet"
                    return@setOnClickListener
                }

                copyToClipboard("Pairing code", code)
            }
        }

        val copyPairingUrlButton = Button(this).apply {
            text = "Copy Pairing URL"
            setOnClickListener {
                val url = activePairingUrl
                if (url.isNullOrBlank()) {
                    pairingStateText.text = "Pairing status: no pairing URL to copy yet"
                    return@setOnClickListener
                }

                copyToClipboard("Pairing URL", url)
            }
        }

        val checkPairingStatusButton = Button(this).apply {
            text = "Check Pairing Status Now"
            setOnClickListener { pollPairingStatusOnce() }
        }

        val previewButton = Button(this).apply {
            text = "Preview Photo From Selected Source"
            setOnClickListener { previewSelectedSource() }
        }

        val intervalLabel = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            text = "Slideshow interval"
        }

        val intervalButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, dp(6))
        }

        GooglePhotosRepository.getSupportedSlideshowIntervalSeconds().forEach { seconds ->
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
            text = "Sign Out / Clear Setup"
            setOnClickListener {
                pairingPollJob?.cancel()
                activePairingSessionId = null
                activePairingUserCode = null
                activePairingUrl = null
                GooglePhotosRepository.clearSession(this@MainActivity)
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
        content.addView(TextView(this).apply { text = "\n" })
        content.addView(statusText)
        content.addView(albumText)
        content.addView(indexStatusText)
        content.addView(playbackSettingsText)
        content.addView(diagnosticText)
        content.addView(pairingCodeText)
        content.addView(pairingUrlText)
        content.addView(pairingQrImage)
        content.addView(pairingStateText)
        content.addView(previewStatusText)
        content.addView(previewImage)
        content.addView(spacerBottom)
        content.addView(startPairingButton)
        content.addView(openPairingUrlButton)
        content.addView(copyPairingCodeButton)
        content.addView(copyPairingUrlButton)
        content.addView(checkPairingStatusButton)
        content.addView(previewButton)
        content.addView(intervalLabel)
        content.addView(intervalButtons)
        content.addView(clearButton)

        root.addView(content)
        return root
    }

    private fun refreshStatus() {
        val immichAuth = ImmichSettingsStore.loadAuthConfig(this)
        immichStatusText.text = if (immichAuth == null) {
            "Immich: not configured"
        } else {
            "Immich: configured for ${immichAuth.serverUrl}"
        }

        val signedIn = GooglePhotosRepository.hasRefreshToken(this)
        val selectedAlbum = GooglePhotosRepository.getSelectedAlbum(this)

        statusText.text = if (selectedAlbum != null) {
            "Status: linked and album selected"
        } else if (signedIn) {
            "Status: signed in but album not selected"
        } else {
            "Status: local Google session not present (pairing still in progress)"
        }

        albumText.text = if (selectedAlbum != null) {
            "Album: ${selectedAlbum.title}"
        } else {
            "Album: not selected"
        }

        indexStatusText.text = if (selectedAlbum != null) {
            val stats = GooglePhotosRepository.getPhotoIndexStats(this, selectedAlbum.id)
            val syncText = if (stats.lastSyncMs > 0L) {
                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                    .format(Date(stats.lastSyncMs))
            } else {
                "never"
            }
            "Indexed photos: ${stats.photoCount} | Last sync: $syncText"
        } else {
            "Indexed photos: 0 | Last sync: never"
        }

        val intervalSeconds = GooglePhotosRepository.getSlideshowIntervalSeconds(this)
        playbackSettingsText.text = "Playback: random order | Change every ${intervalSeconds} seconds"

        val diagnostic = GooglePhotosRepository.getLastError(this)
        diagnosticText.text = if (diagnostic.isNullOrBlank()) {
            "Diagnostic: none | Backend: ${BuildConfig.BACKEND_API_BASE_URL}"
        } else {
            "Diagnostic: $diagnostic | Backend: ${BuildConfig.BACKEND_API_BASE_URL}"
        }

        val pairingCodeDisplay = activePairingUserCode ?: "none"
        pairingCodeText.text = "Pairing code: $pairingCodeDisplay"
        pairingUrlText.text = "Pairing URL: ${activePairingUrl ?: "none"}"
        if (activePairingUrl.isNullOrBlank()) {
            pairingQrImage.setImageDrawable(null)
            pairingQrImage.visibility = View.GONE
        } else {
            pairingQrImage.setImageBitmap(generateQrBitmap(activePairingUrl!!, 280))
            pairingQrImage.visibility = View.VISIBLE
        }
        if (pairingStateText.text.isNullOrBlank()) {
            pairingStateText.text = "Pairing status: idle"
        }
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

    private fun startPairingSession() {
        lifecycleScope.launch {
            pairingStateText.text = "Pairing status: creating session..."
            GooglePhotosRepository.clearLastError(this@MainActivity)

            val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
            val created = PairingBackendRepository.createPairingSession(deviceName)
            if (created == null) {
                GooglePhotosRepository.reportError(
                    this@MainActivity,
                    "Failed to create pairing session via backend API."
                )
                pairingStateText.text = "Pairing status: failed to create session"
                refreshStatus()
                return@launch
            }

            activePairingSessionId = created.sessionId
            activePairingUserCode = created.userCode
            activePairingUrl = created.verificationUrl
            pairingCodeText.text = "Pairing code: ${created.userCode}"
            pairingUrlText.text = "Pairing URL: ${created.verificationUrl}"
            pairingStateText.text = "Pairing status: ${created.status}"
            refreshStatus()

            startPairingPolling()
        }
    }

    private fun pollPairingStatusOnce() {
        val sessionId = activePairingSessionId
        if (sessionId.isNullOrBlank()) {
            pairingStateText.text = "Pairing status: no active session"
            return
        }

        lifecycleScope.launch {
            pairingStateText.text = "Pairing status: checking..."
            val status = PairingBackendRepository.getPairingSessionStatus(sessionId)
            if (status == null) {
                pairingStateText.text = "Pairing status: check failed"
                return@launch
            }

            pairingStateText.text = "Pairing status: ${status.status}"
            if (status.status.equals("LINKED", ignoreCase = true)) {
                applyLinkedPairingConfig(sessionId)
            }
        }
    }

    private fun startPairingPolling() {
        pairingPollJob?.cancel()

        pairingPollJob = lifecycleScope.launch {
            repeat(60) {
                val sessionId = activePairingSessionId ?: return@launch
                val status = PairingBackendRepository.getPairingSessionStatus(sessionId)

                if (status != null) {
                    pairingStateText.text = "Pairing status: ${status.status}"
                    if (status.status.equals("LINKED", ignoreCase = true)) {
                        applyLinkedPairingConfig(sessionId)
                        return@launch
                    }

                    if (status.status.equals("EXPIRED", ignoreCase = true)) {
                        refreshStatus()
                        return@launch
                    }
                }

                delay(5000L)
            }

            pairingStateText.text = "Pairing status: timed out waiting for link"
            refreshStatus()
        }
    }

    private suspend fun applyLinkedPairingConfig(sessionId: String) {
        pairingStateText.text = "Pairing status: linked, applying config..."

        val linkedConfig = PairingBackendRepository.getLinkedPairingConfig(sessionId)
        if (linkedConfig == null) {
            GooglePhotosRepository.reportError(
                this@MainActivity,
                "Pairing linked but app failed to download linked config."
            )
            pairingStateText.text = "Pairing status: linked but config fetch failed"
            refreshStatus()
            return
        }

        linkedConfig.googleRefreshToken?.let { refreshToken ->
            GooglePhotosRepository.saveTokens(
                context = this,
                accessToken = null,
                refreshToken = refreshToken,
                expiresInSeconds = 60L
            )
        }

        val normalizedSharedUrl = linkedConfig.sharedAlbumUrl
            ?.trim()
            ?.takeUnless { it.equals("null", ignoreCase = true) }

        val normalizedAlbumId = linkedConfig.albumId
            .trim()
            .ifBlank { ALL_PHOTOS_ALBUM_ID }

        val safeAlbumId = if (
            normalizedAlbumId == SHARED_LINK_ALBUM_ID &&
            normalizedSharedUrl.isNullOrBlank() &&
            linkedConfig.albumTitle.equals("All Photos", ignoreCase = true)
        ) {
            ALL_PHOTOS_ALBUM_ID
        } else {
            normalizedAlbumId
        }

        if (!normalizedSharedUrl.isNullOrBlank()) {
            GooglePhotosRepository.setSharedAlbumSource(
                context = this,
                sessionId = linkedConfig.sessionId,
                sharedAlbumUrl = normalizedSharedUrl,
                albumTitle = linkedConfig.albumTitle
            )
        } else {
            GooglePhotosRepository.setSelectedAlbum(
                this,
                GoogleAlbum(safeAlbumId, linkedConfig.albumTitle)
            )
        }

        pairingPollJob?.cancel()
        pairingStateText.text = "Pairing status: LINKED (applied)"
        GooglePhotosRepository.clearLastError(this)
        refreshStatus()
        previewSelectedSource()
    }

    private fun previewSelectedSource() {
        lifecycleScope.launch {
            val selectedAlbum = GooglePhotosRepository.getSelectedAlbum(this@MainActivity)
            if (selectedAlbum == null) {
                previewStatusText.text = "Preview: no selected source yet"
                previewImage.setImageDrawable(null)
                previewImage.visibility = View.GONE
                return@launch
            }

            previewStatusText.text = "Preview: loading from ${selectedAlbum.title}..."

            val hasIndexedPhotos = GooglePhotosRepository.syncPhotoIndex(
                context = this@MainActivity,
                albumId = selectedAlbum.id,
                includeLatestPage = true,
                additionalPages = 1
            )

            val urls = GooglePhotosRepository.getIndexedPhotoUrls(this@MainActivity, selectedAlbum.id)
            val firstUrl = urls.firstOrNull()

            if (!hasIndexedPhotos || firstUrl.isNullOrBlank()) {
                val reason = GooglePhotosRepository.getLastError(this@MainActivity)
                    ?: "No photos returned for selected source"
                previewStatusText.text = "Preview: failed ($reason)"
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
        GooglePhotosRepository.setSlideshowIntervalSeconds(this, seconds)
        pairingStateText.text = "Slideshow interval set to ${seconds} seconds"
        refreshStatus()
    }

    private fun dp(value: Int): Int {
        val density = resources.displayMetrics.density
        return (value * density).toInt()
    }

    private fun generateQrBitmap(text: String, sizePx: Int): Bitmap {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx)
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)

        for (x in 0 until sizePx) {
            for (y in 0 until sizePx) {
                bitmap.setPixel(x, y, if (matrix.get(x, y)) Color.BLACK else Color.WHITE)
            }
        }

        return bitmap
    }

    private fun openPairingUrlOnDevice(url: String) {
        // TV devices often lack a full browser app; prefer in-app web view first.
        val openedInApp = runCatching {
            startActivity(
                Intent(this, PairingWebActivity::class.java)
                    .putExtra(PairingWebActivity.EXTRA_URL, url)
            )
            true
        }.getOrDefault(false)

        if (openedInApp) {
            pairingStateText.text = "Pairing status: opened pairing page in app"
            return
        }

        val uri = Uri.parse(url)
        val externalIntent = Intent(Intent.ACTION_VIEW, uri)
        runCatching {
            startActivity(externalIntent)
            pairingStateText.text = "Pairing status: opened pairing page in browser"
        }.onFailure {
            pairingStateText.text = "Pairing status: unable to open pairing page on this TV"
        }
    }

    private fun copyToClipboard(label: String, value: String) {
        val clipboard = getSystemService(ClipboardManager::class.java)
        if (clipboard == null) {
            pairingStateText.text = "Pairing status: clipboard unavailable on this TV"
            return
        }

        clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
        pairingStateText.text = "Pairing status: copied $label"
    }
}