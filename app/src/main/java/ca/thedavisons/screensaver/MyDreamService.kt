package ca.thedavisons.screensaver

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.view.animation.LinearInterpolator
import android.os.Handler
import android.os.Looper
import android.service.dreams.DreamService
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import coil.load
import ca.thedavisons.screensaver.immich.ImmichRepository
import ca.thedavisons.screensaver.immich.ImmichSettingsStore
import ca.thedavisons.screensaver.immich.SlideshowTransitionMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.random.Random

class MyDreamService : DreamService() {

    private companion object {
        const val TAG = "MyDreamService"
    }

    private lateinit var primaryImageView: ImageView
    private lateinit var secondaryImageView: ImageView
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
    private val recentRemoteImageUrls = ArrayDeque<String>()
    private var lastTransitionMode: SlideshowTransitionMode? = null
    private val slideshowRandom = Random(System.currentTimeMillis())
    @Volatile
    private var fallbackReason: String = "Loading Immich photos..."
    private var lastLatestSyncTimeMs = 0L
    private var lastExpansionSyncTimeMs = 0L
    private var lastEmptyRetryTimeMs = 0L
    @Volatile
    private var syncInProgress = false
    private var showingPrimaryImage = true

    private val immichRepository = ImmichRepository()

    private val slideshowRunnable = object : Runnable {

        override fun run() {
            val intervalMs = intervalSecondsForSlide()

            try {
                Log.d(TAG, "Frame tick: idx=$currentImageIndex remoteCount=${indexedImageUrls.size} intervalMs=$intervalMs")
                maybeRefreshRemotePhotos()

                val activeRemoteImages = indexedImageUrls

                if (activeRemoteImages.isNotEmpty()) {
                    fallbackBanner.visibility = View.GONE
                    val (imageUrl, companionUrl) = pickNextRemoteImageUrls(activeRemoteImages)
                    val fallbackResId = fallbackImages[currentImageIndex % fallbackImages.size]
                    val apiKey = ImmichSettingsStore.loadAuthConfig(this@MyDreamService)?.apiKey.orEmpty()
                    displayRemoteImageWithTransition(
                        imageUrl = imageUrl,
                        companionUrl = companionUrl,
                        fallbackResId = fallbackResId,
                        apiKey = apiKey,
                        intervalMs = intervalMs
                    )
                } else {
                    fallbackBanner.visibility = View.VISIBLE
                    fallbackBanner.text = "FALLBACK MODE\nReason: $fallbackReason"
                    val fallbackResId = fallbackImages[currentImageIndex % fallbackImages.size]
                    Log.d(TAG, "Showing fallback image index=${currentImageIndex % fallbackImages.size} resId=$fallbackResId reason=$fallbackReason")
                    displayFallbackImageWithTransition(
                        fallbackResId = fallbackResId,
                        intervalMs = intervalMs
                    )
                }

                currentImageIndex += 1
            } catch (t: Throwable) {
                Log.e(TAG, "Slideshow frame failed", t)
                fallbackBanner.visibility = View.VISIBLE
                fallbackBanner.text = "FALLBACK MODE\nReason: ${t.message ?: fallbackReason}"
                currentImageIndex += 1
            } finally {
                handler.postDelayed(this, intervalMs)
            }
        }
    }

