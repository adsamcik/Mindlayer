package com.adsamcik.mindlayer

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class EmbeddingItemMetadata(
    val tag: String? = null,
    val tokenCount: Int,
    val truncated: Boolean,
) : Parcelable
