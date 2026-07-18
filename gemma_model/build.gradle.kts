plugins {
    id("com.android.asset-pack")
}

assetPack {
    packName = "gemma_model"
    dynamicDelivery {
        deliveryType = "on-demand"
    }
}

tasks.configureEach {
    if (name.contains("Release") && name != "provisionGemmaFragments") {
        dependsOn(rootProject.tasks.named("provisionGemmaFragments"))
    }
}
