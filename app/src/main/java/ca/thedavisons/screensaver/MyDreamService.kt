private fun decodeScaledBitmap(resId: Int): Bitmap {
    val options = BitmapFactory.Options()
    options.inJustDecodeBounds = true
    BitmapFactory.decodeResource(resources, resId, options)

    val reqWidth = 1280
    val reqHeight = 720

    options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
    options.inJustDecodeBounds = false
    options.inPreferredConfig = Bitmap.Config.RGB_565

    return BitmapFactory.decodeResource(resources, resId, options)
}

private fun calculateInSampleSize(
    options: BitmapFactory.Options,
    reqWidth: Int,
    reqHeight: Int
): Int {
    val (height: Int, width: Int) = options.outHeight to options.outWidth
    var inSampleSize = 1

    if (height > reqHeight || width > reqWidth) {
        val halfHeight = height / 2
        val halfWidth = width / 2

        while ((halfHeight / inSampleSize) >= reqHeight &&
            (halfWidth / inSampleSize) >= reqWidth) {
            inSampleSize *= 2
        }
    }

    return inSampleSize
}