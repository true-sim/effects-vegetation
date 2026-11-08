plugins {
    id("truesim.java-library-conventions")
    id("truesim.allocation-free")
    id("truesim.slang")
}

description = "Vegetation for true-sim: grass generated on the GPU from a parametric patch, trees and plants placed " +
    "from scene components, all swaying in the wind through the engine's per-material displacement."

dependencies {
    api("com.truesim.engine:engine-scene:0.195.0") // the renderer's displacement and geometry hooks, the scene bridge
    testImplementation(testFixtures("com.truesim.engine:engine-render:0.195.0")) // the headless renderer
    testImplementation("com.truesim.scene:scene-meshbuild:0.70.0") // placeholder trees and the test ground
}

// The material records, the hooks' records and the push layouts of src/main/slang, as Java; the listing
// (EffectsVegetationShadingHooks) rebuilds the engine's shading with the vegetation's geometry hooks.
slang {
    javaPackage = "com.truesim.effects.vegetation.materials"
    pushBase = "com.truesim.engine.vulkan.PushConstants" // push layouts are writers too
}

// The benchmarks and films (TRUESIM_BENCHMARK=true, TRUESIM_FILMS=true) measure the code, not its coverage probes.
tasks.test {
    if (providers.environmentVariable("TRUESIM_BENCHMARK").orNull == "true"
        || providers.environmentVariable("TRUESIM_FILMS").orNull == "true") {
        maxHeapSize = "6g"
        extensions.configure<JacocoTaskExtension> { isEnabled = false }
    }
    providers.gradleProperty("films").orNull?.let { systemProperty("truesim.films", it) }
    providers.gradleProperty("testShots").orNull?.let { systemProperty("truesim.testShots", it) }
}
