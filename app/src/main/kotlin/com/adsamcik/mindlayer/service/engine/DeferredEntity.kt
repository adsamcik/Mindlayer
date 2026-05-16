package com.adsamcik.mindlayer.service.engine

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "deferred_inference",
    indices = [
        Index("uid"),
        Index(value = ["uid", "statusCode"]),
        Index("expiresAtMs"),
        Index(value = ["uid", "completedAtMs"]),
        Index(value = ["uid", "kind"]),
    ],
)
data class DeferredEntity(
    @PrimaryKey val requestId: String,
    val uid: Int,
    val sessionId: String,
    val promptChars: Int,
    val mediaCount: Int,
    val metricsJson: String?,
    val resultText: String?,
    val errorCodeInt: Int,
    val errorCodeName: String?,
    val statusCode: Int,
    val createdAtMs: Long,
    val completedAtMs: Long?,
    val expiresAtMs: Long,
    val fetchedAtMs: Long?,
    val truncated: Boolean = false,
    @ColumnInfo(name = "kind") val kind: String = KIND_CHAT,
    @ColumnInfo(name = "blob_path") val blobPath: String? = null,
    @ColumnInfo(name = "blob_bytes") val blobBytes: Long? = null,
    @ColumnInfo(name = "per_item_metadata_json") val perItemMetadataJson: String? = null,
) {
    companion object {
        const val KIND_CHAT: String = "chat"
        const val KIND_EMBEDDING: String = "embedding"
    }
}
