package com.adsamcik.mindlayer.service.engine

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
)
