package com.adsamcik.mindlayer.service.engine

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.res.AssetManager
import android.os.Build
import android.util.Log
import com.adsamcik.mindlayer.service.logging.LogRepository
import com.adsamcik.mindlayer.service.logging.MindlayerLog
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * F-080: Robolectric coverage for [EngineManager.isNpuLikelySupported] across
 * the **four** SoC-vendor families now allowlisted (Qualcomm, MediaTek,
 * Google Tensor, Samsung Exynos).
 *
 * **Why a separate test class with the constructor-injected providers
 * instead of extending [EngineManagerTest]:** the existing test uses
 * static-field reflection (`setStaticField(Build.VERSION::class.java,
 * "SDK_INT", …)`) which the Kotlin compiler defeats by inlining the
 * static-final constant at every read site (typically `0` in the
 * android.jar stub). Several of those tests are `@Ignore`'d for that
 * reason — they cannot drive the per-SoC branches reliably. Commit
 * 91afbb5 introduced the `sdkInt: () -> Int` constructor-injection
 * pattern in [ThermalMonitor] to side-step exactly this problem; F-080
 * mirrors that pattern in [EngineManager] and adds `socModel: () -> String`
 * for the same reason.
 *
 * Each test fixes [fakeSdkInt] and [fakeSocModel] to a per-vendor pair,
 * drops a vendor-specific lib into the simulated `nativeLibraryDir`, and
 * asserts [EngineManager.isNpuLikelySupported] returns `true` (positive
 * coverage) or `false` (negative coverage for the no-lib / wrong-lib /
 * unknown-SoC cases).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class EngineManagerNpuSocTest {

    private lateinit var context: Context
    private lateinit var appInfo: ApplicationInfo
    private lateinit var assetManager: AssetManager
    private lateinit var logRepository: LogRepository

    private lateinit var filesDir: File
    private lateinit var nativeLibDir: File

    /** Mutable so each test can set the API level and SoC string explicitly. */
    private var fakeSdkInt: Int = Build.VERSION_CODES.TIRAMISU
    private var fakeSocModel: String = ""

    private fun freshManager(): EngineManager =
        EngineManager(
            context = context,
            logRepository = logRepository,
            sdkInt = { fakeSdkInt },
            socModel = { fakeSocModel },
        )

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        mockkObject(MindlayerLog)

        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
        every { MindlayerLog.d(any(), any(), any(), any()) } returns Unit
        every { MindlayerLog.i(any(), any(), any(), any()) } returns Unit
        every { MindlayerLog.w(any(), any(), any(), any(), any()) } returns Unit
        every { MindlayerLog.e(any(), any(), any(), any(), any()) } returns Unit

        val tmpRoot = File(
            System.getProperty("java.io.tmpdir"),
            "engine-manager-npu-soc-test-${System.nanoTime()}",
        )
        filesDir = File(tmpRoot, "files").apply { mkdirs() }
        nativeLibDir = File(tmpRoot, "nativeLib").apply { mkdirs() }

        appInfo = ApplicationInfo().apply { nativeLibraryDir = nativeLibDir.absolutePath }
        assetManager = mockk(relaxed = true) {
            every { list("") } returns emptyArray()
        }
        context = mockk(relaxed = true) {
            every { filesDir } returns this@EngineManagerNpuSocTest.filesDir
            every { getExternalFilesDir(null) } returns null
            every { cacheDir } returns this@EngineManagerNpuSocTest.filesDir
            every { applicationInfo } returns appInfo
            every { assets } returns assetManager
        }
        logRepository = mockk(relaxed = true)

        // Reset to clean defaults between tests
        fakeSdkInt = Build.VERSION_CODES.TIRAMISU
        fakeSocModel = ""
    }

    @After
    fun tearDown() {
        filesDir.parentFile?.deleteRecursively()
        unmockkAll()
    }

    private fun invokeIsNpuLikelySupported(mgr: EngineManager): Boolean {
        val method = EngineManager::class.java.getDeclaredMethod("isNpuLikelySupported")
        method.isAccessible = true
        return method.invoke(mgr) as Boolean
    }

    private fun writeLib(name: String) {
        File(nativeLibDir, name).writeText("fake-lib-content")
    }

    // ---- API-gate sanity ----------------------------------------------------

    @Test
    fun `API less than 31 is rejected even with valid Tensor SoC and libs`() {
        fakeSdkInt = Build.VERSION_CODES.R // API 30
        fakeSocModel = "Tensor G3"
        writeLib("libtflite_npu_runtime.so")

        assertFalse(
            "Build.SOC_MODEL is API 31+; the API gate must reject earlier runtimes " +
                "regardless of SoC string",
            invokeIsNpuLikelySupported(freshManager()),
        )
    }

    @Test
    fun `empty SOC_MODEL string is rejected even on API 33`() {
        fakeSdkInt = Build.VERSION_CODES.TIRAMISU
        fakeSocModel = ""
        writeLib("libQnnHtp.so")

        assertFalse(invokeIsNpuLikelySupported(freshManager()))
    }

    @Test
    fun `unknown SoC string is rejected even with vendor libs present`() {
        fakeSocModel = "exotic_chip_xyz"
        writeLib("libQnnHtp.so")
        writeLib("libtflite_npu.so")
        writeLib("libnpu_runtime.so")

        assertFalse(invokeIsNpuLikelySupported(freshManager()))
    }

    // ---- Qualcomm (existing coverage, sanity-checked under injection) -------

    @Test
    fun `Qualcomm sm8650 with libQnn lib is supported`() {
        fakeSocModel = "sm8650"
        writeLib("libQnnHtp.so")
        assertTrue(invokeIsNpuLikelySupported(freshManager()))
    }

    @Test
    fun `Qualcomm sub-variant sm8650-AC is supported via base prefix normalization`() {
        // Snapdragon binning suffixes (e.g. sm8650-AC, sm8550AB) string-match
        // differently from the base sm8650; F-080 added normalization to keep
        // them on the NPU path.
        fakeSocModel = "sm8650-AC"
        writeLib("libQnnHtp.so")
        assertTrue(
            "sm8650-AC must normalise to sm8650 and stay NPU-eligible",
            invokeIsNpuLikelySupported(freshManager()),
        )
    }

    @Test
    fun `Qualcomm sm8550 without libQnn lib is rejected`() {
        fakeSocModel = "sm8550"
        // No libs in nativeLibDir
        assertFalse(invokeIsNpuLikelySupported(freshManager()))
    }

    @Test
    fun `Qualcomm sm8650 with only Tensor libs is rejected (vendor-matched detection)`() {
        // F-080: lib detection is now per-vendor. A Qualcomm SoC must have
        // libQnn libs; bundled-but-unrelated Tensor libs do not satisfy.
        fakeSocModel = "sm8650"
        writeLib("libtflite_npu.so")
        writeLib("libgemini_runtime.so")
        assertFalse(invokeIsNpuLikelySupported(freshManager()))
    }

    // ---- MediaTek (existing coverage, sanity-checked) -----------------------

    @Test
    fun `MediaTek mt6989 with libmediatek lib is supported`() {
        fakeSocModel = "mt6989"
        writeLib("libmediatek_npu_runtime.so")
        assertTrue(invokeIsNpuLikelySupported(freshManager()))
    }

    @Test
    fun `MediaTek mt6991 with libdispatch lib is supported`() {
        fakeSocModel = "mt6991"
        writeLib("libdispatch.so")
        assertTrue(invokeIsNpuLikelySupported(freshManager()))
    }

    // ---- Google Tensor — F-080 new coverage ---------------------------------

    @Test
    fun `Tensor G1 SOC_MODEL "Tensor" with libtflite is supported`() {
        // Pixel 6 / 6 Pro / 6a stock vendor.prop reports `ro.soc.model=Tensor`.
        fakeSocModel = "Tensor"
        writeLib("libtflite_npu_runtime.so")
        assertTrue(invokeIsNpuLikelySupported(freshManager()))
    }

    @Test
    fun `Tensor G1 silicon model gs101 with libgemini is supported`() {
        fakeSocModel = "gs101"
        writeLib("libgemini_edgetpu.so")
        assertTrue(invokeIsNpuLikelySupported(freshManager()))
    }

    @Test
    fun `Tensor G2 SOC_MODEL "GS201" with libtflite is supported`() {
        // Pixel 7 / 7 Pro / 7a stock vendor.prop reports `ro.soc.model=GS201`.
        fakeSocModel = "GS201"
        writeLib("libtflite_npu_runtime.so")
        assertTrue(invokeIsNpuLikelySupported(freshManager()))
    }

    @Test
    fun `Tensor G3 SOC_MODEL "Tensor G3" with libtflite is supported`() {
        // Pixel 8 / 8 Pro / 8a stock vendor.prop reports `ro.soc.model=Tensor G3`.
        fakeSocModel = "Tensor G3"
        writeLib("libtflite_npu_runtime.so")
        assertTrue(invokeIsNpuLikelySupported(freshManager()))
    }

    @Test
    fun `Tensor G3 codename zuma is also accepted`() {
        // Some firmware permutations expose the codename instead of the
        // marketing string. Both must be allowlisted.
        fakeSocModel = "zuma"
        writeLib("libgemini_runtime.so")
        assertTrue(invokeIsNpuLikelySupported(freshManager()))
    }

    @Test
    fun `Tensor G4 SOC_MODEL "Tensor G4" with libtflite is supported`() {
        // Pixel 9 / 9 Pro / 9 Pro Fold / 9a stock vendor.prop reports
        // `ro.soc.model=Tensor G4`.
        fakeSocModel = "Tensor G4"
        writeLib("libtflite_npu_runtime.so")
        assertTrue(invokeIsNpuLikelySupported(freshManager()))
    }

    @Test
    fun `Tensor G4 codename zumapro is also accepted`() {
        fakeSocModel = "zumapro"
        writeLib("libtflite_npu_runtime.so")
        assertTrue(invokeIsNpuLikelySupported(freshManager()))
    }

    @Test
    fun `Tensor G4 silicon model gs401 is also accepted`() {
        fakeSocModel = "gs401"
        writeLib("libgemini_runtime.so")
        assertTrue(invokeIsNpuLikelySupported(freshManager()))
    }

    @Test
    fun `Tensor SoC without libtflite or libgemini is rejected`() {
        // Forward-looking guard: until LiteRT-LM ships the Tensor NPU
        // runtime, the lib check must keep these devices on the GPU/CPU
        // path even though the SoC is allowlisted.
        fakeSocModel = "Tensor G3"
        // No libs in nativeLibDir
        assertFalse(invokeIsNpuLikelySupported(freshManager()))
    }

    @Test
    fun `Tensor SoC with only Qualcomm libs is rejected (vendor-matched detection)`() {
        fakeSocModel = "Tensor G4"
        writeLib("libQnnHtp.so")
        assertFalse(invokeIsNpuLikelySupported(freshManager()))
    }

    @Test
    fun `Tensor SoC matching is case-insensitive`() {
        // Pixel devices have been observed reporting "Tensor", "tensor",
        // "TENSOR" depending on firmware build — the lowercase canonicalisation
        // in EngineManager must accept all.
        fakeSocModel = "TENSOR G3"
        writeLib("libtflite_npu_runtime.so")
        assertTrue(invokeIsNpuLikelySupported(freshManager()))
    }

    // ---- Samsung Exynos — F-080 new coverage --------------------------------

    @Test
    fun `Exynos 2200 SOC_MODEL s5e9925 with libnpu is supported`() {
        // Galaxy S22 (Intl) / S23 FE stock build.prop reports
        // `ro.soc.model=s5e9925`.
        fakeSocModel = "s5e9925"
        writeLib("libnpu_runtime.so")
        assertTrue(invokeIsNpuLikelySupported(freshManager()))
    }

    @Test
    fun `Exynos 2200 with libenn is also supported`() {
        // Some Samsung firmware bundles the ENN runtime under libenn_*.
        fakeSocModel = "s5e9925"
        writeLib("libenn_user.so")
        assertTrue(invokeIsNpuLikelySupported(freshManager()))
    }

    @Test
    fun `Exynos 2400 SOC_MODEL s5e9945 with libnpu is supported`() {
        // Galaxy S24 (Intl) / S24 FE stock build.prop reports
        // `ro.soc.model=s5e9945`.
        fakeSocModel = "s5e9945"
        writeLib("libnpu_runtime.so")
        assertTrue(invokeIsNpuLikelySupported(freshManager()))
    }

    @Test
    fun `Older Exynos (Exynos 990 s5e9830) is intentionally rejected`() {
        // Exynos 990 has Samsung NPU silicon but its ENN runtime is not
        // exposed by current LiteRT builds, so it is intentionally NOT
        // in the allowlist. Asserting the negative protects against an
        // accidental future addition that would silently break Galaxy
        // S20 / Note 20 series users.
        fakeSocModel = "s5e9830"
        writeLib("libnpu_runtime.so")
        writeLib("libenn_user.so")
        assertFalse(invokeIsNpuLikelySupported(freshManager()))
    }

    @Test
    fun `Exynos SoC without libnpu or libenn is rejected`() {
        fakeSocModel = "s5e9925"
        // No libs in nativeLibDir
        assertFalse(invokeIsNpuLikelySupported(freshManager()))
    }

    @Test
    fun `Exynos SoC with only MediaTek libs is rejected (vendor-matched detection)`() {
        fakeSocModel = "s5e9945"
        writeLib("libmediatek_npu.so")
        writeLib("libdispatch.so")
        assertFalse(invokeIsNpuLikelySupported(freshManager()))
    }

    // ---- nativeLibraryDir edge cases ----------------------------------------

    @Test
    fun `null nativeLibraryDir is rejected for Tensor SoC`() {
        fakeSocModel = "Tensor G3"
        appInfo.nativeLibraryDir = null
        assertFalse(invokeIsNpuLikelySupported(freshManager()))
    }

    @Test
    fun `null nativeLibraryDir is rejected for Exynos SoC`() {
        fakeSocModel = "s5e9925"
        appInfo.nativeLibraryDir = null
        assertFalse(invokeIsNpuLikelySupported(freshManager()))
    }
}
