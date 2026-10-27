package app.teron.superapp.suncode

import android.content.Context
import android.graphics.Canvas
import android.view.View
import app.teron.superapp.core.UserProfile
import kotlin.math.min

class SunCodeView(
    context: Context,
    var profile: UserProfile
) : View(context) {

    private var phase = 0f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) * 0.46f
        if (r <= 10f) return
        SunCodeCodec.drawSunCodeGraphic(canvas, cx, cy, r, profile, phase)
        if (isAttachedToWindow) {
            phase += 0.08f
            postInvalidateDelayed(36L)
        }
    }
}
