pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
// Downloads a JDK 21 for the build when none is installed.
plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0" }
dependencyResolutionManagement { repositories { google(); mavenCentral() } }
rootProject.name = "Unifiarr"
include(":app")
