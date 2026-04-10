plugins {
    id("com.android.ai-pack")
}

aiPack {
    packName = "gemma_model"
    dynamicDelivery {
        deliveryType = "install-time"
    }
}
