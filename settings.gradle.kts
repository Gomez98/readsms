pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }  // ← así es la forma correcta
    }
}

includeBuild("../readsms-master/simple-commons") {
    dependencySubstitution {
        substitute(module("com.github.SimpleMobileTools:Simple-Commons")).using(project(":commons"))
    }
}

include(":app")