package de.radicalfishgames.crosscode.features

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.webkit.JavascriptInterface
import de.radicalfishgames.crosscode.GameActivity
import de.radicalfishgames.crosscode.GameWrapper
import kotlin.math.ceil
import kotlin.math.min


class HapticFeedbackFeature(
    gameWrapper: GameWrapper,
    hostActivity: GameActivity
) : Feature(gameWrapper, hostActivity) {

    private val enabled = hostActivity.preferences.getBoolean("vibration_on_rumble", true)
    private val vibrator = hostActivity.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

    override fun onPreGamePageLoad() {

        if(!enabled){
            return
        }

        gameWrapper.exposeJSInterface(this, JS_INTERFACE_NAME)
    }

    override fun onPostGamePageLoad() {

        if(!enabled){
            return
        }

        runJS("""
            ig.Rumble.RumbleHandle.inject({
                _updatePosition: function(arg) {
                    this.parent(arg);
                    $JS_INTERFACE_NAME.reportRumble(arg, this.shakeDuration);
                }
            });
        """)
    }

    @JavascriptInterface
    fun reportRumble(strength: Double, effectDuration: Double){
        try {

            if(strength > 15){
                Log.e("CrossCode", "Received a rumble strength that is larger than rumbles should get!")
                return
            }

            if(effectDuration > 0.2){
                // Effect is too slow - less of a screen shake and more of a screen-wobbling
                return
            }

            val vibrationAmplitude = min(ceil(MAX_VIB_AMPLITUDE * (strength / MAX_RUMBLE_STRENGTH) * NORMAL_EFFECT_DURATION/effectDuration), 255.0).toInt()
            val vibrationDuration = ceil(MAX_VIB_DURATION_MILLIS * (strength / MAX_RUMBLE_STRENGTH)).toLong()

            if(vibrationAmplitude == 0 || vibrationDuration == 0L){
                Log.w("CrossCode", "Rumble strength is too small!")
                return
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(vibrationDuration, vibrationAmplitude))

            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(vibrationDuration)
            }

        } catch (ex: Exception) {
            hostActivity.errorHandler.uncaughtException(Thread.currentThread(), ex)
            throw ex
        }
    }

    companion object {
        const val JS_INTERFACE_NAME = "CARumbleListener"

        const val MAX_RUMBLE_STRENGTH = 15.0
        const val MAX_VIB_AMPLITUDE = 255
        const val MAX_VIB_DURATION_MILLIS = 40
        const val NORMAL_EFFECT_DURATION = 0.2
    }
}