    override fun onAttachedToWindow() {

        super.onAttachedToWindow()
        Log.d(TAG, "onAttachedToWindow")

        isInteractive = false
        isFullscreen = true

        primaryImageView = newSlideImageView()
        secondaryImageView = newSlideImageView().apply { alpha = 0f }

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
                primaryImageView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
            addView(
                secondaryImageView,
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
        Log.d(TAG, "onDreamingStopped")

        handler.removeCallbacks(slideshowRunnable)
        serviceScope.cancel()
    }

    private fun maybeRefreshRemotePhotos() {
        val now = System.currentTimeMillis()
        val emptyRetryIntervalMs = 30L * 1000L
        val latestRefreshIntervalMs = 60L * 60L * 1000L
        val expansionIntervalMs = 5L * 60L * 1000L

        if (indexedImageUrls.isEmpty()) {
            if (now - lastEmptyRetryTimeMs >= emptyRetryIntervalMs) {
                lastEmptyRetryTimeMs = now
                refreshRemotePhotos(
                    includeLatestPage = true,
                    additionalPages = 2,
                    force = false
                )
            }
            return
        }

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
                if (indexedImageUrls.isNotEmpty() && currentImageIndex >= indexedImageUrls.size) {
                    currentImageIndex = 0
                }
                fallbackReason = if (refreshedUrls.isNotEmpty()) {
                    ""
                } else {
                    "Immich returned no playable images from selected albums."
                }

                if (refreshedUrls.isNotEmpty()) {
                    if (includeLatestPage || force) {
                        lastLatestSyncTimeMs = System.currentTimeMillis()
                    }
                    lastExpansionSyncTimeMs = System.currentTimeMillis()
                    lastEmptyRetryTimeMs = System.currentTimeMillis()
                }
            } catch (t: Throwable) {
                indexedImageUrls = emptyList()
                fallbackReason = "Immich refresh failed: ${t.message ?: "unknown error"}"
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

    private fun newSlideImageView(): ImageView {
        return ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.BLACK)
            alpha = 1f
        }
    }

    private fun activeImageView(): ImageView {
        return if (showingPrimaryImage) primaryImageView else secondaryImageView
    }

    private fun inactiveImageView(): ImageView {
        return if (showingPrimaryImage) secondaryImageView else primaryImageView
    }

    private fun transitionDurationMs(intervalMs: Long): Long {
        return (intervalMs / 4L).coerceIn(400L, 1500L)
    }

    private fun transitionModeForSlide(): SlideshowTransitionMode {
        val enabledTransitions = ImmichSettingsStore.loadSlideshowSettings(this).enabledTransitions
            .ifEmpty { SlideshowTransitionMode.values().toSet() }

        val candidates = enabledTransitions
            .filter { it != lastTransitionMode }
            .ifEmpty { enabledTransitions.toList() }

        val chosen = candidates.random(slideshowRandom)
        lastTransitionMode = chosen
        return chosen
    }

    private fun displayRemoteImageWithTransition(
        imageUrl: String,
        companionUrl: String?,
        fallbackResId: Int,
        apiKey: String,
        intervalMs: Long
    ) {
        val nextView = inactiveImageView()
        val previousView = activeImageView()
        val fadeDuration = transitionDurationMs(intervalMs)

        nextView.animate().cancel()
        previousView.animate().cancel()
        nextView.alpha = 0f

        // Always transition on schedule, even if remote image loading is slow.
        nextView.setImageResource(fallbackResId)
        runTransition(previousView, nextView, fadeDuration, intervalMs)

        if (apiKey.isNotBlank()) {
            nextView.load(imageUrl) {
                addHeader("x-api-key", apiKey)
                listener(onSuccess = { _, result ->
                    val primaryDrawable = result.drawable
                    nextView.setImageDrawable(primaryDrawable)
                    if (shouldUseSideBySide(primaryDrawable) && !companionUrl.isNullOrBlank() && companionUrl != imageUrl) {
                        loadCompanionIntoView(
                            companionUrl = companionUrl,
                            apiKey = apiKey,
                            primaryDrawable = primaryDrawable,
                            targetView = nextView
                        )
                    }
                }, onError = { _, errorResult ->
                    fallbackReason = "Image load failed: ${errorResult.throwable.message ?: "unknown"}"
                    fallbackBanner.visibility = View.VISIBLE
                })
            }
        } else {
            nextView.load(imageUrl) {
                listener(onSuccess = { _, result ->
                    val primaryDrawable = result.drawable
                    nextView.setImageDrawable(primaryDrawable)
                    if (shouldUseSideBySide(primaryDrawable) && !companionUrl.isNullOrBlank() && companionUrl != imageUrl) {
                        loadCompanionIntoView(
                            companionUrl = companionUrl,
                            apiKey = apiKey,
                            primaryDrawable = primaryDrawable,
                            targetView = nextView
                        )
                    }
                }, onError = { _, errorResult ->
                    fallbackReason = "Image load failed: ${errorResult.throwable.message ?: "unknown"}"
                    fallbackBanner.visibility = View.VISIBLE
                })
            }
        }
    }

