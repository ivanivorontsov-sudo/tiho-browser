package org.ivanivorontsov.tiho.browser

object ToolbarScroll {
    fun next(offset: Float, scrollY: Int, lastY: Int, height: Float): Float {
        if (height <= 0f || scrollY <= 0) return 0f
        return (offset - (scrollY - lastY)).coerceIn(-height, 0f)
    }
}
