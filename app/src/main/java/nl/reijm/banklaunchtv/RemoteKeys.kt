package nl.reijm.banklaunchtv

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Makes the remote's YouTube button open our YouTube (TizenTube) instead of the official app.
 *
 * The TV hands that button to a Sony service that always opens the official YouTube. An
 * accessibility service sees keys before that happens, so it can take the button over.
 * Turned on once from the PC: scripts/tv.sh keys-on
 */
class RemoteKeys : AccessibilityService() {

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != YOUTUBE_KEY) return false
        val intent = youtubeIntent() ?: return false // nothing of ours installed: let the TV handle it
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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    companion object {
        private const val TAG = "BankLaunch"
        /** What the BRAVIA remote's YouTube button sends (seen in logcat: GlobalKeyManager keyCode=196). */
        const val YOUTUBE_KEY = 196
        private const val OFFICIAL_YOUTUBE = "com.google.android.youtube.tv"
    }
}