    private fun shouldUseSideBySide(primaryDrawable: android.graphics.drawable.Drawable): Boolean {
        val width = primaryDrawable.intrinsicWidth
        val height = primaryDrawable.intrinsicHeight
        if (width <= 0 || height <= 0) {
            return false
        }

        val photoAspect = width.toFloat() / height.toFloat()
        val screenAspect = resources.displayMetrics.widthPixels.toFloat() /
            resources.displayMetrics.heightPixels.toFloat()

        return photoAspect < (screenAspect * 0.72f)
    }

    private fun loadCompanionIntoView(
        companionUrl: String,
        apiKey: String,
        primaryDrawable: android.graphics.drawable.Drawable,
        targetView: ImageView
    ) {
        val scratchView = ImageView(this)

        if (apiKey.isNotBlank()) {
            scratchView.load(companionUrl) {
                addHeader("x-api-key", apiKey)
                listener(onSuccess = { _, companionResult ->
                    val combined = composeSideBySideBitmap(primaryDrawable, companionResult.drawable)
                    if (combined != null) {
                        targetView.setImageBitmap(combined)
                    } else {
                        targetView.setImageDrawable(primaryDrawable)
                    }
                }, onError = { _, _ ->
                    targetView.setImageDrawable(primaryDrawable)
                })
            }
        } else {
            scratchView.load(companionUrl) {
                listener(onSuccess = { _, companionResult ->
                    val combined = composeSideBySideBitmap(primaryDrawable, companionResult.drawable)
                    if (combined != null) {
                        targetView.setImageBitmap(combined)
                    } else {
                        targetView.setImageDrawable(primaryDrawable)
                    }
                }, onError = { _, _ ->
                    targetView.setImageDrawable(primaryDrawable)
                })
            }
        }
    }

    private fun composeSideBySideBitmap(
        leftDrawable: android.graphics.drawable.Drawable,
        rightDrawable: android.graphics.drawable.Drawable
    ): Bitmap? {
        val canvasWidth = resources.displayMetrics.widthPixels
        val canvasHeight = resources.displayMetrics.heightPixels
        if (canvasWidth <= 0 || canvasHeight <= 0) {
            return null
        }

        val leftBitmap = drawableToBitmap(leftDrawable) ?: return null
        val rightBitmap = drawableToBitmap(rightDrawable) ?: return null

        val output = Bitmap.createBitmap(canvasWidth, canvasHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawColor(Color.BLACK)

        val leftRect = RectF(0f, 0f, canvasWidth / 2f, canvasHeight.toFloat())
        val rightRect = RectF(canvasWidth / 2f, 0f, canvasWidth.toFloat(), canvasHeight.toFloat())

        drawCenterCrop(canvas, leftBitmap, leftRect)
        drawCenterCrop(canvas, rightBitmap, rightRect)

        return output
    }

    private fun drawableToBitmap(drawable: android.graphics.drawable.Drawable): Bitmap? {
        if (drawable is android.graphics.drawable.BitmapDrawable) {
            return drawable.bitmap
        }

        val width = drawable.intrinsicWidth
        val height = drawable.intrinsicHeight
        if (width <= 0 || height <= 0) {
            return null
        }

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, width, height)
        drawable.draw(canvas)
        return bitmap
    }

    private fun drawCenterCrop(canvas: Canvas, bitmap: Bitmap, dst: RectF) {
        val srcWidth = bitmap.width.toFloat()
        val srcHeight = bitmap.height.toFloat()
        if (srcWidth <= 0f || srcHeight <= 0f) {
            return
        }

        val scale = maxOf(dst.width() / srcWidth, dst.height() / srcHeight)
        val scaledWidth = srcWidth * scale
        val scaledHeight = srcHeight * scale
        val left = dst.left + (dst.width() - scaledWidth) / 2f
        val top = dst.top + (dst.height() - scaledHeight) / 2f
        val fitted = RectF(left, top, left + scaledWidth, top + scaledHeight)

        canvas.drawBitmap(bitmap, null, fitted, null)
    }

