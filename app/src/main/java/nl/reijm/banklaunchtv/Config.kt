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
    val packages: List<String>,
)

object Config {
    val apps = listOf(
        // SmartTube first: ad-free YouTube. Official YouTube only as a fallback.
        AppSpec(
            "YouTube", R.drawable.ic_youtube,
            listOf("org.smarttube.stable", "org.smarttube.beta", "com.google.android.youtube.tv"),
        ),
        AppSpec("Netflix", R.drawable.ic_netflix, listOf("com.netflix.ninja")),
        AppSpec("NOS", R.drawable.ic_nos, listOf("nl.nos.app")),
        AppSpec("NPO Start", R.drawable.ic_npo, listOf("nl.uitzendinggemist")),
        AppSpec("Prime Video", R.drawable.ic_prime, listOf("com.amazon.amazonvideo.livingroom")),
    )
}
