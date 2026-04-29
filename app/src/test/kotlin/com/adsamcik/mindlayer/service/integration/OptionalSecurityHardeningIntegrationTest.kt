package com.adsamcik.mindlayer.service.integration

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.res.AssetManager
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.mindlayer.service.engine.EngineManager
import com.adsamcik.mindlayer.service.security.AllowlistStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.TestName
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class OptionalSecurityHardeningIntegrationTest {

    @get:Rule
    val testName = TestName()

    private lateinit var appContext: Context
    private lateinit var allowlistDirName: String
    private lateinit var modelRoot: File
    private lateinit var filesDir: File
    private lateinit var cacheDir: File
    private lateinit var externalFilesDir: File

    @Before
    fun setUp() {
        appContext = ApplicationProvider.getApplicationContext()
        val testId = testName.methodName.sanitizedForPath()
        allowlistDirName = "integration_allowlist_$testId"
        allowlistDir().deleteRecursively()

        modelRoot = File(
            System.getProperty("java.io.tmpdir"),
            "mindlayer-model-integration-$testId",
        ).also {
            it.deleteRecursively()
            it.mkdirs()
        }
        filesDir = File(modelRoot, "files").also { it.mkdirs() }
        cacheDir = File(modelRoot, "cache").also { it.mkdirs() }
        externalFilesDir = File(modelRoot, "external").also { it.mkdirs() }
    }

    @After
    fun tearDown() {
        allowlistDir().deleteRecursively()
        modelRoot.deleteRecursively()
        unmockkAll()
    }

    @Test
    fun `pending approval written by service store is approved by dashboard store and authorizes service store`() {
        val serviceStore = AllowlistStore(appContext, allowlistDirName)
        val dashboardStore = AllowlistStore(appContext, allowlistDirName)

        serviceStore.recordPending(
            pkg = "com.adsamcik.firstparty.client",
            sigSha256 = "abc123",
            displayName = "First-party Client",
        )

        val pending = dashboardStore.listPending()
        assertEquals(1, pending.size)
        assertEquals("com.adsamcik.firstparty.client", pending.single().packageName)
        assertEquals("First-party Client", pending.single().displayName)

        dashboardStore.approve(
            pkg = pending.single().packageName,
            sigSha256 = pending.single().signingCertSha256,
            displayName = pending.single().displayName,
        )

        assertTrue(serviceStore.isAllowed("com.adsamcik.firstparty.client", "abc123"))
        assertTrue(serviceStore.listPending().isEmpty())
    }

    @Test
    fun `tampered shared allowlist blocks service store until dashboard reapproves`() {
        val dashboardStore = AllowlistStore(appContext, allowlistDirName)
        val serviceStore = AllowlistStore(appContext, allowlistDirName)
        dashboardStore.approve("com.adsamcik.firstparty.client", "old-sig", "First-party")
        assertTrue(serviceStore.isAllowed("com.adsamcik.firstparty.client", "old-sig"))

        val entriesFile = File(allowlistDir(), "entries.json")
        val envelope = JSONObject(entriesFile.readText())
        envelope.getJSONArray("entries")
            .getJSONObject(0)
            .put("sig", "evil-sig")
        entriesFile.writeText(envelope.toString())

        assertFalse(serviceStore.isAllowed("com.adsamcik.firstparty.client", "evil-sig"))
        assertFalse(serviceStore.isAllowed("com.adsamcik.firstparty.client", "old-sig"))

        dashboardStore.approve("com.adsamcik.firstparty.client", "new-sig", "First-party")

        assertTrue(serviceStore.isAllowed("com.adsamcik.firstparty.client", "new-sig"))
    }

    @Test
    fun `engine manager release context selects largest verified model through registry`() {
        val smallBytes = "small model".toByteArray()
        val bigBytes = "larger verified model".toByteArray()
        val small = File(filesDir, "small.litertlm").apply { writeBytes(smallBytes) }
        val big = File(filesDir, "big.litertlm").apply { writeBytes(bigBytes) }
        File(filesDir, "small.litertlm.sha256").writeText(sha256(smallBytes))
        File(filesDir, "big.litertlm.sha256").writeText(sha256(bigBytes))

        val manager = EngineManager(modelContext(debuggable = false))

        assertEquals(big.absolutePath, manager.modelPath)
        assertTrue(big.length() > small.length())
    }

    @Test
    fun `engine manager release context rejects unverified discovered model`() {
        File(filesDir, EngineManager.DEFAULT_MODEL_FILENAME).writeText("unverified model")
        val manager = EngineManager(modelContext(debuggable = false))

        try {
            manager.modelPath
            fail("Expected missing-model failure for unverified release model")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("litertlm"))
        }
    }

    @Test
    fun `engine manager release context accepts verified ai pack extraction`() {
        val context = modelContext(
            debuggable = false,
            manifest = """
                {
                  "models": [
                    {
                      "filename": "${EngineManager.DEFAULT_MODEL_FILENAME}",
                      "sha256": "${sha256(ByteArray(0))}",
                      "sizeBytes": 1
                    }
                  ]
                }
            """.trimIndent(),
            assetNames = arrayOf(EngineManager.DEFAULT_MODEL_FILENAME),
            assetContents = mapOf(EngineManager.DEFAULT_MODEL_FILENAME to ByteArray(0)),
        )
        val manager = EngineManager(context)

        try {
            manager.modelPath
            fail("Expected size-mismatch failure before corrected manifest")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("litertlm"))
        }

        val corrected = EngineManager(
            modelContext(
                debuggable = false,
                manifest = """
                    {
                      "models": [
                        {
                          "filename": "${EngineManager.DEFAULT_MODEL_FILENAME}",
                          "sha256": "${sha256(ByteArray(0))}",
                          "sizeBytes": 0
                        }
                      ]
                    }
                """.trimIndent(),
                assetNames = arrayOf(EngineManager.DEFAULT_MODEL_FILENAME),
                assetContents = mapOf(EngineManager.DEFAULT_MODEL_FILENAME to ByteArray(0)),
            ),
        )

        assertEquals(
            File(filesDir, EngineManager.DEFAULT_MODEL_FILENAME).absolutePath,
            corrected.modelPath,
        )
    }

    private fun allowlistDir(): File = File(appContext.filesDir, allowlistDirName)

    private fun modelContext(
        debuggable: Boolean,
        manifest: String? = null,
        assetNames: Array<String> = emptyArray(),
        assetContents: Map<String, ByteArray> = emptyMap(),
    ): Context {
        val appInfo = ApplicationInfo().apply {
            flags = if (debuggable) ApplicationInfo.FLAG_DEBUGGABLE else 0
            nativeLibraryDir = File(modelRoot, "native").also { it.mkdirs() }.absolutePath
        }
        val assetManager = mockk<AssetManager> {
            every { list("") } returns assetNames
            every { open(any<String>()) } answers {
                val name = firstArg<String>()
                val bytes = when (name) {
                    "model_integrity.json" -> manifest?.encodeToByteArray()
                        ?: throw IOException("manifest absent")
                    else -> assetContents[name] ?: throw IOException("asset absent: $name")
                }
                ByteArrayInputStream(bytes)
            }
        }
        return mockk {
            every { filesDir } returns this@OptionalSecurityHardeningIntegrationTest.filesDir
            every { getExternalFilesDir(null) } returns externalFilesDir
            every { cacheDir } returns this@OptionalSecurityHardeningIntegrationTest.cacheDir
            every { applicationInfo } returns appInfo
            every { assets } returns assetManager
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun String.sanitizedForPath(): String =
        replace(Regex("[^A-Za-z0-9_.-]"), "_")
}
