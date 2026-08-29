plugins {
    alias(libs.plugins.bpodcat.android.library)
    alias(libs.plugins.bpodcat.android.hilt)
}

android {
    namespace = "md.borisveriga.bpodcat.core.common"
}

dependencies {
    api(projects.core.model)
}