    private fun displayFallbackImageWithTransition(fallbackResId: Int, intervalMs: Long) {
        val nextView = inactiveImageView()
        val previousView = activeImageView()
        val fadeDuration = transitionDurationMs(intervalMs)

        nextView.setImageResource(fallbackResId)
        nextView.alpha = 0f
        runTransition(previousView, nextView, fadeDuration, intervalMs)
    }

    private fun runTransition(
        previousView: ImageView,
        nextView: ImageView,
        transitionDurationMs: Long,
        intervalMs: Long
    ) {
        nextView.animate().cancel()
        previousView.animate().cancel()

        resetTransitionState(previousView, 1f)
        resetTransitionState(nextView, 0f)

        when (transitionModeForSlide()) {
            SlideshowTransitionMode.CROSSFADE -> {
                nextView.animate()
                    .alpha(1f)
                    .setDuration(transitionDurationMs)
                    .setInterpolator(LinearInterpolator())
                    .start()

                previousView.animate()
                    .alpha(0f)
                    .setDuration(transitionDurationMs)
                    .setInterpolator(LinearInterpolator())
                    .withEndAction { finishTransition(intervalMs) }
                    .start()
            }

            SlideshowTransitionMode.SLIDE_LEFT -> {
                val screenWidth = resources.displayMetrics.widthPixels.toFloat()
                nextView.translationX = -screenWidth * 0.25f

                nextView.animate()
                    .alpha(1f)
                    .translationX(0f)
                    .setDuration(transitionDurationMs)
                    .setInterpolator(LinearInterpolator())
                    .start()

                previousView.animate()
                    .alpha(0f)
                    .translationX(screenWidth * 0.25f)
                    .setDuration(transitionDurationMs)
                    .setInterpolator(LinearInterpolator())
                    .withEndAction { finishTransition(intervalMs) }
                    .start()
            }

            SlideshowTransitionMode.SLIDE_RIGHT -> {
                val screenWidth = resources.displayMetrics.widthPixels.toFloat()
                nextView.translationX = screenWidth * 0.25f

                nextView.animate()
                    .alpha(1f)
                    .translationX(0f)
                    .setDuration(transitionDurationMs)
                    .setInterpolator(LinearInterpolator())
                    .start()

                previousView.animate()
                    .alpha(0f)
                    .translationX(-screenWidth * 0.25f)
                    .setDuration(transitionDurationMs)
                    .setInterpolator(LinearInterpolator())
                    .withEndAction { finishTransition(intervalMs) }
                    .start()
            }

            SlideshowTransitionMode.SLIDE_UP -> {
                val screenHeight = resources.displayMetrics.heightPixels.toFloat()
                nextView.translationY = screenHeight * 0.25f

                nextView.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(transitionDurationMs)
                    .setInterpolator(LinearInterpolator())
                    .start()

                previousView.animate()
                    .alpha(0f)
                    .translationY(-screenHeight * 0.25f)
                    .setDuration(transitionDurationMs)
                    .setInterpolator(LinearInterpolator())
                    .withEndAction { finishTransition(intervalMs) }
                    .start()
            }

            SlideshowTransitionMode.SLIDE_DOWN -> {
                val screenHeight = resources.displayMetrics.heightPixels.toFloat()
                nextView.translationY = -screenHeight * 0.25f

                nextView.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(transitionDurationMs)
                    .setInterpolator(LinearInterpolator())
                    .start()

                previousView.animate()
                    .alpha(0f)
                    .translationY(screenHeight * 0.25f)
                    .setDuration(transitionDurationMs)
                    .setInterpolator(LinearInterpolator())
                    .withEndAction { finishTransition(intervalMs) }
                    .start()
            }

            SlideshowTransitionMode.CENTER_EXPAND -> {
                nextView.scaleX = 0.82f
                nextView.scaleY = 0.82f

                nextView.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(transitionDurationMs)
                    .setInterpolator(LinearInterpolator())
                    .start()

                previousView.animate()
                    .alpha(0f)
                    .scaleX(1.08f)
                    .scaleY(1.08f)
                    .setDuration(transitionDurationMs)
                    .setInterpolator(LinearInterpolator())
                    .withEndAction { finishTransition(intervalMs) }
                    .start()
            }

            SlideshowTransitionMode.PUSH_LEFT -> {
                val screenWidth = resources.displayMetrics.widthPixels.toFloat()
                nextView.translationX = screenWidth * 0.40f
                nextView.alpha = 1f

                nextView.animate()
                    .translationX(0f)
                    .setDuration(transitionDurationMs)
                    .setInterpolator(LinearInterpolator())
                    .start()

                previousView.animate()
                    .translationX(-screenWidth * 0.40f)
                    .setDuration(transitionDurationMs)
                    .setInterpolator(LinearInterpolator())
                    .withEndAction { finishTransition(intervalMs) }
                    .start()
            }

            SlideshowTransitionMode.PUSH_RIGHT -> {
                val screenWidth = resources.displayMetrics.widthPixels.toFloat()
                nextView.translationX = -screenWidth * 0.40f
                nextView.alpha = 1f

                nextView.animate()
                    .translationX(0f)
                    .setDuration(transitionDurationMs)
                    .setInterpolator(LinearInterpolator())
                    .start()

                previousView.animate()
                    .translationX(screenWidth * 0.40f)
                    .setDuration(transitionDurationMs)
                    .setInterpolator(LinearInterpolator())
                    .withEndAction { finishTransition(intervalMs) }
                    .start()
            }

            SlideshowTransitionMode.ZOOM_FADE -> {
                nextView.scaleX = 1.12f
                nextView.scaleY = 1.12f

                nextView.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(transitionDurationMs)
                    .setInterpolator(LinearInterpolator())
                    .start()

                previousView.animate()
                    .alpha(0f)
                    .scaleX(0.92f)
                    .scaleY(0.92f)
                    .setDuration(transitionDurationMs)
                    .setInterpolator(LinearInterpolator())
                    .withEndAction { finishTransition(intervalMs) }
                    .start()
            }
        }
    }

