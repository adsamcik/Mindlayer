package com.adsamcik.mindlayer.service.modeldelivery

import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class VerifiedModelMaterializerTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        root = File(
            ApplicationProvider.getApplicationContext<android.content.Context>().filesDir,
            "model-delivery-materializer-test",
        ).apply {
            deleteRecursively()
            mkdirs()
        }
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `chat streams verified fragments to atomic final file and writes marker last`() {
        val full = "hello on-demand model".toByteArray()
        val first = full.copyOfRange(0, 8)
        val second = full.copyOfRange(8, full.size)
        val fullHash = sha256(full)
        val partOne = pack("gemma_model", first, 1, fullHash)
        val partTwo = pack("gemma_model_part_2", second, 2, fullHash)
        val materializer = VerifiedModelMaterializer(
            filesDir = root,
            releaseBuild = true,
            pinnedSha256 = { filename -> if (filename == "gemma-4-E2B-it.litertlm") fullHash else null },
        )

        val result = materializer.materialize(
            family = ModelFamily.CHAT,
            packAssetDirectories = mapOf(
                "gemma_model" to partOne,
                "gemma_model_part_2" to partTwo,
            ),
        )

        assertTrue(result is MaterializationResult.Installed)
        val familyDir = File(root, "model_delivery/chat")
        assertEquals("hello on-demand model", File(familyDir, "gemma-4-E2B-it.litertlm").readText())
        assertTrue(File(familyDir, "installed.json").isFile)
        assertFalse(File(familyDir, "gemma-4-E2B-it.litertlm.partial").exists())
    }

    @Test
    fun `independent materializers reconstruct chat only once`() {
        val full = "hello on-demand model".toByteArray()
        val fullHash = sha256(full)
        val packs = mapOf(
            "gemma_model" to pack(
                "gemma_model",
                full.copyOfRange(0, 8),
                1,
                fullHash,
            ),
            "gemma_model_part_2" to pack(
                "gemma_model_part_2",
                full.copyOfRange(8, full.size),
                2,
                fullHash,
            ),
        )
        val publications = AtomicInteger()
        val materializers = List(2) {
            VerifiedModelMaterializer(
                filesDir = root,
                releaseBuild = true,
                pinnedSha256 = { filename ->
                    if (filename == "gemma-4-E2B-it.litertlm") fullHash else null
                },
                publicationStarted = { publications.incrementAndGet() },
            )
        }

        val results = materializeConcurrently(materializers, ModelFamily.CHAT, packs)

        assertEquals(1, results.count { it == MaterializationResult.Installed })
        assertEquals(1, results.count { it == MaterializationResult.AlreadyInstalled })
        assertEquals(1, publications.get())
        assertEquals(
            "hello on-demand model",
            File(root, "model_delivery/chat/gemma-4-E2B-it.litertlm").readText(),
        )
    }

    @Test
    fun `concurrent materializers repair corrupt bytes only once`() {
        val full = "hello on-demand model".toByteArray()
        val fullHash = sha256(full)
        val packs = mapOf(
            "gemma_model" to pack(
                "gemma_model",
                full.copyOfRange(0, 8),
                1,
                fullHash,
            ),
            "gemma_model_part_2" to pack(
                "gemma_model_part_2",
                full.copyOfRange(8, full.size),
                2,
                fullHash,
            ),
        )
        val publications = AtomicInteger()
        val materializers = List(2) {
            VerifiedModelMaterializer(
                filesDir = root,
                releaseBuild = true,
                pinnedSha256 = { filename ->
                    if (filename == "gemma-4-E2B-it.litertlm") fullHash else null
                },
                publicationStarted = { publications.incrementAndGet() },
            )
        }
        assertEquals(
            MaterializationResult.Installed,
            materializers.first().materialize(ModelFamily.CHAT, packs),
        )
        val installed = File(root, "model_delivery/chat/gemma-4-E2B-it.litertlm")
        installed.writeBytes(ByteArray(full.size) { 0x5a })

        val results = materializeConcurrently(materializers, ModelFamily.CHAT, packs)

        assertEquals(1, results.count { it == MaterializationResult.Installed })
        assertEquals(1, results.count { it == MaterializationResult.AlreadyInstalled })
        assertEquals(2, publications.get())
        assertEquals("hello on-demand model", installed.readText())
    }

    @Test
    fun `remove authority blocks every concurrent materializer`() {
        val full = "hello on-demand model".toByteArray()
        val fullHash = sha256(full)
        val packs = mapOf(
            "gemma_model" to pack(
                "gemma_model",
                full.copyOfRange(0, 8),
                1,
                fullHash,
            ),
            "gemma_model_part_2" to pack(
                "gemma_model_part_2",
                full.copyOfRange(8, full.size),
                2,
                fullHash,
            ),
        )
        ModelDeliveryIntentStore(root).recordRemoval(ModelFamily.CHAT)
        val publications = AtomicInteger()
        val materializers = List(2) {
            VerifiedModelMaterializer(
                filesDir = root,
                releaseBuild = true,
                pinnedSha256 = { filename ->
                    if (filename == "gemma-4-E2B-it.litertlm") fullHash else null
                },
                publicationStarted = { publications.incrementAndGet() },
            )
        }

        val results = materializeConcurrently(materializers, ModelFamily.CHAT, packs)

        assertEquals(2, results.count { it is MaterializationResult.Failed })
        assertEquals(0, publications.get())
        assertFalse(File(root, "model_delivery/chat/installed.json").exists())
    }

    @Test
    fun `installed marker is invalidated when the pinned hash changes`() {
        val full = "hello on-demand model".toByteArray()
        val fullHash = sha256(full)
        var pinnedHash = fullHash
        val materializer = VerifiedModelMaterializer(
            filesDir = root,
            releaseBuild = true,
            pinnedSha256 = { filename ->
                if (filename == "gemma-4-E2B-it.litertlm") pinnedHash else null
            },
        )
        assertTrue(
            materializer.materialize(
                family = ModelFamily.CHAT,
                packAssetDirectories = mapOf(
                    "gemma_model" to pack(
                        "gemma_model",
                        full.copyOfRange(0, 8),
                        1,
                        fullHash,
                    ),
                    "gemma_model_part_2" to pack(
                        "gemma_model_part_2",
                        full.copyOfRange(8, full.size),
                        2,
                        fullHash,
                    ),
                ),
            ) is MaterializationResult.Installed,
        )
        assertTrue(materializer.isMarkedInstalled(ModelFamily.CHAT))

        pinnedHash = "0".repeat(64)

        assertFalse(materializer.isMarkedInstalled(ModelFamily.CHAT))
    }

    @Test
    fun `same-size installed byte mutation fails validation and can reprovision`() {
        val full = "hello on-demand model".toByteArray()
        val fullHash = sha256(full)
        val partOne = pack("gemma_model", full.copyOfRange(0, 8), 1, fullHash)
        val partTwo = pack("gemma_model_part_2", full.copyOfRange(8, full.size), 2, fullHash)
        val materializer = VerifiedModelMaterializer(
            filesDir = root,
            releaseBuild = true,
            pinnedSha256 = { filename ->
                if (filename == "gemma-4-E2B-it.litertlm") fullHash else null
            },
        )
        val packs = mapOf(
            "gemma_model" to partOne,
            "gemma_model_part_2" to partTwo,
        )
        assertTrue(materializer.materialize(ModelFamily.CHAT, packs) is MaterializationResult.Installed)
        assertTrue(materializer.isMarkedInstalled(ModelFamily.CHAT))

        val installed = File(root, "model_delivery/chat/gemma-4-E2B-it.litertlm")
        val originalModified = installed.lastModified()
        installed.writeBytes(ByteArray(full.size) { 0x5a })
        installed.setLastModified(originalModified + 2_000L)

        assertFalse(materializer.isMarkedInstalled(ModelFamily.CHAT))
        assertFalse(File(root, "model_delivery/chat/installed.json").exists())

        assertTrue(materializer.materialize(ModelFamily.CHAT, packs) is MaterializationResult.Installed)
        assertTrue(materializer.isMarkedInstalled(ModelFamily.CHAT))
        assertEquals("hello on-demand model", installed.readText())
    }

    @Test
    fun `forced validation catches same-size corruption with restored fingerprint metadata`() {
        val full = "hello on-demand model".toByteArray()
        val fullHash = sha256(full)
        val packs = mapOf(
            "gemma_model" to pack(
                "gemma_model",
                full.copyOfRange(0, 8),
                1,
                fullHash,
            ),
            "gemma_model_part_2" to pack(
                "gemma_model_part_2",
                full.copyOfRange(8, full.size),
                2,
                fullHash,
            ),
        )
        val materializer = VerifiedModelMaterializer(
            filesDir = root,
            releaseBuild = true,
            pinnedSha256 = { filename ->
                if (filename == "gemma-4-E2B-it.litertlm") fullHash else null
            },
        )
        assertTrue(materializer.materialize(ModelFamily.CHAT, packs) is MaterializationResult.Installed)
        val installed = File(root, "model_delivery/chat/gemma-4-E2B-it.litertlm")
        val originalModified = installed.lastModified()

        installed.writeBytes(ByteArray(full.size) { 0x5a })
        assertTrue(installed.setLastModified(originalModified))
        assertEquals(originalModified, installed.lastModified())
        assertTrue(materializer.isMarkedInstalled(ModelFamily.CHAT))

        assertFalse(materializer.isMarkedInstalled(ModelFamily.CHAT, forceValidation = true))
        assertFalse(File(root, "model_delivery/chat/installed.json").exists())
    }

    @Test
    fun `validation cache rehashes after max age but not while fresh`() {
        val full = "hello on-demand model".toByteArray()
        val fullHash = sha256(full)
        var nowMs = 1_000L
        var digestCalls = 0
        val materializer = VerifiedModelMaterializer(
            filesDir = root,
            releaseBuild = true,
            pinnedSha256 = { filename ->
                if (filename == "gemma-4-E2B-it.litertlm") fullHash else null
            },
            validationClockMs = { nowMs },
            validationMaxAgeMs = 100L,
            installedDigest = { file ->
                digestCalls += 1
                sha256(file)
            },
        )
        val packs = mapOf(
            "gemma_model" to pack(
                "gemma_model",
                full.copyOfRange(0, 8),
                1,
                fullHash,
            ),
            "gemma_model_part_2" to pack(
                "gemma_model_part_2",
                full.copyOfRange(8, full.size),
                2,
                fullHash,
            ),
        )
        assertTrue(materializer.materialize(ModelFamily.CHAT, packs) is MaterializationResult.Installed)

        assertTrue(materializer.isMarkedInstalled(ModelFamily.CHAT))
        assertEquals(0, digestCalls)

        nowMs += 101L
        assertTrue(materializer.isMarkedInstalled(ModelFamily.CHAT))
        assertEquals(1, digestCalls)

        assertTrue(materializer.isMarkedInstalled(ModelFamily.CHAT))
        assertEquals(1, digestCalls)
    }

    @Test
    fun `remove deletes delivered and legacy artifacts and writes tombstone`() {
        val legacy = File(root, "gemma-4-E2B-it.litertlm").apply { writeText("legacy") }
        val delivered = File(root, "model_delivery/chat").apply { mkdirs() }
        File(delivered, "gemma-4-E2B-it.litertlm").writeText("delivered")
        val materializer = VerifiedModelMaterializer(
            filesDir = root,
            releaseBuild = true,
            pinnedSha256 = { null },
        )

        materializer.remove(ModelFamily.CHAT)

        assertFalse(legacy.exists())
        assertFalse(delivered.exists())
        assertTrue(ModelDeliveryFileLock.removalTombstone(root, ModelFamily.CHAT).isFile)
        assertFalse(materializer.isMarkedInstalled(ModelFamily.CHAT))
    }

    private fun pack(pack: String, bytes: ByteArray, index: Int, fullHash: String): File {
        return File(root, pack).apply {
            mkdirs()
            val fragmentName = "gemma-4-E2B-it.litertlm.part$index"
            File(this, fragmentName).writeBytes(bytes)
            File(this, "gemma_part_${index}_integrity.json").writeText(
                """
                {
                  "schema": 1,
                  "index": $index,
                  "totalParts": 2,
                  "fragmentFile": "$fragmentName",
                  "fragmentByteSize": ${bytes.size},
                  "fragmentSha256": "${sha256(bytes)}",
                  "fullFile": "gemma-4-E2B-it.litertlm",
                  "fullSha256": "$fullHash"
                }
                """.trimIndent(),
            )
        }
    }

    private fun materializeConcurrently(
        materializers: List<VerifiedModelMaterializer>,
        family: ModelFamily,
        packs: Map<String, File>,
    ): List<MaterializationResult> {
        val ready = CountDownLatch(materializers.size)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(materializers.size)
        return try {
            val futures = materializers.map { materializer ->
                executor.submit<MaterializationResult> {
                    ready.countDown()
                    check(start.await(5, TimeUnit.SECONDS))
                    materializer.materialize(family, packs)
                }
            }
            check(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            futures.map { it.get(10, TimeUnit.SECONDS) }
        } finally {
            start.countDown()
            executor.shutdownNow()
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun sha256(file: File): String = sha256(file.readBytes())
}
