package com.adsamcik.mindlayer.sdk.consumercheck

import com.adsamcik.mindlayer.sdk.ConnectionState
import com.adsamcik.mindlayer.sdk.InferenceEvent
import com.adsamcik.mindlayer.sdk.InferenceHandle
import com.adsamcik.mindlayer.sdk.Mindlayer
import com.adsamcik.mindlayer.shared.StreamEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject

/** Compile this consumer with only the SDK to catch missing API dependencies. */
internal object PublicApiCompileCheck {
    fun events(handle: InferenceHandle): Flow<InferenceEvent> = handle.events

    fun connectionState(client: Mindlayer): StateFlow<ConnectionState> = client.connectionState

    suspend fun structuredResult(handle: InferenceHandle.Structured): JsonObject = handle.awaitJson()

    fun payload(event: StreamEvent): JsonObject = event.payload
}
