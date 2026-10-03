pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Mapbox Maps SDK (repozytorium publiczne, bez tokenu pobierania).
        maven {
            url = uri("https://api.mapbox.com/downloads/v2/releases/maven")
            // Opcjonalnie: token tajny (sk.…) z ~/.gradle/gradle.properties, potrzebny tylko gdy serwer Mapbox zwróci 401.
            val downloadsToken = providers.gradleProperty("MAPBOX_DOWNLOADS_TOKEN")
            if (downloadsToken.isPresent) {
                credentials {
                    username = "mapbox"
                    password = downloadsToken.get()
                }
                authentication { create<BasicAuthentication>("basic") }
            }
        }
    }
}

rootProject.name = "KajakApp"
include(":app")
