plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.10.4"
}

group = "com.yuriyvot"
version = "0.4.8"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

val ideLocalPath = providers.gradleProperty("ideLocalPath")

dependencies {
    intellijPlatform {
        if (ideLocalPath.isPresent && file(ideLocalPath.get()).exists()) {
            local(ideLocalPath.get())
        } else {
            rider("2025.3.1") {
                useInstaller = false
            }
        }
    }
    testImplementation("junit:junit:4.13.2")
}

kotlin {
    jvmToolchain(21)
}

intellijPlatform {
    buildSearchableOptions = false
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "253"
            untilBuild = provider { null }
        }
    }
}
