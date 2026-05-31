package com.adsamcik.mindlayer.sdk

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.adsamcik.mindlayer.MediaPart
import java.io.File

/**
 * Declarative description of a single inference call, assembled through
 * [Builder] inside [Mindlayer.infer].
 *
 * C1 lands the builder surface only — the builder records intent into private
 * fields but no AIDL request is produced until C2 wires [Mindlayer.infer].
 */
class InferenceRequest private constructor() {

    /**
     * Fluent builder for an [InferenceRequest].
     *
     * Marked [MindlayerDsl] so a nested builder (e.g. inside `withSession`)
     * does not implicitly capture an outer builder's receiver.
     */
    @MindlayerDsl
    class Builder {
        // ---- Session ownership (exactly one; second call replaces first) ----

        /** Run in a fresh ephemeral session configured by [configure]. */
        fun ephemeralSession(configure: SessionScope.() -> Unit = {}) {
            sessionConfigure = configure
            sessionId = null
        }

        /** Run inside an existing session [id]. */
        fun session(id: String) {
            sessionId = id
            sessionConfigure = null
        }

        // ---- Input (chainable, named only — NO operator overloads) ----------

        fun text(text: String) {
            promptText = text
        }

        fun image(bitmap: Bitmap) {
            imageInputs += ImageInput.Bitmap(bitmap)
        }

        fun image(uri: Uri, context: Context) {
            imageInputs += ImageInput.Uri(uri, context)
        }

        fun audio(file: File) {
            audioFile = file
        }

        /** Escape hatch for modalities the typed setters don't cover yet. */
        fun media(part: MediaPart) {
            mediaParts += part
        }

        // ---- Output mode (mutually exclusive; last call wins; default Text) ----

        fun outputText() {
            outputMode = OutputMode.Text
        }

        fun outputJson(schema: JsonSchema, strategy: JsonOutputStrategy = JsonOutputStrategy.PromptAndValidate) {
            outputMode = OutputMode.Json(schema, strategy)
        }

        fun outputTools(tools: List<ToolSpec>) {
            outputMode = OutputMode.Tools(tools)
        }

        // ---- Sampling -------------------------------------------------------

        fun sampling(configure: SamplerScope.() -> Unit) {
            samplerConfigure = configure
        }

        // ---- Tool follow-ups (only valid after outputTools) -----------------

        /**
         * Caller-supplied tool handler invoked in the collecting coroutine
         * scope. If [handler] throws, the events flow terminates with an
         * [InferenceEvent.Error] frame.
         */
        fun onToolCall(handler: suspend (ToolCall) -> String) {
            toolHandler = handler
        }

        // ---- Internal recorded intent (consumed in C2) ----------------------

        internal var sessionId: String? = null
        internal var sessionConfigure: (SessionScope.() -> Unit)? = null
        internal var promptText: String? = null
        internal val imageInputs: MutableList<ImageInput> = mutableListOf()
        internal var audioFile: File? = null
        internal val mediaParts: MutableList<MediaPart> = mutableListOf()
        internal var outputMode: OutputMode = OutputMode.Text
        internal var samplerConfigure: (SamplerScope.() -> Unit)? = null
        internal var toolHandler: (suspend (ToolCall) -> String)? = null
    }

    /** Recorded output mode for an in-flight [Builder]. */
    internal sealed interface OutputMode {
        object Text : OutputMode
        data class Json(val schema: JsonSchema, val strategy: JsonOutputStrategy) : OutputMode
        data class Tools(val tools: List<ToolSpec>) : OutputMode
    }
}
