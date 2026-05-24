package ca.thedavisons.screensaver

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.view.animation.LinearInterpolator
import android.os.Handler
import android.os.Looper
import android.service.dreams.DreamService
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import coil.load
import ca.thedavisons.screensaver.immich.ImmichRepository
import ca.thedavisons.screensaver.immich.ImmichSettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.random.Random

class MyDreamService : DreamService() {

    private lateinit var imageView: ImageView
    private lateinit var fallbackBanner: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val fallbackImages = listOf(
        R.drawable.photo1,
        R.drawable.photo2,
        R.drawable.photo3
    )

    private var currentImageIndex = 0
    @Volatile
    private var indexedImageUrls: List<String> = emptyList()
    @Volatile
    private var fallbackReason: String = "Loading Immich photos..."
    private var lastLatestSyncTimeMs = 0L
    private var lastExpansionSyncTimeMs = 0L
    @Volatile
    private var syncInProgress = false
    private var currentAnimatorRunning = false

    private val immichRepository = ImmichRepository()

    private val slideshowRunnable = object : Runnable {

        override fun run() {
            maybeRefreshRemotePhotos()

            val activeRemoteImages = indexedImageUrls

            if (activeRemoteImages.isNotEmpty()) {
                fallbackBanner.visibility = View.GONE
                val imageUrl = activeRemoteImages[currentImageIndex % activeRemoteImages.size]
                imageView.load(imageUrl)
                startPhotoMotion(intervalSecondsForSlide())
            } else {
                fallbackBanner.visibility = View.VISIBLE
                fallbackBanner.text = "FALLBACK MODE\nReason: $fallbackReason"
                val bitmap = decodeScaledBitmap(fallbackImages[currentImageIndex % fallbackImages.size])
                imageView.setImageBitmap(bitmap)
            }

            currentImageIndex += 1

            handler.postDelayed(this, intervalSecondsForSlide())
        }
    }

    override fun onAttachedToWindow() {

        super.onAttachedToWindow()

        isInteractive = false
        isFullscreen = true

        imageView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.BLACK)
        }

        fallbackBanner = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#B3000000"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            text = "FALLBACK MODE\nReason: $fallbackReason"
            gravity = Gravity.START
        }

        val container = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(
                imageView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
            addView(
                fallbackBanner,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP
                )
            )
        }

        setContentView(container)

        refreshRemotePhotos(
            includeLatestPage = true,
            additionalPages = 8,
            force = true
        )
        slideshowRunnable.run()
    }

    override fun onDreamingStopped() {

        super.onDreamingStopped()

        handler.removeCallbacks(slideshowRunnable)
        serviceScope.cancel()
    }

    private fun maybeRefreshRemotePhotos() {
        val now = System.currentTimeMillis()
        val latestRefreshIntervalMs = 60L * 60L * 1000L
        val expansionIntervalMs = 5L * 60L * 1000L

        if (now - lastLatestSyncTimeMs >= latestRefreshIntervalMs) {
            refreshRemotePhotos(
                includeLatestPage = true,
                additionalPages = 2,
                force = false
            )
            return
        }

        if (now - lastExpansionSyncTimeMs >= expansionIntervalMs) {
            refreshRemotePhotos(
                includeLatestPage = false,
                additionalPages = 3,
                force = false
            )
        }
    }

    private fun refreshRemotePhotos(
        includeLatestPage: Boolean,
        additionalPages: Int,
        force: Boolean
    ) {
        if (syncInProgress) {
            return
        }

        syncInProgress = true

        serviceScope.launch {
            try {
                val immichSelection = ImmichSettingsStore.loadSlideshowSettings(this@MyDreamService)
                val hasImmichConfigured = immichRepository.hasConfiguredAuth(this@MyDreamService)

                if (!hasImmichConfigured) {
                    indexedImageUrls = emptyList()
                    fallbackReason = "Immich is not configured in app setup."
                    return@launch
                }

                if (immichSelection.selectedAlbumIds.isEmpty()) {
                    indexedImageUrls = emptyList()
                    fallbackReason = "No Immich albums selected in app setup."
                    return@launch
                }

                val refreshedUrls = immichRepository.fetchSelectedAlbumSlideshowUrls(
                    context = this@MyDreamService,
                    useShuffle = immichSelection.shuffle
                )

                indexedImageUrls = refreshedUrls
                if (currentImageIndex >= indexedImageUrls.size) {
                    currentImageIndex = 0
                }
                fallbackReason = if (refreshedUrls.isNotEmpty()) {
                    ""
                } else {
                    "Immich returned no playable images from selected albums."
                }

                if (includeLatestPage || force) {
                    lastLatestSyncTimeMs = System.currentTimeMillis()
                }
                lastExpansionSyncTimeMs = System.currentTimeMillis()
            } finally {
                syncInProgress = false
            }
        }
    }

    private fun intervalSecondsForSlide(): Long {
        return ImmichSettingsStore.loadSlideshowSettings(this).intervalSeconds
            .coerceAtLeast(1)
            .toLong() * 1000L
    }

    private fun startPhotoMotion(durationMs: Long) {
        currentAnimatorRunning = false
        imageView.animate().cancel()
        imageView.scaleX = 1f
        imageView.scaleY = 1f
        imageView.translationX = 0f
        imageView.translationY = 0f

        val maxTranslationX = resources.displayMetrics.widthPixels * 0.035f
        val maxTranslationY = resources.displayMetrics.heightPixels * 0.035f
        val targetScale = 1.04f + (Random.nextFloat() * 0.02f)
        val targetTranslationX = (Random.nextFloat() * 2f - 1f) * maxTranslationX
        val targetTranslationY = (Random.nextFloat() * 2f - 1f) * maxTranslationY

        imageView.animate()
            .scaleX(targetScale)
            .scaleY(targetScale)
            .translationX(targetTranslationX)
            .translationY(targetTranslationY)
            .setDuration(durationMs.coerceAtLeast(1000L))
            .setInterpolator(LinearInterpolator())
            .start()
    }

    private fun dp(value: Int): Int {
        val density = resources.displayMetrics.density
        return (value * density).toInt()
    }

    private fun decodeScaledBitmap(resId: Int): Bitmap {

        val options = BitmapFactory.Options()

        options.inJustDecodeBounds = true

        BitmapFactory.decodeResource(
            resources,
            resId,
            options
        )

        val reqWidth = 1280
        val reqHeight = 720

        options.inSampleSize =
            calculateInSampleSize(
                options,
                reqWidth,
                reqHeight
            )

        options.inJustDecodeBounds = false

        options.inPreferredConfig =
            Bitmap.Config.RGB_565

        return BitmapFactory.decodeResource(
            resources,
            resId,
            options
        )
    }

    private fun calculateInSampleSize(
        options: BitmapFactory.Options,
        reqWidth: Int,
        reqHeight: Int
    ): Int {

        val height = options.outHeight
        val width = options.outWidth

        var inSampleSize = 1

        if (height > reqHeight ||
            width > reqWidth
        ) {

            val halfHeight = height / 2
            val halfWidth = width / 2

            while (
                (halfHeight / inSampleSize) >= reqHeight &&
                (halfWidth / inSampleSize) >= reqWidth
            ) {

                inSampleSize *= 2
            }
        }

        return inSampleSize
    }
}