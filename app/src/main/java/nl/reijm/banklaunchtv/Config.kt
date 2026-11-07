package nl.reijm.banklaunchtv

/**
 * Edit this file to change what Bank Launch TV shows.
 *
 * Favourites are package names, shown in this order. A package that isn't installed
 * is skipped, so unverified names here do no harm. List what's installed on the TV with:
 *   adb shell pm list packages -3
 */
object Config {
    val favourites = listOf(
        "org.smarttube.stable",                 // SmartTube (ad-free YouTube)
        "com.google.android.youtube.tv",        // YouTube
        "com.netflix.ninja",                    // Netflix
        "com.amazon.amazonvideo.livingroom",    // Prime Video
        "com.disney.disneyplus",                // Disney+
        "nl.uitzendinggemist",                  // NPO Start - verify
        "com.spotify.tv.android",               // Spotify
    )

    /** Packages never shown in the "Alle apps" row. */
    val hidden = setOf(
        BuildConfigPkg.SELF,
    )
}

internal object BuildConfigPkg {
    const val SELF = "nl.reijm.banklaunchtv"
}
