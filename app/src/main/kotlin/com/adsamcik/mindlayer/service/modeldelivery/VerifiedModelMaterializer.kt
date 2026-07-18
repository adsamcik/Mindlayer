package com.adsamcik.mindlayer.service.modeldelivery

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

sealed class MaterializationResult {
    /** This caller verified and published new model artifacts. */
    data object Installed : MaterializationResult()

    /** Another caller already published artifacts that passed forced validation. */
    data object AlreadyInstalled : MaterializationResult()

    data object Failed : MaterializationResult()
}

interface ModelArtifactMaterializer {
    /**
     * Validates any current installation and, only when necessary, publishes
     * replacement artifacts while holding the shared family lock.
     */
    fun materialize(
        family: ModelFamily,
        packAssetDirectories: Map<String, File>,
    ): MaterializationResult

    fun isMarkedInstalled(family: ModelFamily, forceValidation: Boolean = false): Boolean

    fun remove(family: ModelFamily, lockHeld: Boolean = false)
}

/**
 * Verifies assets supplied by Play and atomically publishes only complete model
 * artifacts below `filesDir/model_delivery`. This class never makes a model
 * filename visible before its bytes and hash have been verified.
 */
class VerifiedModelMaterializer(
    private val filesDir: File,
    private val releaseBuild: Boolean,
    private val pinnedSha256: (String) -> String?,
    private val catalog: (ModelFamily) -> ModelFamilySpec = ModelDeliveryCatalog::family,
    private val validationClockMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val validationMaxAgeMs: Long = DEFAULT_VALIDATION_MAX_AGE_MS,
    private val installedDigest: (File) -> String = ::sha256,
    private val publicationStarted: (ModelFamily) -> Unit = {},
) : ModelArtifactMaterializer {
    companion object {
        private const val MARKER_FILE = "installed.json"
        private const val DEFAULT_VALIDATION_MAX_AGE_MS = 24L * 60L * 60L * 1_000L
        private val SHA256 = Regex("^[0-9a-f]{64}$", RegexOption.IGNORE_CASE)
    }

    private val validatedFingerprints = ConcurrentHashMap<ModelFamily, CachedValidation>()

    init {
        require(validationMaxAgeMs >= 0L) { "Validation max age must not be negative" }
    }

    override fun materialize(
        family: ModelFamily,
        packAssetDirectories: Map<String, File>,
    ): MaterializationResult = ModelDeliveryFileLock.withLock(filesDir, family) {
        runCatching {
            check(!ModelDeliveryFileLock.isRemovalAuthoritative(filesDir, family, lockHeld = true)) {
                "Model removal is authoritative"
            }
            val spec = catalog(family)
            if (isMarkedInstalledLocked(family, spec, forceValidation = true)) {
                return@runCatching MaterializationResult.AlreadyInstalled
            }
            require(spec.packNames.all(packAssetDirectories::containsKey)) { "Required model pack is unavailable" }
            val destination = familyDir(family).apply { mkdirs() }
            cleanPartials(destination)
            File(destination, MARKER_FILE).delete()
            validatedFingerprints.remove(family)
            publicationStarted(family)

            val installedFiles = when (family) {
                ModelFamily.CHAT -> materializeChat(spec, packAssetDirectories, destination)
                ModelFamily.EMBEDDINGS, ModelFamily.OCR ->
                    materializeFiles(spec, packAssetDirectories, destination)
            }
            check(!ModelDeliveryFileLock.isRemovalAuthoritative(filesDir, family, lockHeld = true)) {
                "Model removal became authoritative"
            }
            writeMarker(destination, family, installedFiles)
            cacheCurrentFingerprint(family, destination, spec)
            MaterializationResult.Installed
        }.getOrElse {
            val spec = catalog(family)
            cleanPublishedArtifacts(familyDir(family), spec)
            validatedFingerprints.remove(family)
            MaterializationResult.Failed
        }
    }

    override fun isMarkedInstalled(family: ModelFamily, forceValidation: Boolean): Boolean =
        ModelDeliveryFileLock.withLock(filesDir, family) {
            isMarkedInstalledLocked(family, catalog(family), forceValidation)
        }

    override fun remove(family: ModelFamily, lockHeld: Boolean) {
        val removeArtifacts = {
            validatedFingerprints.remove(family)
            val spec = catalog(family)
            val targets = listOf(familyDir(family)) +
                (spec.files.map(ModelArtifact::filename) + listOfNotNull(spec.outputFileName))
                    .map { filename -> File(filesDir, filename) }
            targets.forEach { target ->
                check(!target.exists() || target.deleteRecursively()) {
                    "Could not delete model artifact"
                }
                check(!target.exists()) {
                    "Model artifact remained after deletion"
                }
            }
            val tombstone = ModelDeliveryFileLock.removalTombstone(filesDir, family)
            tombstone.parentFile?.let { parent ->
                check(parent.isDirectory || parent.mkdirs()) {
                    "Could not create model delivery state directory"
                }
            }
            check(tombstone.exists() || tombstone.createNewFile()) {
                "Could not persist model removal tombstone"
            }
        }
        if (lockHeld) {
            removeArtifacts()
        } else {
            ModelDeliveryFileLock.withLock(filesDir, family, removeArtifacts)
        }
    }

    private fun materializeChat(
        spec: ModelFamilySpec,
        packDirectories: Map<String, File>,
        destination: File,
    ): List<InstalledFile> {
        val fragments = spec.fragments.map { expected ->
            val root = checkNotNull(packDirectories[expected.packName])
            val metadata = parseFragmentMetadata(File(root, expected.metadataFilename))
            require(metadata.index == expected.index && metadata.totalParts == expected.totalParts) {
                "Unexpected fragment order"
            }
            require(metadata.fragmentFile.isSafeModelFilename()) { "Unexpected fragment name" }
            require(metadata.fullFile == spec.outputFileName && metadata.fullFile.isSafeModelFilename()) {
                "Unexpected full model name"
            }
            require(metadata.fragmentSha256.isSha256() && metadata.fullSha256.isSha256()) {
                "Invalid model digest metadata"
            }
            require(metadata.fragmentByteSize > 0L) { "Invalid fragment byte size" }
            metadata to File(root, metadata.fragmentFile)
        }.sortedBy { it.first.index }

        require(fragments.map { it.first.index } == listOf(1, 2)) { "Gemma parts must be contiguous" }
        val fullName = checkNotNull(spec.outputFileName)
        val fullHash = fragments.first().first.fullSha256.lowercase()
        require(fragments.all { it.first.fullSha256.equals(fullHash, ignoreCase = true) }) {
            "Gemma parts disagree about full digest"
        }
        val pinned = pinnedSha256(fullName)?.lowercase()?.takeIf { it.isSha256() }
        if (releaseBuild) require(pinned != null && pinned == fullHash) { "Missing or mismatched release model pin" }
        val expectedFullHash = pinned ?: fullHash

        val partial = File(destination, "$fullName.partial")
        val fullDigest = MessageDigest.getInstance("SHA-256")
        BufferedOutputStream(partial.outputStream()).use { output ->
            fragments.forEach { (metadata, file) ->
                require(file.isFile && file.length() == metadata.fragmentByteSize) { "Fragment size mismatch" }
                val fragmentDigest = MessageDigest.getInstance("SHA-256")
                var copied = 0L
                BufferedInputStream(file.inputStream()).use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        fragmentDigest.update(buffer, 0, read)
                        fullDigest.update(buffer, 0, read)
                        copied += read
                    }
                }
                require(copied == metadata.fragmentByteSize) { "Fragment size changed while copying" }
                require(fragmentDigest.digest().hex() == metadata.fragmentSha256.lowercase()) {
                    "Fragment digest mismatch"
                }
            }
        }
        require(fullDigest.digest().hex() == expectedFullHash) { "Full model digest mismatch" }
        val sizeBytes = partial.length()
        atomicPublish(partial, File(destination, fullName))
        return listOf(InstalledFile(fullName, sizeBytes, expectedFullHash))
    }

    private fun materializeFiles(
        spec: ModelFamilySpec,
        packDirectories: Map<String, File>,
        destination: File,
    ): List<InstalledFile> {
        val root = checkNotNull(packDirectories[spec.packNames.single()])
        val metadata = parseFileMetadata(root)
        val published = mutableListOf<File>()
        val installedFiles = mutableListOf<InstalledFile>()
        try {
            spec.files.forEach { artifact ->
                require(artifact.filename.isSafeModelFilename()) { "Unexpected model filename" }
                val source = File(root, artifact.filename)
                require(source.isFile) { "Expected model asset is unavailable" }
                val manifestHash = metadata[artifact.filename]
                val pinned = pinnedSha256(artifact.filename)?.lowercase()?.takeIf { it.isSha256() }
                if (releaseBuild) require(pinned != null) { "Missing release model pin" }
                if (pinned != null && manifestHash != null) {
                    require(pinned == manifestHash) { "Pack metadata disagrees with release pin" }
                }
                val expectedHash = pinned ?: manifestHash
                require(expectedHash != null && expectedHash.isSha256()) { "Missing model digest metadata" }

                val partial = File(destination, "${artifact.filename}.partial")
                val actual = copyAndDigest(source, partial)
                require(actual == expectedHash.lowercase()) { "Model digest mismatch" }
                val sizeBytes = partial.length()
                val target = File(destination, artifact.filename)
                atomicPublish(partial, target)
                published += target
                installedFiles += InstalledFile(artifact.filename, sizeBytes, actual)
            }
        } catch (error: Throwable) {
            published.forEach(File::delete)
            throw error
        }
        return installedFiles
    }

    private fun writeMarker(
        destination: File,
        family: ModelFamily,
        installedFiles: List<InstalledFile>,
    ) {
        val marker = InstalledMarker(
            schema = 1,
            family = family.name,
            files = installedFiles.onEach { installed ->
                require(File(destination, installed.filename).isFile) { "Model publish incomplete" }
            },
        )
        val partial = File(destination, "$MARKER_FILE.partial")
        partial.writeText(Json.encodeToString(InstalledMarker.serializer(), marker))
        atomicPublish(partial, File(destination, MARKER_FILE))
    }

    private fun isMarkedInstalledLocked(
        family: ModelFamily,
        spec: ModelFamilySpec,
        forceValidation: Boolean,
    ): Boolean {
        if (ModelDeliveryFileLock.isRemovalAuthoritative(filesDir, family, lockHeld = true)) {
            return false
        }
        val expectedFilenames = (
            spec.files.map(ModelArtifact::filename) + listOfNotNull(spec.outputFileName)
            ).toSet()
        val marker = File(familyDir(family), MARKER_FILE)
        if (!marker.isFile) return false
        val valid = runCatching {
            val installed = Json.decodeFromString<InstalledMarker>(marker.readText())
            val installedByName = installed.files.associateBy(InstalledFile::filename)
            if (
                installed.schema != 1 ||
                installed.family != family.name ||
                installedByName.keys != expectedFilenames
            ) {
                return@runCatching false
            }
            val files = installed.files.map { item ->
                val file = File(familyDir(family), item.filename)
                val currentPin = pinnedSha256(item.filename)
                    ?.lowercase()
                    ?.takeIf { it.isSha256() }
                val expectedHash = when {
                    releaseBuild -> currentPin
                        ?.takeIf { item.sha256.equals(it, ignoreCase = true) }
                    currentPin != null && item.sha256.equals(currentPin, ignoreCase = true) -> currentPin
                    currentPin == null && item.sha256.isSha256() -> item.sha256.lowercase()
                    else -> null
                } ?: return@runCatching false
                if (!file.isFile || file.length() != item.sizeBytes) return@runCatching false
                ValidatedFile(
                    file = file,
                    expectedHash = expectedHash,
                    fingerprint = fingerprint(file) ?: return@runCatching false,
                )
            }
            val fingerprint = ValidationFingerprint(
                marker = fingerprint(marker) ?: return@runCatching false,
                files = files.map { it.fingerprint },
            )
            val nowMs = validationClockMs()
            val cached = validatedFingerprints[family]
            if (
                !forceValidation &&
                cached?.fingerprint == fingerprint &&
                cached.isFresh(nowMs, validationMaxAgeMs)
            ) {
                return@runCatching true
            }
            val digestMatches = files.all { validated ->
                installedDigest(validated.file) == validated.expectedHash
            }
            if (digestMatches) {
                validatedFingerprints[family] = CachedValidation(fingerprint, nowMs)
            }
            digestMatches
        }.getOrDefault(false)
        if (!valid) {
            validatedFingerprints.remove(family)
            marker.delete()
        }
        return valid
    }

    private fun parseFragmentMetadata(file: File): FragmentMetadata {
        require(file.isFile) { "Fragment metadata is unavailable" }
        return Json.decodeFromString(FragmentMetadata.serializer(), file.readText())
    }

    private fun parseFileMetadata(root: File): Map<String, String> {
        val manifest = listOf("embedding_model_integrity.json", "paddleocr_model_integrity.json")
            .firstNotNullOfOrNull { name -> File(root, name).takeIf(File::isFile) }
            ?: return emptyMap()
        val json = Json.parseToJsonElement(manifest.readText()) as? JsonObject ?: return emptyMap()
        val models = json["models"] as? JsonArray ?: return emptyMap()
        return models.mapNotNull { element ->
            val objectValue = element as? JsonObject ?: return@mapNotNull null
            val filename = (objectValue["filename"] as? JsonPrimitive)?.contentOrNull
            val sha = (objectValue["sha256"] as? JsonPrimitive)?.contentOrNull?.lowercase()
            if (filename != null && sha?.isSha256() == true) filename to sha else null
        }.toMap()
    }

    private fun copyAndDigest(source: File, target: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        BufferedInputStream(source.inputStream()).use { input ->
            BufferedOutputStream(target.outputStream()).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    digest.update(buffer, 0, read)
                }
            }
        }
        return digest.digest().hex()
    }

    private fun cleanPartials(directory: File) {
        directory.listFiles()?.filter { it.name.endsWith(".partial") }?.forEach(File::delete)
    }

    private fun cleanPublishedArtifacts(directory: File, spec: ModelFamilySpec) {
        cleanPartials(directory)
        File(directory, MARKER_FILE).delete()
        (spec.files.map(ModelArtifact::filename) + listOfNotNull(spec.outputFileName))
            .forEach { filename -> File(directory, filename).delete() }
    }

    private fun cacheCurrentFingerprint(
        family: ModelFamily,
        directory: File,
        spec: ModelFamilySpec,
    ) {
        val marker = fingerprint(File(directory, MARKER_FILE)) ?: return
        val files = (spec.files.map(ModelArtifact::filename) + listOfNotNull(spec.outputFileName))
            .map { filename -> fingerprint(File(directory, filename)) ?: return }
        validatedFingerprints[family] = CachedValidation(
            fingerprint = ValidationFingerprint(marker, files),
            validatedAtMs = validationClockMs(),
        )
    }

    private fun familyDir(family: ModelFamily): File =
        ModelDeliveryFileLock.familyDir(filesDir, family)

    private fun atomicPublish(partial: File, target: File) {
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
    }

    private fun String.isSha256(): Boolean = SHA256.matches(this)

    private fun String.isSafeModelFilename(): Boolean =
        matches(Regex("^[A-Za-z0-9_.-]+$")) && !contains("..")

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

    private fun fingerprint(file: File): FileFingerprint? = runCatching {
        val attributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
        FileFingerprint(
            canonicalPath = file.canonicalPath,
            sizeBytes = attributes.size(),
            lastModifiedMs = attributes.lastModifiedTime().toMillis(),
            fileKey = attributes.fileKey()?.toString(),
        )
    }.getOrNull()
}

