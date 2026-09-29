pluginManagement {
    repositories {
        google {
            content {
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
                includeGroupAndSubgroups("androidx")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            content {
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
                includeGroupAndSubgroups("androidx")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "ShopArchive"

include(":shared")
include(":shoparchive-api")
include(":server-launcher")
include(":server")
include(":composeApp")
include(":androidApp")
include(":plugins:telegram")
include(":plugins:example-discord")
include(":plugins:import")
