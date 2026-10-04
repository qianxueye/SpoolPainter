package com.spoolpainter.app.hardware.printer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import com.spoolpainter.app.R

/** Local vendor artwork, rendered identically for receipt preview and both print paths. */
fun renderReceiptBrandLogo(context: Context, vendor: String): Bitmap? {
    val brand = ReceiptBrand.resolve(vendor) ?: return null
    try {
        val resource = when (brand) {
            ReceiptBrand.INSLOGIC -> R.drawable.ic_inslogic_wordmark
            ReceiptBrand.POLYMAKER -> R.drawable.ic_polymaker_wordmark
            ReceiptBrand.SNAPMAKER -> R.drawable.ic_snapmaker_wordmark
            ReceiptBrand.BAMBU_LAB -> R.drawable.ic_bambulab_wordmark
            ReceiptBrand.KEXCELLED -> R.drawable.ic_kexcelled_wordmark
        }
        val drawable = requireNotNull(context.getDrawable(resource)).mutate()
        val layout = ReceiptLogoLayout.fit(drawable.intrinsicWidth, drawable.intrinsicHeight)
        // SRC_IN preserves the source alpha silhouette, including transparent PNG edges.
        drawable.setTint(Color.BLACK)
        drawable.setTintMode(PorterDuff.Mode.SRC_IN)
        drawable.setBounds(0, 0, layout.widthDots, layout.artworkHeightDots)
        return Bitmap.createBitmap(layout.widthDots, layout.contentTopDots, Bitmap.Config.ARGB_8888).apply {
            density = Bitmap.DENSITY_NONE
            eraseColor(Color.WHITE)
            drawable.draw(Canvas(this))
        }
    } catch (e: Exception) {
        throw IllegalStateException("无法准备 $vendor 品牌标志，尚未回抽或打印", e)
    }
}
