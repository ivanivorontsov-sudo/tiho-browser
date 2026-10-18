package org.ivanivorontsov.tiho.browser

object ToolbarScroll {
    const val HEIGHT_DP = 64

    fun maxHeightPx(density: Float, fullscreen: Boolean): Int = if (fullscreen) 0 else Math.round(HEIGHT_DP * density)

    fun next(offset: Float, scrollY: Int, lastY: Int, height: Float): Float {
        if (height <= 0f || scrollY <= 0) return 0f
        return (offset - (scrollY - lastY)).coerceIn(-height, 0f)
    }
}
