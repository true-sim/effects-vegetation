pluginManagement {
    // Use ../build-logic when present; override with -Ptruesim.build-logic=<path> (or false).
    providers.gradleProperty("truesim.build-logic").orElse("../build-logic").get()
        .takeIf { file(it).isDirectory }?.let { includeBuild(it) }
    repositories {
        gradlePluginPortal()
        maven("https://maven.pkg.github.com/true-sim/build-logic") {
            credentials {
                username = providers.gradleProperty("gpr.user").orElse(providers.environmentVariable("GITHUB_ACTOR")).orNull
                password = providers.gradleProperty("gpr.key").orElse(providers.environmentVariable("GITHUB_TOKEN")).orNull
            }
        }
    }
}

plugins {
    id("truesim.settings") version "0.19.1"
}

rootProject.name = "effects-vegetation"

// Vegetation: GPU grass, trees and plants swaying in the wind, through the engine's per-material displacement.
include("effects-vegetation")
project(":effects-vegetation").projectDir = file("vegetation")

// Sibling checkouts are used as included builds when present (override with -Ptruesim.<repo>=<path>, or false).
val truesimRepos = listOf("build-logic", "common-math", "common-state", "common-jobs", "common-graph", "scene", "engine")
// Only fetched (the forest of the impostors' tests): never included from a sibling
val fetchedRepos = listOf("common-procedural")
for (repo in truesimRepos) {
    providers.gradleProperty("truesim.$repo").orElse("../$repo").get()
        .takeIf { file(it).isDirectory }?.let { includeBuild(it) }
}

dependencyResolutionManagement {
    repositories {
        for (repo in truesimRepos + fetchedRepos) {
            maven("https://maven.pkg.github.com/true-sim/$repo") {
                credentials {
                    username = providers.gradleProperty("gpr.user").orElse(providers.environmentVariable("GITHUB_ACTOR")).orNull
                    password = providers.gradleProperty("gpr.key").orElse(providers.environmentVariable("GITHUB_TOKEN")).orNull
                }
            }
        }
    }
}
