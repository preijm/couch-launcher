package nl.reijm.banklaunchtv

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Makes the remote's YouTube button open our YouTube (TizenTube) instead of the official app,
 * and the green and yellow buttons open NOS and NPO Start (except while watching TV channels,
 * where they keep doing teletext and the broadcasters' red-button pages).
 *
 * The TV hands that button to a Sony service that always opens the official YouTube. An
 * accessibility service sees keys before that happens, so it can take the button over.
 * Turned on once from the PC: scripts/tv.sh keys-on
 */
class RemoteKeys : AccessibilityService() {

    /** The app in front, from window changes: the colour buttons leave the TV channels alone. */
    private var inFront: String? = null

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val intent = when (event.keyCode) {
            YOUTUBE_KEY -> youtubeIntent()
            KeyEvent.KEYCODE_PROG_GREEN -> if (watchingChannels()) null else appIntent("nos")
            KeyEvent.KEYCODE_PROG_YELLOW -> if (watchingChannels()) null else appIntent("npo")
            else -> null
        } ?: return false // not ours, or nothing installed: let the TV handle it
        if (event.action == KeyEvent.ACTION_UP) {
            try {
                startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
                Log.w(TAG, "Could not open YouTube", e)
            }
        }
        return true
    }

    /** The first installed app of the YouTube entry in the home list (TizenTube first). */
    private fun youtubeIntent(): Intent? {
        val spec = AppList.load(this).firstOrNull { it.iconKey == "youtube" } ?: return null
        val pm = packageManager
        return spec.packages.asSequence()
            .filter { it != OFFICIAL_YOUTUBE }
            .mapNotNull { pm.getLeanbackLaunchIntentForPackage(it) ?: pm.getLaunchIntentForPackage(it) }
            .firstOrNull()
    }

    /** The first installed app of an entry in the home list, by its icon (e.g. "nos"). */
    private fun appIntent(icon: String): Intent? {
        val spec = AppList.load(this).firstOrNull { it.iconKey == icon } ?: return null
        val pm = packageManager
        return spec.packages.asSequence()
            .mapNotNull { pm.getLeanbackLaunchIntentForPackage(it) ?: pm.getLaunchIntentForPackage(it) }
            .firstOrNull()
    }

    private fun watchingChannels() = inFront?.startsWith("com.sony.dtv.tv") == true

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            event.packageName?.toString()?.let { if (it != "android" && !it.startsWith("com.android.systemui")) inFront = it }
        }
    }
    override fun onInterrupt() {}

    companion object {
        private const val TAG = "BankLaunch"
        /** What the BRAVIA remote's YouTube button sends (seen in logcat: GlobalKeyManager keyCode=196). */
        const val YOUTUBE_KEY = 196
        private const val OFFICIAL_YOUTUBE = "com.google.android.youtube.tv"
    }
}
