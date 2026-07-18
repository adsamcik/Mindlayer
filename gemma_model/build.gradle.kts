plugins {
    id("mindlayer.assetpack")
}

tasks.configureEach {
    if (name.contains("Release") && name != "provisionGemmaFragments") {
        dependsOn(rootProject.tasks.named("provisionGemmaFragments"))
    }
}