private data class CachedValidation(
    val fingerprint: ValidationFingerprint,
    val validatedAtMs: Long,
) {
    fun isFresh(nowMs: Long, maxAgeMs: Long): Boolean {
        val ageMs = nowMs - validatedAtMs
        return ageMs in 0L..maxAgeMs
    }
}

private data class ValidatedFile(
    val file: File,
    val expectedHash: String,
    val fingerprint: FileFingerprint,
)

private data class ValidationFingerprint(
    val marker: FileFingerprint,
    val files: List<FileFingerprint>,
)

private data class FileFingerprint(
    val canonicalPath: String,
    val sizeBytes: Long,
    val lastModifiedMs: Long,
    val fileKey: String?,
)

@Serializable
private data class FragmentMetadata(
    val schema: Int,
    val index: Int,
    val totalParts: Int,
    val fragmentFile: String,
    val fragmentByteSize: Long,
    val fragmentSha256: String,
    val fullFile: String,
    val fullSha256: String,
)

@Serializable
private data class InstalledMarker(
    val schema: Int,
    val family: String,
    val files: List<InstalledFile>,
)

@Serializable
private data class InstalledFile(
    val filename: String,
    val sizeBytes: Long,
    val sha256: String,
)

private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    BufferedInputStream(file.inputStream()).use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
