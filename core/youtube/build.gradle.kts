plugins {
    alias(libs.plugins.megapodcastplayer.android.library)
    alias(libs.plugins.megapodcastplayer.android.hilt)
}

android {
    namespace = "md.borisveriga.megapodcastplayer.core.youtube"
}

dependencies {
    // The youtube:// sentinel helpers live in :core:model, and callers of this module need them.
    api(projects.core.model)
    implementation(projects.core.common)
    // `api`, not `implementation`, on two counts: extraction shares the OkHttpClient — and so the
    // connection pool — with feeds and artwork, and YouTubePlaylistFetcher hands back the same
    // FeedChannel the RSS parser produces, which is what lets a playlist travel through the rest of
    // the app as an ordinary show.
    api(projects.core.network)

    // Deliberately `implementation`, not `api`: no org.schabi.* type may reach :core:media or :app.
    implementation(libs.newpipe.extractor)
    implementation(libs.okhttp.core)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.mockk)
}