    private fun resetTransitionState(view: ImageView, alpha: Float) {
        view.alpha = alpha
        view.translationX = 0f
        view.translationY = 0f
        view.scaleX = 1f
        view.scaleY = 1f
    }

    private fun finishTransition(intervalMs: Long) {
        showingPrimaryImage = !showingPrimaryImage
        resetTransitionState(inactiveImageView(), 0f)
        startPhotoMotionOnView(activeImageView(), intervalMs)
    }

    private fun startPhotoMotionOnView(targetView: ImageView, durationMs: Long) {
        targetView.animate().cancel()
        targetView.scaleX = 1f
        targetView.scaleY = 1f
        targetView.translationX = 0f
        targetView.translationY = 0f

        val maxTranslationX = resources.displayMetrics.widthPixels * 0.035f
        val maxTranslationY = resources.displayMetrics.heightPixels * 0.035f
        val targetScale = 1.04f + (Random.nextFloat() * 0.02f)
        val targetTranslationX = (Random.nextFloat() * 2f - 1f) * maxTranslationX
        val targetTranslationY = (Random.nextFloat() * 2f - 1f) * maxTranslationY

        targetView.animate()
            .scaleX(targetScale)
            .scaleY(targetScale)
            .translationX(targetTranslationX)
            .translationY(targetTranslationY)
            .setDuration(durationMs.coerceAtLeast(1000L))
            .setInterpolator(LinearInterpolator())
            .start()
    }

    private fun pickNextRemoteImageUrls(remoteUrls: List<String>): Pair<String, String?> {
        val availableUrls = remoteUrls.filter { !recentRemoteImageUrls.contains(it) }
        val candidateUrls = if (availableUrls.isNotEmpty()) availableUrls else remoteUrls
        val selectedUrl = candidateUrls.random(slideshowRandom)
        if (recentRemoteImageUrls.size >= 100) {
            recentRemoteImageUrls.removeFirst()
        }
        recentRemoteImageUrls.addLast(selectedUrl)

        val companionUrl = remoteUrls
            .filter { it != selectedUrl }
            .takeIf { it.isNotEmpty() }
            ?.randomOrNull(slideshowRandom)

        return selectedUrl to companionUrl
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