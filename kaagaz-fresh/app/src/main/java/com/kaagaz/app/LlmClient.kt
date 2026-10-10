package com.kaagaz.app

/** The one seam between Kaagaz and any language model, so engines stay testable without a device or model. */
interface LlmClient {
    val isReady: Boolean
    val label: String
    fun generate(prompt: String, system: String, maxTokens: Int = 160, timeoutMs: Long = 45_000): LlmResult
    fun cancel() {}
}

sealed class LlmResult {
    data class Text(val text: String, val latencyMs: Long) : LlmResult()
    data class Unavailable(val reason: String) : LlmResult()
    data class Failed(val reason: String) : LlmResult()
}

/** Used when no model is installed: always unavailable, so every engine exercises its deterministic path. */
object NoLlm : LlmClient {
    override val isReady = false
    override val label = "Deterministic engine"
    override fun generate(prompt: String, system: String, maxTokens: Int, timeoutMs: Long) = LlmResult.Unavailable("No language model installed")
}
