package com.adsamcik.mindlayer.service

import com.adsamcik.mindlayer.EmbeddingRequest
import com.adsamcik.mindlayer.service.engine.DeferredStore
import com.adsamcik.mindlayer.service.engine.EmbeddingCoordinator
import com.adsamcik.mindlayer.service.engine.EmbeddingEngine
import com.adsamcik.mindlayer.service.engine.EmbeddingOutput
import com.adsamcik.mindlayer.service.engine.FakeDeferredDao
import com.adsamcik.mindlayer.service.ipc.SharedMemoryPool
import com.adsamcik.mindlayer.service.security.EvictionRegistry
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MindlayerMlServiceFgsEmbeddingTest {
    @Test fun `embedding coordinator toggles service foreground around active batch`() = runTest {
        val service = mockk<MindlayerMlService>(relaxed = true)
        val dir = File(System.getProperty("java.io.tmpdir"), "mlsvc-fgs-${System.nanoTime()}").apply { mkdirs() }
        every { service.cacheDir } returns dir
        every { service.filesDir } returns dir
        every { service.getExternalFilesDir(null) } returns dir
        every { service.applicationInfo } returns android.content.pm.ApplicationInfo().apply { flags = android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE }
        every { service.assets.list(any()) } returns emptyArray()
        File(dir, "embedding-test.tflite").writeBytes(byteArrayOf(1))
        File(dir, "sentencepiece.model").writeBytes(byteArrayOf(1))
        val engine = mockk<EmbeddingEngine>()
        coEvery { engine.embed(any(), any(), any(), any()) } returns EmbeddingOutput(floatArrayOf(1f), 1, "embedding-test", 1, false, "CPU", 1)
        val coordinator = EmbeddingCoordinator(engine, DeferredStore(FakeDeferredDao()), service, CoroutineScope(SupervisorJob() + Dispatchers.Default), EvictionRegistry(), SharedMemoryPool(dir))
        coordinator.embedBatch(1, listOf(EmbeddingRequest(text = "x")), "fgs")
        verify { service.enterForeground() }
        verify { service.exitForeground() }
    }
}