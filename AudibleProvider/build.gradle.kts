version = 14

cloudstream {
    description = "Audible audiobook catalogue powered by Libex metadata. Audio playback sources are not yet integrated."
    authors = listOf("Rashedul0103")
    status = 1
    tvTypes = listOf("AudioBook", "Others", "Movie", "TvSeries")
    iconUrl = "https://www.google.com/s2/favicons?domain=audible.com&sz=%size%"
    isCrossPlatform = false
}

dependencies {
    // Plugin UI hooks and native dialog dependencies for provider-specific settings.
    val cloudstream by configurations
    cloudstream("com.lagradost:cloudstream3:pre-release")

    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.core:core-ktx:1.9.0")
}


tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}


android {
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
