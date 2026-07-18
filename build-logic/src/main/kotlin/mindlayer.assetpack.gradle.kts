/**
 * On-demand Play Asset Delivery pack whose Play `packName` is the Gradle module
 * name. Modules keep any pack-specific release provisioning / integrity tasks.
 */
plugins {
    id("com.android.asset-pack")
}

assetPack {
    packName = project.name
    dynamicDelivery {
        deliveryType = "on-demand"
    }
}
