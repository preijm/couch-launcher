package nl.reijm.banklaunchtv

/**
 * The apps in the home list, in this order.
 *
 * Each app is found by the first installed package in [AppSpec.packages]. Watch time is
 * counted over all of them together, so YouTube history from before SmartTube still shows.
 *
 * List what's installed on the TV with:  adb shell pm list packages -3
 */
data class AppSpec(
    val name: String,
    val icon: Int,
    /** Colour of this app in the charts. */
    val color: String,
    val packages: List<String>,
)

object Config {
    val apps = listOf(
        // TizenTube Cobalt first (modern YouTube TV look, no ads), then SmartTube, then official YouTube.
        AppSpec(
            "YouTube", R.drawable.ic_youtube, "#FF6B5E",
            listOf(
                "io.gh.reisxd.tizentube.cobalt", "org.smarttube.stable", "org.smarttube.beta",
                "com.google.android.youtube.tv",
            ),
        ),
        AppSpec("Netflix", R.drawable.ic_netflix, "#C8344C", listOf("com.netflix.ninja")),
        AppSpec("NOS", R.drawable.ic_nos, "#6C86FF", listOf("nl.nos.app")),
        AppSpec("NPO Start", R.drawable.ic_npo, "#FFA94D", listOf("nl.uitzendinggemist")),
        AppSpec("Prime Video", R.drawable.ic_prime, "#3FB8E8", listOf("com.amazon.amazonvideo.livingroom")),
    )
}
