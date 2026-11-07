package nl.reijm.banklaunchtv

/**
 * The apps on the home row, in this order.
 *
 * Each app is found by the first installed package in [AppSpec.packages]. If none of those
 * is installed, it falls back to an app whose label matches one of [AppSpec.labels].
 * [AppSpec.glow] is the colour the background takes on when that app is selected.
 *
 * List what's installed on the TV with:  adb shell pm list packages -3
 */
data class AppSpec(
    val name: String,
    val glow: String,
    val packages: List<String>,
    val labels: List<String> = listOf(name),
)

object Config {
    val apps = listOf(
        AppSpec(
            "YouTube", "#FF2A2A",
            // SmartTube first: ad-free YouTube. Official YouTube only as a fallback.
            listOf("org.smarttube.stable", "org.smarttube.beta", "com.google.android.youtube.tv"),
            listOf("SmartTube", "YouTube"),
        ),
        AppSpec("Netflix", "#E50914", listOf("com.netflix.ninja")),
        AppSpec("NOS", "#F04E23", listOf("nl.nos.app", "nl.nos.tv", "nl.nos.androidtv")),
        AppSpec("NPO Start", "#FF8A00", listOf("nl.npo.player", "nl.uitzendinggemist", "nl.npo.start"), listOf("NPO Start", "NPO")),
        AppSpec("Prime Video", "#1A98FF", listOf("com.amazon.amazonvideo.livingroom"), listOf("Prime Video")),
    )

    /** Glow colour when the settings button is selected. */
    const val SETTINGS_GLOW = "#6B7CFF"
}
