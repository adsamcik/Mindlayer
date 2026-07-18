package com.adsamcik.mindlayer.service.modeldelivery

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal enum class ModelDeliveryIntent {
    INSTALL,
    REMOVE,
}

internal enum class ModelDeliveryIntentTransitionPoint {
    AFTER_CANONICAL_RECORD,
    AFTER_PENDING_MARKER,
    AFTER_TOMBSTONE,
}

/**
 * Durable cross-process user intent for one model family. A monotonically
 * increasing generation makes every explicit Download/Remove a distinct
 * operation even though Play callbacks do not carry request identifiers.
 */
internal class ModelDeliveryIntentStore(
    private val filesDir: File,
    private val transitionObserver:
        (ModelFamily, ModelDeliveryIntent, ModelDeliveryIntentTransitionPoint) -> Unit =
        { _, _, _ -> },
) {
    fun recordDownload(family: ModelFamily): Long =
        ModelDeliveryFileLock.withLock(filesDir, family) {
            val record = nextRecord(family, ModelDeliveryIntent.INSTALL)
            writeRecord(family, record)
            notifyTransition(family, record, ModelDeliveryIntentTransitionPoint.AFTER_CANONICAL_RECORD)
            deleteRequired(ModelDeliveryFileLock.pendingRemovalMarker(filesDir, family))
            notifyTransition(family, record, ModelDeliveryIntentTransitionPoint.AFTER_PENDING_MARKER)
            deleteRequired(ModelDeliveryFileLock.removalTombstone(filesDir, family))
            notifyTransition(family, record, ModelDeliveryIntentTransitionPoint.AFTER_TOMBSTONE)
            record.generation
        }

    fun recordRemoval(family: ModelFamily): Long =
        ModelDeliveryFileLock.withLock(filesDir, family) {
            val record = nextRecord(family, ModelDeliveryIntent.REMOVE)
            writeRecord(family, record)
            notifyTransition(family, record, ModelDeliveryIntentTransitionPoint.AFTER_CANONICAL_RECORD)
            writeGenerationMarker(
                ModelDeliveryFileLock.pendingRemovalMarker(filesDir, family),
                record.generation,
            )
            notifyTransition(family, record, ModelDeliveryIntentTransitionPoint.AFTER_PENDING_MARKER)
            writeGenerationMarker(
                ModelDeliveryFileLock.removalTombstone(filesDir, family),
                record.generation,
            )
            notifyTransition(family, record, ModelDeliveryIntentTransitionPoint.AFTER_TOMBSTONE)
            record.generation
        }

    fun currentIntent(family: ModelFamily): ModelDeliveryIntent? =
        ModelDeliveryFileLock.withLock(filesDir, family) {
            readRecord(family)?.intent
        }

    /**
     * Converges derivative removal markers to the atomically replaced intent
     * record. Marker generations distinguish a completed REMOVE from a newer
     * REMOVE whose marker transition was interrupted.
     */
    fun reconcileMarkers(family: ModelFamily): ModelDeliveryIntent? =
        ModelDeliveryFileLock.withLock(filesDir, family) {
            val record = readRecord(family)
            when (record?.intent) {
                ModelDeliveryIntent.REMOVE -> reconcileRemovalMarkers(family, record.generation)
                ModelDeliveryIntent.INSTALL -> {
                    deleteRequired(ModelDeliveryFileLock.pendingRemovalMarker(filesDir, family))
                    deleteRequired(ModelDeliveryFileLock.removalTombstone(filesDir, family))
                }
                null -> Unit
            }
            record?.intent
        }

    fun provisioningAllowed(family: ModelFamily): Boolean =
        ModelDeliveryFileLock.withLock(filesDir, family) {
            !ModelDeliveryFileLock.isRemovalAuthoritative(filesDir, family, lockHeld = true) &&
                readRecord(family)?.intent != ModelDeliveryIntent.REMOVE
        }

    private fun nextRecord(family: ModelFamily, intent: ModelDeliveryIntent): DeliveryIntentRecord {
        val current = readRecord(family)
        return DeliveryIntentRecord(
            schema = SCHEMA,
            generation = (current?.generation ?: 0L) + 1L,
            intent = intent,
        )
    }

    private fun readRecord(family: ModelFamily): DeliveryIntentRecord? =
        readRecord(ModelDeliveryFileLock.intentFile(filesDir, family))

    private fun reconcileRemovalMarkers(family: ModelFamily, generation: Long) {
        val pending = ModelDeliveryFileLock.pendingRemovalMarker(filesDir, family)
        val tombstone = ModelDeliveryFileLock.removalTombstone(filesDir, family)
        val tombstoneGeneration = readMarkerGeneration(tombstone)
        val pendingGeneration = readMarkerGeneration(pending)
        if (tombstoneGeneration != generation) {
            writeGenerationMarker(pending, generation)
            writeGenerationMarker(tombstone, generation)
        } else if (pending.exists() && pendingGeneration != generation) {
            deleteRequired(pending)
        }
    }

    private fun notifyTransition(
        family: ModelFamily,
        record: DeliveryIntentRecord,
        point: ModelDeliveryIntentTransitionPoint,
    ) {
        transitionObserver(family, record.intent, point)
    }

    private fun writeGenerationMarker(file: File, generation: Long) {
        file.parentFile?.let { parent ->
            check(parent.isDirectory || parent.mkdirs()) {
                "Could not create model delivery state directory"
            }
        }
        FileOutputStream(file, false).use { output ->
            output.write(generation.toString().toByteArray())
            output.flush()
            runCatching { output.fd.sync() }
        }
    }

    private fun readMarkerGeneration(file: File): Long? {
        if (!file.isFile) return null
        return runCatching { file.readText().trim().toLong() }.getOrNull()
    }

    private fun writeRecord(family: ModelFamily, record: DeliveryIntentRecord) {
        val target = ModelDeliveryFileLock.intentFile(filesDir, family)
        target.parentFile?.let { parent ->
            check(parent.isDirectory || parent.mkdirs()) {
                "Could not create model delivery state directory"
            }
        }
        val partial = File(target.parentFile, "${target.name}.partial")
        try {
            FileOutputStream(partial).use { output ->
                output.write(Json.encodeToString(DeliveryIntentRecord.serializer(), record).toByteArray())
                output.flush()
                runCatching { output.fd.sync() }
            }
            try {
                Files.move(
                    partial.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(partial.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (error: IOException) {
            partial.delete()
            throw error
        }
    }

    private fun deleteRequired(file: File) {
        check(file.delete() || !file.exists()) {
            "Could not clear model delivery intent marker"
        }
    }

    internal companion object {
        private const val SCHEMA = 1

        fun canonicalIntent(filesDir: File, family: ModelFamily): ModelDeliveryIntent? =
            readRecord(ModelDeliveryFileLock.intentFile(filesDir, family))?.intent

        private fun readRecord(file: File): DeliveryIntentRecord? {
            if (!file.isFile) return null
            return runCatching {
                Json.decodeFromString<DeliveryIntentRecord>(file.readText())
                    .takeIf { it.schema == SCHEMA && it.generation > 0L }
            }.getOrNull()
        }
    }
}

@Serializable
private data class DeliveryIntentRecord(
    val schema: Int,
    val generation: Long,
    val intent: ModelDeliveryIntent,
)
