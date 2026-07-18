package com.adsamcik.mindlayer.service.modeldelivery

import android.content.Context
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@OptIn(ExperimentalCoroutinesApi::class)
class ModelDeliveryManagerTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun clearDeliveryState() {
        File(context.filesDir, "model_delivery").deleteRecursively()
    }

    @After
    fun cleanDeliveryState() {
        File(context.filesDir, "model_delivery").deleteRecursively()
    }

    @Test
    fun `concurrent refreshes materialize a completed family only once`() = runTest {
        val sourceDir = File(context.filesDir, "model-delivery-manager-test").apply {
            deleteRecursively()
            mkdirs()
        }
        val client = FakeAssetPackClient(
            ModelDeliveryCatalog.family(ModelFamily.CHAT).packNames.associateWith { packName ->
                AssetPackSnapshot(
                    packName = packName,
                    phase = AssetPackPhase.COMPLETED,
                    assetsPath = sourceDir.absolutePath,
                )
            },
        )
        val materializer = CountingMaterializer()
        val managerScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val manager = ModelDeliveryManager(
            context = context,
            client = client,
            scope = managerScope,
            materializer = materializer,
        )

        try {
            repeat(4) { manager.refresh() }
            advanceUntilIdle()
            val installedState = withTimeout(5_000) {
                manager.states.first { it[ModelFamily.CHAT] == ModelDeliveryState.Installed }
            }

            assertEquals(1, materializer.materializeCalls.get())
            assertEquals(
                ModelDeliveryState.Installed,
                installedState[ModelFamily.CHAT],
            )
        } finally {
            manager.close()
            sourceDir.deleteRecursively()
        }
    }

    @Test
    fun `independent managers publish completed ocr once and activate only the publisher`() = runTest {
        val sourceDir = File(context.filesDir, "cross-manager-ocr-source").apply {
            deleteRecursively()
            mkdirs()
        }
        val pins = createOcrPack(sourceDir)
        val publications = AtomicInteger()
        val results = Collections.synchronizedList(mutableListOf<MaterializationResult>())
        val materializationsCompleted = CountDownLatch(2)
        val validationBarriers = List(3) { CyclicBarrier(2) }
        val firstExecutor = Executors.newSingleThreadExecutor()
        val secondExecutor = Executors.newSingleThreadExecutor()
        val firstDispatcher = firstExecutor.asCoroutineDispatcher()
        val secondDispatcher = secondExecutor.asCoroutineDispatcher()
        val firstRuntime = FakeRuntimeControl()
        val secondRuntime = FakeRuntimeControl()
        val firstManager = ModelDeliveryManager(
            context = context,
            client = FakeAssetPackClient(completedPacks(ModelFamily.OCR, sourceDir)),
            scope = CoroutineScope(SupervisorJob() + firstDispatcher),
            materializer = CoordinatedMaterializer(
                delegate = VerifiedModelMaterializer(
                    filesDir = context.filesDir,
                    releaseBuild = true,
                    pinnedSha256 = pins::get,
                    publicationStarted = { publications.incrementAndGet() },
                ),
                validationBarriers = validationBarriers,
                results = results,
                materializationsCompleted = materializationsCompleted,
            ),
            runtimeControl = firstRuntime,
            blockingDispatcher = firstDispatcher,
        )
        val secondManager = ModelDeliveryManager(
            context = context,
            client = FakeAssetPackClient(completedPacks(ModelFamily.OCR, sourceDir)),
            scope = CoroutineScope(SupervisorJob() + secondDispatcher),
            materializer = CoordinatedMaterializer(
                delegate = VerifiedModelMaterializer(
                    filesDir = context.filesDir,
                    releaseBuild = true,
                    pinnedSha256 = pins::get,
                    publicationStarted = { publications.incrementAndGet() },
                ),
                validationBarriers = validationBarriers,
                results = results,
                materializationsCompleted = materializationsCompleted,
            ),
            runtimeControl = secondRuntime,
            blockingDispatcher = secondDispatcher,
        )

        try {
            firstManager.start()
            secondManager.start()
            assertTrue(materializationsCompleted.await(15, TimeUnit.SECONDS))
            withTimeout(5_000) {
                firstManager.states.first { it[ModelFamily.OCR] == ModelDeliveryState.Installed }
                secondManager.states.first { it[ModelFamily.OCR] == ModelDeliveryState.Installed }
            }

            assertEquals(1, results.count { it == MaterializationResult.Installed })
            assertEquals(1, results.count { it == MaterializationResult.AlreadyInstalled })
            assertEquals(1, publications.get())
            assertEquals(
                1,
                firstRuntime.activationCalls.get() + secondRuntime.activationCalls.get(),
            )
        } finally {
            firstManager.close()
            secondManager.close()
            firstDispatcher.close()
            secondDispatcher.close()
            firstExecutor.shutdownNow()
            secondExecutor.shutdownNow()
            sourceDir.deleteRecursively()
        }
    }

    @Test
    fun `refresh failure remains unsupported instead of reverting to not installed`() = runTest {
        val client = FakeAssetPackClient(emptyMap(), refreshFailure = IllegalStateException("no Play"))
        val managerScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val manager = ModelDeliveryManager(
            context = context,
            client = client,
            scope = managerScope,
            materializer = CountingMaterializer(),
        )

        try {
            manager.refresh()
            advanceUntilIdle()

            assertEquals(
                ModelDeliveryState.Unsupported,
                manager.states.value[ModelFamily.CHAT],
            )
            assertEquals(
                ModelDeliveryState.Unsupported,
                manager.states.value[ModelFamily.EMBEDDINGS],
            )
            assertEquals(
                ModelDeliveryState.Unsupported,
                manager.states.value[ModelFamily.OCR],
            )
        } finally {
            manager.close()
        }
    }

    @Test
    fun `refresh failure does not replace an installed family`() = runTest {
        val chatPacks = ModelDeliveryCatalog.family(ModelFamily.CHAT).packNames.associateWith { packName ->
            AssetPackSnapshot(packName = packName, phase = AssetPackPhase.COMPLETED)
        }
        val client = FakeAssetPackClient(chatPacks, refreshFailure = IllegalStateException("no Play"))
        val managerScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val manager = ModelDeliveryManager(
            context = context,
            client = client,
            scope = managerScope,
            materializer = CountingMaterializer(setOf(ModelFamily.CHAT)),
        )

        try {
            manager.refresh()
            advanceUntilIdle()

            assertEquals(
                ModelDeliveryState.Installed,
                manager.states.value[ModelFamily.CHAT],
            )
        } finally {
            manager.close()
        }
    }

    @Test
    fun `verified private copy remains installed when Play reports pack not installed`() = runTest {
        val chatPacks = ModelDeliveryCatalog.family(ModelFamily.CHAT).packNames.associateWith { packName ->
            AssetPackSnapshot(packName = packName, phase = AssetPackPhase.NOT_INSTALLED)
        }
        val managerScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val manager = ModelDeliveryManager(
            context = context,
            client = FakeAssetPackClient(chatPacks),
            scope = managerScope,
            materializer = CountingMaterializer(setOf(ModelFamily.CHAT)),
        )

        try {
            manager.start()
            advanceUntilIdle()

            assertEquals(
                ModelDeliveryState.Installed,
                manager.states.value[ModelFamily.CHAT],
            )
        } finally {
            manager.close()
        }
    }

    @Test
    fun `interrupted multi-pack removal resumes from persisted intent`() = runTest {
        val chatPacks = ModelDeliveryCatalog.family(ModelFamily.CHAT).packNames.associateWith { packName ->
            AssetPackSnapshot(packName = packName, phase = AssetPackPhase.COMPLETED)
        }
        val client = FakeAssetPackClient(chatPacks, removeFailureAtCall = 2)
        val materializer = CountingMaterializer(setOf(ModelFamily.CHAT))
        val firstScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val firstManager = ModelDeliveryManager(
            context = context,
            client = client,
            scope = firstScope,
            materializer = materializer,
            runtimeControl = FakeRuntimeControl(),
        )

        try {
            firstManager.start()
            advanceUntilIdle()
            firstManager.remove(ModelFamily.CHAT)
            advanceUntilIdle()

            assertTrue(
                firstManager.states.value[ModelFamily.CHAT] is ModelDeliveryState.RemovalFailed,
            )
            assertEquals(0, materializer.removeCalls.get())
            firstManager.close()

            client.removeFailureAtCall = null
            val secondScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            val secondManager = ModelDeliveryManager(
                context = context,
                client = client,
                scope = secondScope,
                materializer = materializer,
                runtimeControl = FakeRuntimeControl(),
            )
            try {
                secondManager.refresh()
                advanceUntilIdle()

                assertEquals(
                    ModelDeliveryState.NotInstalled,
                    secondManager.states.value[ModelFamily.CHAT],
                )
                assertEquals(1, materializer.removeCalls.get())
            } finally {
                secondManager.close()
            }
        } finally {
            firstManager.close()
        }
    }

    @Test
    fun `concurrent refreshes resume a pending removal only once`() = runTest {
        val marker = File(context.filesDir, "model_delivery/.pending_remove_chat")
        marker.parentFile?.mkdirs()
        marker.writeText("")
        val client = FakeAssetPackClient(emptyMap())
        val materializer = CountingMaterializer()
        val managerScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val manager = ModelDeliveryManager(
            context = context,
            client = client,
            scope = managerScope,
            materializer = materializer,
            runtimeControl = FakeRuntimeControl(),
        )

        try {
            manager.refresh()
            manager.refresh()
            advanceUntilIdle()

            assertEquals(
                ModelDeliveryCatalog.family(ModelFamily.CHAT).packNames.size,
                client.removeCalls.get(),
            )
            assertEquals(1, materializer.removeCalls.get())
            assertEquals(ModelDeliveryState.NotInstalled, manager.states.value[ModelFamily.CHAT])
        } finally {
            manager.close()
            marker.delete()
        }
    }

    @Test
    fun `cancellation after removal persistence begins cannot lose intent`() = runTest {
        val marker = File(context.filesDir, "model_delivery/.pending_remove_chat")
        marker.delete()
        val persistenceStarted = CountDownLatch(1)
        val allowPersistence = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val blockingDispatcher = executor.asCoroutineDispatcher()
        val intentStore = ModelDeliveryIntentStore(context.filesDir)
        val releaseGate = CompletableDeferred<RuntimeReleaseResult>()
        val managerScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val manager = ModelDeliveryManager(
            context = context,
            client = FakeAssetPackClient(emptyMap()),
            scope = managerScope,
            materializer = CountingMaterializer(setOf(ModelFamily.CHAT)),
            runtimeControl = FakeRuntimeControl(releaseGate = releaseGate),
            blockingDispatcher = blockingDispatcher,
            removalIntentRecorder = { family ->
                persistenceStarted.countDown()
                check(allowPersistence.await(5, TimeUnit.SECONDS))
                intentStore.recordRemoval(family)
            },
        )

        try {
            val removal = launch {
                manager.remove(ModelFamily.CHAT)
            }
            runCurrent()
            assertTrue(persistenceStarted.await(5, TimeUnit.SECONDS))

            removal.cancel()
            allowPersistence.countDown()
            removal.join()

            assertTrue(marker.isFile)
            assertEquals(ModelDeliveryIntent.REMOVE, intentStore.currentIntent(ModelFamily.CHAT))
            releaseGate.complete(RuntimeReleaseResult.Released)
            advanceUntilIdle()
        } finally {
            allowPersistence.countDown()
            releaseGate.complete(RuntimeReleaseResult.Released)
            manager.close()
            blockingDispatcher.close()
            executor.shutdownNow()
            marker.delete()
        }
    }

    @Test
    fun `immediate cancellation before io dispatch persists removal for restart`() = runTest {
        val executor = Executors.newSingleThreadExecutor()
        val blockingDispatcher = executor.asCoroutineDispatcher()
        val dispatcherOccupied = CountDownLatch(1)
        val releaseDispatcher = CountDownLatch(1)
        val persistenceCompleted = CountDownLatch(1)
        executor.submit {
            dispatcherOccupied.countDown()
            releaseDispatcher.await(5, TimeUnit.SECONDS)
        }
        assertTrue(dispatcherOccupied.await(5, TimeUnit.SECONDS))
        val intentStore = ModelDeliveryIntentStore(context.filesDir)
        val firstScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val firstManager = ModelDeliveryManager(
            context = context,
            client = FakeAssetPackClient(emptyMap()),
            scope = firstScope,
            materializer = CountingMaterializer(setOf(ModelFamily.CHAT)),
            runtimeControl = FakeRuntimeControl(),
            blockingDispatcher = blockingDispatcher,
            removalIntentRecorder = { family ->
                intentStore.recordRemoval(family)
                persistenceCompleted.countDown()
            },
        )

        try {
            val removal = launch { firstManager.remove(ModelFamily.CHAT) }
            runCurrent()
            removal.cancel()
            releaseDispatcher.countDown()
            assertTrue(persistenceCompleted.await(5, TimeUnit.SECONDS))
            firstManager.close()
            runCurrent()
            removal.join()

            assertEquals(ModelDeliveryIntent.REMOVE, intentStore.currentIntent(ModelFamily.CHAT))

            val recoveredMaterializer = CountingMaterializer(setOf(ModelFamily.CHAT))
            val secondScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            val secondManager = ModelDeliveryManager(
                context = context,
                client = FakeAssetPackClient(emptyMap()),
                scope = secondScope,
                materializer = recoveredMaterializer,
                runtimeControl = FakeRuntimeControl(),
            )
            try {
                secondManager.refresh()
                advanceUntilIdle()

                assertEquals(1, recoveredMaterializer.removeCalls.get())
                assertEquals(
                    ModelDeliveryState.NotInstalled,
                    secondManager.states.value[ModelFamily.CHAT],
                )
            } finally {
                secondManager.close()
            }
        } finally {
            releaseDispatcher.countDown()
            firstManager.close()
            blockingDispatcher.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun `startup resumes remove whose marker transition was interrupted`() = runTest {
        val interrupted = ModelDeliveryIntentStore(context.filesDir) { _, intent, point ->
            if (
                intent == ModelDeliveryIntent.REMOVE &&
                point == ModelDeliveryIntentTransitionPoint.AFTER_CANONICAL_RECORD
            ) {
                throw SimulatedProcessDeath()
            }
        }
        org.junit.Assert.assertThrows(SimulatedProcessDeath::class.java) {
            interrupted.recordRemoval(ModelFamily.CHAT)
        }
        val materializer = CountingMaterializer(setOf(ModelFamily.CHAT))
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val manager = ModelDeliveryManager(
            context = context,
            client = FakeAssetPackClient(emptyMap()),
            scope = scope,
            materializer = materializer,
            runtimeControl = FakeRuntimeControl(),
        )

        try {
            manager.start()
            advanceUntilIdle()

            assertEquals(1, materializer.removeCalls.get())
            assertEquals(ModelDeliveryState.NotInstalled, manager.states.value[ModelFamily.CHAT])
            assertFalse(
                ModelDeliveryFileLock.pendingRemovalMarker(context.filesDir, ModelFamily.CHAT).exists(),
            )
        } finally {
            manager.close()
        }
    }

    @Test
    fun `startup clears stale remove markers for canonical install before provisioning`() = runTest {
        val sourceDir = File(context.filesDir, "install-recovery-source").apply { mkdirs() }
        ModelDeliveryIntentStore(context.filesDir).recordRemoval(ModelFamily.CHAT)
        val interrupted = ModelDeliveryIntentStore(context.filesDir) { _, intent, point ->
            if (
                intent == ModelDeliveryIntent.INSTALL &&
                point == ModelDeliveryIntentTransitionPoint.AFTER_CANONICAL_RECORD
            ) {
                throw SimulatedProcessDeath()
            }
        }
        org.junit.Assert.assertThrows(SimulatedProcessDeath::class.java) {
            interrupted.recordDownload(ModelFamily.CHAT)
        }
        val materializer = CountingMaterializer()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val manager = ModelDeliveryManager(
            context = context,
            client = FakeAssetPackClient(completedPacks(ModelFamily.CHAT, sourceDir)),
            scope = scope,
            materializer = materializer,
        )

        try {
            manager.start()
            advanceUntilIdle()

            assertEquals(1, materializer.materializeCalls.get())
            assertEquals(ModelDeliveryState.Installed, manager.states.value[ModelFamily.CHAT])
            assertFalse(
                ModelDeliveryFileLock.pendingRemovalMarker(context.filesDir, ModelFamily.CHAT).exists(),
            )
            assertFalse(
                ModelDeliveryFileLock.removalTombstone(context.filesDir, ModelFamily.CHAT).exists(),
            )
        } finally {
            manager.close()
            sourceDir.deleteRecursively()
        }
    }

    @Test
    fun `startup and explicit refresh force installed byte validation for every family`() = runTest {
        val materializer = CountingMaterializer(setOf(ModelFamily.CHAT))
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val manager = ModelDeliveryManager(
            context = context,
            client = FakeAssetPackClient(emptyMap()),
            scope = scope,
            materializer = materializer,
        )

        try {
            manager.start()
            advanceUntilIdle()
            assertEquals(ModelFamily.entries.size, materializer.forcedValidationCalls.get())

            manager.refresh()
            advanceUntilIdle()
            assertEquals(ModelFamily.entries.size * 2, materializer.forcedValidationCalls.get())
            assertEquals(ModelFamily.entries.size * 2, materializer.forcedValidationCalls.get())
        } finally {
            manager.close()
        }
    }

    @Test
    fun `chat completion after startup reconciliation is caught up exactly once`() = runTest {
        val sourceDir = File(context.filesDir, "startup-chat-catch-up-source").apply { mkdirs() }
        val client = FakeAssetPackClient(emptyMap())
        val activationGate = CompletableDeferred<RuntimeActivationResult>()
        val materializer = CountingMaterializer(setOf(ModelFamily.OCR))
        val runtimeControl = FakeRuntimeControl(activationGate = activationGate)
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val manager = ModelDeliveryManager(
            context = context,
            client = client,
            scope = scope,
            materializer = materializer,
            runtimeControl = runtimeControl,
        )

        try {
            manager.start()
            runCurrent()
            assertEquals(1, runtimeControl.activationCalls.get())

            client.emitCompleted(ModelFamily.CHAT, sourceDir)
            runCurrent()
            activationGate.complete(RuntimeActivationResult.Activated)
            advanceUntilIdle()

            assertEquals(1, materializer.materializeCalls.get())
            assertEquals(ModelDeliveryState.Installed, manager.states.value[ModelFamily.CHAT])
            assertEquals(1, runtimeControl.activationCalls.get())
        } finally {
            activationGate.complete(RuntimeActivationResult.Activated)
            manager.close()
            sourceDir.deleteRecursively()
        }
    }

    @Test
    fun `ocr completion after startup reconciliation provisions and activates exactly once`() = runTest {
        val sourceDir = File(context.filesDir, "startup-ocr-catch-up-source").apply { mkdirs() }
        val client = FakeAssetPackClient(emptyMap())
        val startupReconciled = CompletableDeferred<Unit>()
        val allowCallbackHandoff = CompletableDeferred<Unit>()
        val materializer = CountingMaterializer()
        val runtimeControl = FakeRuntimeControl()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val manager = ModelDeliveryManager(
            context = context,
            client = client,
            scope = scope,
            materializer = materializer,
            runtimeControl = runtimeControl,
            beforeInitialCallbackHandoff = {
                startupReconciled.complete(Unit)
                allowCallbackHandoff.await()
            },
        )

        try {
            manager.start()
            runCurrent()
            assertTrue(startupReconciled.isCompleted)

            client.emitCompleted(ModelFamily.OCR, sourceDir)
            runCurrent()
            allowCallbackHandoff.complete(Unit)
            advanceUntilIdle()

            assertEquals(1, materializer.familyMaterializeCalls(ModelFamily.OCR))
            assertEquals(1, runtimeControl.activationCalls.get())
            assertEquals(ModelDeliveryState.Installed, manager.states.value[ModelFamily.OCR])
        } finally {
            allowCallbackHandoff.complete(Unit)
            manager.close()
            sourceDir.deleteRecursively()
        }
    }

    @Test
    fun `callback during startup catch-up is not lost or provisioned twice`() = runTest {
        val sourceDir = File(context.filesDir, "startup-overlapping-callback-source").apply { mkdirs() }
        val client = FakeAssetPackClient(emptyMap())
        val startupReconciled = CompletableDeferred<Unit>()
        val allowCallbackHandoff = CompletableDeferred<Unit>()
        val catchUpStarted = CompletableDeferred<Unit>()
        val allowCatchUp = CompletableDeferred<Unit>()
        val materializer = CountingMaterializer()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val manager = ModelDeliveryManager(
            context = context,
            client = client,
            scope = scope,
            materializer = materializer,
            beforeInitialCallbackHandoff = {
                startupReconciled.complete(Unit)
                allowCallbackHandoff.await()
            },
            beforeInitialCatchUp = {
                catchUpStarted.complete(Unit)
                allowCatchUp.await()
            },
        )

        try {
            manager.start()
            runCurrent()
            assertTrue(startupReconciled.isCompleted)

            client.emitCompleted(ModelFamily.CHAT, sourceDir)
            runCurrent()
            allowCallbackHandoff.complete(Unit)
            runCurrent()
            assertTrue(catchUpStarted.isCompleted)

            client.emitCompleted(ModelFamily.EMBEDDINGS, sourceDir)
            runCurrent()
            allowCatchUp.complete(Unit)
            advanceUntilIdle()

            assertEquals(ModelDeliveryState.Installed, manager.states.value[ModelFamily.CHAT])
            assertEquals(ModelDeliveryState.Installed, manager.states.value[ModelFamily.EMBEDDINGS])
            assertEquals(1, materializer.familyMaterializeCalls(ModelFamily.CHAT))
            assertEquals(1, materializer.familyMaterializeCalls(ModelFamily.EMBEDDINGS))
        } finally {
            allowCallbackHandoff.complete(Unit)
            allowCatchUp.complete(Unit)
            manager.close()
            sourceDir.deleteRecursively()
        }
    }

    @Test
    fun `late completion after removal cannot reprovision until explicit download`() = runTest {
        val sourceDir = File(context.filesDir, "late-completion-source").apply { mkdirs() }
        val client = FakeAssetPackClient(completedPacks(ModelFamily.CHAT, sourceDir))
        val materializer = CountingMaterializer(setOf(ModelFamily.CHAT))
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val manager = ModelDeliveryManager(
            context = context,
            client = client,
            scope = scope,
            materializer = materializer,
            runtimeControl = FakeRuntimeControl(),
            availableBytes = { Long.MAX_VALUE },
        )

        try {
            advanceUntilIdle()
            manager.remove(ModelFamily.CHAT)
            advanceUntilIdle()

            client.emitCompleted(ModelFamily.CHAT, sourceDir)
            advanceUntilIdle()

            assertEquals(0, materializer.materializeCalls.get())
            assertEquals(ModelDeliveryState.NotInstalled, manager.states.value[ModelFamily.CHAT])

            manager.download(ModelFamily.CHAT)
            advanceUntilIdle()
            withTimeout(5_000) {
                manager.states.first { it[ModelFamily.CHAT] == ModelDeliveryState.Installed }
            }

            assertEquals(1, materializer.materializeCalls.get())
            assertEquals(ModelDeliveryState.Installed, manager.states.value[ModelFamily.CHAT])
        } finally {
            manager.close()
            sourceDir.deleteRecursively()
        }
    }

    @Test
    fun `cancel is requested before runtime release and pack deletion`() = runTest {
        val events = Collections.synchronizedList(mutableListOf<String>())
        val client = FakeAssetPackClient(emptyMap(), events = events)
        val materializer = CountingMaterializer(setOf(ModelFamily.CHAT), events)
        val runtimeControl = FakeRuntimeControl(events = events)
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "model-removal-io")
        }
        val blockingDispatcher = executor.asCoroutineDispatcher()
        val intentStore = ModelDeliveryIntentStore(context.filesDir)
        val callerThread = Thread.currentThread().name
        var persistenceThread = ""
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val manager = ModelDeliveryManager(
            context = context,
            client = client,
            scope = scope,
            materializer = materializer,
            runtimeControl = runtimeControl,
            blockingDispatcher = blockingDispatcher,
            removalIntentRecorder = { family ->
                persistenceThread = Thread.currentThread().name
                events.add("persist:$family")
                intentStore.recordRemoval(family)
            },
        )

        try {
            manager.remove(ModelFamily.CHAT)
            advanceUntilIdle()

            val persistenceIndex = events.indexOf("persist:CHAT")
            val cancelIndex = events.indexOf("cancel:CHAT")
            val quiesceIndex = events.indexOf("quiesce:CHAT")
            val firstPackDelete = events.indexOfFirst { it.startsWith("removePack:") }
            val artifactDelete = events.indexOf("materializerRemove:CHAT")
            assertNotEquals(callerThread, persistenceThread)
            assertTrue(persistenceIndex >= 0)
            assertTrue(persistenceIndex < cancelIndex)
            assertTrue(cancelIndex >= 0)
            assertTrue(cancelIndex < quiesceIndex)
            assertTrue(quiesceIndex < firstPackDelete)
            assertTrue(firstPackDelete < artifactDelete)
        } finally {
            manager.close()
            blockingDispatcher.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun `removal waits for runtime acknowledgement and failure remains retryable`() = runTest {
        val releaseGate = CompletableDeferred<RuntimeReleaseResult>()
        val runtimeControl = FakeRuntimeControl(releaseGate = releaseGate)
        val client = FakeAssetPackClient(emptyMap())
        val materializer = CountingMaterializer(setOf(ModelFamily.EMBEDDINGS))
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val manager = ModelDeliveryManager(
            context = context,
            client = client,
            scope = scope,
            materializer = materializer,
            runtimeControl = runtimeControl,
        )

        try {
            manager.remove(ModelFamily.EMBEDDINGS)
            runCurrent()

            assertEquals(ModelDeliveryState.Quiescing, manager.states.value[ModelFamily.EMBEDDINGS])
            assertEquals(0, materializer.removeCalls.get())

            releaseGate.complete(RuntimeReleaseResult.Failed(RuntimeControlFailure.RELEASE_FAILED))
            advanceUntilIdle()

            assertTrue(manager.states.value[ModelFamily.EMBEDDINGS] is ModelDeliveryState.RemovalFailed)
            assertEquals(0, materializer.removeCalls.get())
            assertTrue(
                ModelDeliveryFileLock.pendingRemovalMarker(
                    context.filesDir,
                    ModelFamily.EMBEDDINGS,
                ).isFile,
            )

            runtimeControl.releaseGate = null
            runtimeControl.releaseResult = RuntimeReleaseResult.Released
            manager.remove(ModelFamily.EMBEDDINGS)
            advanceUntilIdle()

            assertEquals(ModelDeliveryState.NotInstalled, manager.states.value[ModelFamily.EMBEDDINGS])
            assertEquals(1, materializer.removeCalls.get())
            assertFalse(
                ModelDeliveryFileLock.pendingRemovalMarker(
                    context.filesDir,
                    ModelFamily.EMBEDDINGS,
                ).exists(),
            )
        } finally {
            manager.close()
        }
    }

    @Test
    fun `ocr materialization requests one coalesced activation`() = runTest {
        val sourceDir = File(context.filesDir, "ocr-activation-source").apply { mkdirs() }
        val client = FakeAssetPackClient(completedPacks(ModelFamily.OCR, sourceDir))
        val materializer = CountingMaterializer()
        val runtimeControl = FakeRuntimeControl()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val manager = ModelDeliveryManager(
            context = context,
            client = client,
            scope = scope,
            materializer = materializer,
            runtimeControl = runtimeControl,
        )

        try {
            repeat(4) { manager.refresh() }
            advanceUntilIdle()

            assertEquals(1, materializer.materializeCalls.get())
            assertEquals(1, runtimeControl.activationCalls.get())
            assertEquals(ModelDeliveryState.Installed, manager.states.value[ModelFamily.OCR])
        } finally {
            manager.close()
            sourceDir.deleteRecursively()
        }
    }

    @Test
    fun `ocr activation failure preserves installed bytes`() = runTest {
        val sourceDir = File(context.filesDir, "ocr-activation-failure-source").apply { mkdirs() }
        val client = FakeAssetPackClient(completedPacks(ModelFamily.OCR, sourceDir))
        val materializer = CountingMaterializer()
        val runtimeControl = FakeRuntimeControl(
            activationResult = RuntimeActivationResult.Failed(RuntimeControlFailure.ACTIVATION_FAILED),
        )
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val manager = ModelDeliveryManager(
            context = context,
            client = client,
            scope = scope,
            materializer = materializer,
            runtimeControl = runtimeControl,
        )

        try {
            manager.start()
            advanceUntilIdle()

            val state = withTimeout(5_000) {
                manager.states.first {
                    it[ModelFamily.OCR] == ModelDeliveryState.InstalledWithActivationError
                }.getValue(ModelFamily.OCR)
            }
            assertEquals(ModelDeliveryState.InstalledWithActivationError, state)
            assertTrue(materializer.isMarkedInstalled(ModelFamily.OCR))
            assertEquals(0, materializer.removeCalls.get())
        } finally {
            manager.close()
            sourceDir.deleteRecursively()
        }
    }

    @Test
    fun `explicit refresh retries one prior ocr activation failure but pad emissions do not`() = runTest {
        val sourceDir = File(context.filesDir, "ocr-refresh-retry-source").apply { mkdirs() }
        val client = FakeAssetPackClient(completedPacks(ModelFamily.OCR, sourceDir))
        val materializer = CountingMaterializer()
        val runtimeControl = FakeRuntimeControl(
            activationResult = RuntimeActivationResult.Failed(RuntimeControlFailure.ACTIVATION_FAILED),
        )
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val manager = ModelDeliveryManager(
            context = context,
            client = client,
            scope = scope,
            materializer = materializer,
            runtimeControl = runtimeControl,
        )

        try {
            manager.start()
            advanceUntilIdle()
            assertEquals(ModelDeliveryState.InstalledWithActivationError, manager.states.value[ModelFamily.OCR])
            assertEquals(1, runtimeControl.activationCalls.get())

            client.emitPhase(ModelFamily.OCR, AssetPackPhase.PENDING, sourceDir)
            advanceUntilIdle()

            assertEquals(ModelDeliveryState.InstalledWithActivationError, manager.states.value[ModelFamily.OCR])
            assertEquals(1, runtimeControl.activationCalls.get())

            runtimeControl.activationResult = RuntimeActivationResult.Activated
            manager.refresh()
            advanceUntilIdle()

            assertEquals(2, runtimeControl.activationCalls.get())
            assertEquals(ModelDeliveryState.Installed, manager.states.value[ModelFamily.OCR])
        } finally {
            manager.close()
            sourceDir.deleteRecursively()
        }
    }

    @Test
    fun `preexisting OCR install activates once per explicit reconciliation but not PAD callbacks`() = runTest {
        val sourceDir = File(context.filesDir, "ocr-restart-activation-source").apply { mkdirs() }
        val client = FakeAssetPackClient(emptyMap())
        val runtimeControl = FakeRuntimeControl()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val manager = ModelDeliveryManager(
            context = context,
            client = client,
            scope = scope,
            materializer = CountingMaterializer(setOf(ModelFamily.OCR)),
            runtimeControl = runtimeControl,
        )

        try {
            manager.start()
            advanceUntilIdle()

            assertEquals(1, runtimeControl.activationCalls.get())
            assertEquals(ModelDeliveryState.Installed, manager.states.value[ModelFamily.OCR])

            client.emitPhase(ModelFamily.OCR, AssetPackPhase.PENDING, sourceDir)
            advanceUntilIdle()
            client.emitPhase(ModelFamily.OCR, AssetPackPhase.DOWNLOADING, sourceDir)
            advanceUntilIdle()
            client.emitPhase(ModelFamily.OCR, AssetPackPhase.TRANSFERRING, sourceDir)
            advanceUntilIdle()

            assertEquals(1, runtimeControl.activationCalls.get())

            manager.refresh()
            advanceUntilIdle()

            assertEquals(2, runtimeControl.activationCalls.get())
            assertEquals(ModelDeliveryState.Installed, manager.states.value[ModelFamily.OCR])
        } finally {
            manager.close()
            sourceDir.deleteRecursively()
        }
    }

    @Test
    fun `dedicated ocr activation retry is coalesced and does not fetch packs`() = runTest {
        val sourceDir = File(context.filesDir, "ocr-dedicated-retry-source").apply { mkdirs() }
        val client = FakeAssetPackClient(completedPacks(ModelFamily.OCR, sourceDir))
        val materializer = CountingMaterializer()
        val runtimeControl = FakeRuntimeControl(
            activationResult = RuntimeActivationResult.Failed(RuntimeControlFailure.ACTIVATION_FAILED),
        )
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val manager = ModelDeliveryManager(
            context = context,
            client = client,
            scope = scope,
            materializer = materializer,
            runtimeControl = runtimeControl,
        )

        try {
            manager.start()
            advanceUntilIdle()
            val activationGate = CompletableDeferred<RuntimeActivationResult>()
            runtimeControl.activationGate = activationGate

            val firstRetry = launch { manager.retryActivation(ModelFamily.OCR) }
            val secondRetry = launch { manager.retryActivation(ModelFamily.OCR) }
            runCurrent()

            assertEquals(ModelDeliveryState.Activating, manager.states.value[ModelFamily.OCR])
            assertEquals(2, runtimeControl.activationCalls.get())
            assertEquals(0, client.fetchCalls.get())

            activationGate.complete(RuntimeActivationResult.Activated)
            advanceUntilIdle()
            firstRetry.join()
            secondRetry.join()

            assertEquals(ModelDeliveryState.Installed, manager.states.value[ModelFamily.OCR])
            assertEquals(2, runtimeControl.activationCalls.get())
            assertEquals(0, client.fetchCalls.get())
        } finally {
            manager.close()
            sourceDir.deleteRecursively()
        }
    }

    @Test
    fun `late successful ocr activation clears timeout error`() = runTest {
        val sourceDir = File(context.filesDir, "ocr-late-activation-source").apply { mkdirs() }
        val client = FakeAssetPackClient(completedPacks(ModelFamily.OCR, sourceDir))
        val activationGate = CompletableDeferred<RuntimeActivationResult>()
        val runtimeControl = FakeRuntimeControl(activationGate = activationGate)
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val manager = ModelDeliveryManager(
            context = context,
            client = client,
            scope = scope,
            materializer = CountingMaterializer(),
            runtimeControl = runtimeControl,
        )

        try {
            manager.start()
            runCurrent()
            assertEquals(ModelDeliveryState.Activating, manager.states.value[ModelFamily.OCR])

            advanceTimeBy(10_000L)
            runCurrent()
            assertEquals(ModelDeliveryState.InstalledWithActivationError, manager.states.value[ModelFamily.OCR])

            activationGate.complete(RuntimeActivationResult.Activated)
            advanceUntilIdle()

            assertEquals(ModelDeliveryState.Installed, manager.states.value[ModelFamily.OCR])
            assertEquals(1, runtimeControl.activationCalls.get())
        } finally {
            manager.close()
            sourceDir.deleteRecursively()
        }
    }

    private fun completedPacks(
        family: ModelFamily,
        sourceDir: File,
    ): Map<String, AssetPackSnapshot> =
        ModelDeliveryCatalog.family(family).packNames.associateWith { packName ->
            AssetPackSnapshot(
                packName = packName,
                phase = AssetPackPhase.COMPLETED,
                assetsPath = sourceDir.absolutePath,
            )
        }

    private fun createOcrPack(sourceDir: File): Map<String, String> {
        val hashes = ModelDeliveryCatalog.family(ModelFamily.OCR).files.associate { artifact ->
            val bytes = "test-${artifact.filename}".toByteArray()
            File(sourceDir, artifact.filename).writeBytes(bytes)
            artifact.filename to sha256(bytes)
        }
        val models = hashes.entries.joinToString(",") { (filename, hash) ->
            """{"filename":"$filename","sha256":"$hash"}"""
        }
        File(sourceDir, "paddleocr_model_integrity.json").writeText("""{"models":[$models]}""")
        return hashes
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private class CoordinatedMaterializer(
        private val delegate: ModelArtifactMaterializer,
        private val validationBarriers: List<CyclicBarrier>,
        private val results: MutableList<MaterializationResult>,
        private val materializationsCompleted: CountDownLatch,
    ) : ModelArtifactMaterializer {
        private val ocrValidationCalls = AtomicInteger()

        override fun materialize(
            family: ModelFamily,
            packAssetDirectories: Map<String, File>,
        ): MaterializationResult =
            delegate.materialize(family, packAssetDirectories).also { result ->
                results.add(result)
                materializationsCompleted.countDown()
            }

        override fun isMarkedInstalled(family: ModelFamily, forceValidation: Boolean): Boolean {
            val installed = delegate.isMarkedInstalled(family, forceValidation)
            if (family == ModelFamily.OCR) {
                val index = ocrValidationCalls.getAndIncrement()
                if (index < validationBarriers.size) {
                    validationBarriers[index].await(10, TimeUnit.SECONDS)
                }
            }
            return installed
        }

        override fun remove(family: ModelFamily) {
            delegate.remove(family)
        }
    }

    private class CountingMaterializer(
        initiallyInstalled: Set<ModelFamily> = emptySet(),
        private val events: MutableList<String>? = null,
    ) : ModelArtifactMaterializer {
        val materializeCalls = AtomicInteger()
        val removeCalls = AtomicInteger()
        val forcedValidationCalls = AtomicInteger()
        private val materializeCallsByFamily = ConcurrentHashMap<ModelFamily, AtomicInteger>()
        private val installed = initiallyInstalled.toMutableSet()

        override fun materialize(
            family: ModelFamily,
            packAssetDirectories: Map<String, File>,
        ): MaterializationResult {
            materializeCalls.incrementAndGet()
            materializeCallsByFamily.computeIfAbsent(family) { AtomicInteger() }.incrementAndGet()
            installed += family
            events?.add("materialize:$family")
            return MaterializationResult.Installed
        }

        override fun isMarkedInstalled(family: ModelFamily, forceValidation: Boolean): Boolean {
            if (forceValidation) forcedValidationCalls.incrementAndGet()
            return family in installed
        }

        override fun remove(family: ModelFamily) {
            removeCalls.incrementAndGet()
            installed -= family
            events?.add("materializerRemove:$family")
        }

        fun familyMaterializeCalls(family: ModelFamily): Int =
            materializeCallsByFamily[family]?.get() ?: 0
    }

    private class FakeAssetPackClient(
        initialStates: Map<String, AssetPackSnapshot>,
        private val refreshFailure: Throwable? = null,
        var removeFailureAtCall: Int? = null,
        private val events: MutableList<String>? = null,
    ) : AssetPackClient {
        private val mutableStates = MutableStateFlow(initialStates)
        val removeCalls = AtomicInteger()
        val fetchCalls = AtomicInteger()
        override val states: StateFlow<Map<String, AssetPackSnapshot>> = mutableStates

        override suspend fun refresh(packNames: Collection<String>) {
            refreshFailure?.let { throw it }
        }

        override suspend fun fetch(packNames: Collection<String>) {
            fetchCalls.incrementAndGet()
            events?.add("fetch")
        }

        override suspend fun cancel(packNames: Collection<String>) {
            events?.add(
                "cancel:" + ModelFamily.entries.firstOrNull { family ->
                    ModelDeliveryCatalog.family(family).packNames == packNames.toList()
                }?.name,
            )
        }

        override suspend fun removePack(packName: String) {
            events?.add("removePack:$packName")
            val call = removeCalls.incrementAndGet()
            if (call == removeFailureAtCall) {
                throw IllegalStateException("remove failed")
            }
            mutableStates.update { current ->
                current + (
                    packName to AssetPackSnapshot(
                        packName = packName,
                        phase = AssetPackPhase.NOT_INSTALLED,
                    )
                )
            }
        }

        override fun showConfirmationDialog(
            launcher: ActivityResultLauncher<IntentSenderRequest>,
        ): Boolean = false

        override fun close() = Unit

        fun emitCompleted(family: ModelFamily, sourceDir: File) {
            mutableStates.update { current ->
                current + completedSnapshots(family, sourceDir)
            }
        }

        fun emitPhase(family: ModelFamily, phase: AssetPackPhase, sourceDir: File) {
            mutableStates.update { current ->
                current + ModelDeliveryCatalog.family(family).packNames.associateWith { packName ->
                    AssetPackSnapshot(
                        packName = packName,
                        phase = phase,
                        assetsPath = sourceDir.absolutePath,
                    )
                }
            }
        }

        private fun completedSnapshots(
            family: ModelFamily,
            sourceDir: File,
        ): Map<String, AssetPackSnapshot> =
            ModelDeliveryCatalog.family(family).packNames.associateWith { packName ->
                AssetPackSnapshot(
                    packName = packName,
                    phase = AssetPackPhase.COMPLETED,
                    assetsPath = sourceDir.absolutePath,
                )
            }
    }

    private class FakeRuntimeControl(
        var releaseResult: RuntimeReleaseResult = RuntimeReleaseResult.Released,
        var activationResult: RuntimeActivationResult = RuntimeActivationResult.Activated,
        var releaseGate: CompletableDeferred<RuntimeReleaseResult>? = null,
        var activationGate: CompletableDeferred<RuntimeActivationResult>? = null,
        private val events: MutableList<String>? = null,
    ) : ModelRuntimeControl {
        val activationCalls = AtomicInteger()

        override suspend fun quiesce(family: ModelFamily): RuntimeReleaseResult {
            events?.add("quiesce:$family")
            return releaseGate?.await() ?: releaseResult
        }

        override suspend fun activate(family: ModelFamily): RuntimeActivationResult {
            events?.add("activate:$family")
            activationCalls.incrementAndGet()
            return activationGate?.await() ?: activationResult
        }
    }

    private class SimulatedProcessDeath : RuntimeException()
}
