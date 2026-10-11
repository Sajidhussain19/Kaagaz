package com.kaagaz.app.llm

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * On-device Gemma through LiteRT-LM. No model weights ship with the app: the user imports a `.litertlm`
 * file, which is copied into private storage. The runtime reports [Status.VERIFIED] only after a real
 * generation returned non-empty text on this device; every caller must handle [Status] != VERIFIED.
 */
class GemmaRuntime(private val context: Context) : LlmClient {
    enum class Status { NOT_INSTALLED, INSTALLED, LOADING, VERIFIED, FAILED }

    data class State(val status: Status, val modelName: String?, val backend: String?, val detail: String?, val lastLatencyMs: Long? = null)

    private val executor: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "gemma").apply { priority = Thread.NORM_PRIORITY - 1 } }
    @Volatile private var engine: Engine? = null
    @Volatile private var activeConversation: Conversation? = null
    @Volatile var state: State = initialState(); private set

    val modelDir: File get() = File(context.filesDir, "models").apply { mkdirs() }

    fun installedModel(): File? = modelDir.listFiles { f -> f.isFile && f.name.endsWith(".litertlm") && f.length() > 0 }?.maxByOrNull { it.lastModified() }

    /** Written before native initialisation and removed after; if it survives a restart, the last load killed the app. */
    private val loadingMarker: File get() = File(modelDir, ".loading")

    private fun initialState(): State {
        val model = runCatching { installedModel() }.getOrNull()
        return when {
            model == null -> State(Status.NOT_INSTALLED, null, null, "No Gemma model imported. Answers use the deterministic engine.")
            model.length() < MIN_MODEL_BYTES -> State(Status.FAILED, model.name, null, "This file is ${model.length() / 1_000_000} MB, too small to be a Gemma model. Import a real .litertlm build.")
            loadingMarker.exists() -> State(Status.FAILED, model.name, null, "The last attempt to load this model stopped the app, so it will not be loaded automatically. The file is probably corrupt or incompatible with LiteRT-LM 0.16.1.")
            else -> State(Status.INSTALLED, model.name, null, "Model imported but not loaded yet." + (headerWarning(model)?.let { " $it" } ?: ""))
        }
    }

    private fun headerWarning(model: File): String? = runCatching {
        val head = ByteArray(8); model.inputStream().use { it.read(head) }
        if (String(head, Charsets.US_ASCII) == "LITERTLM") null else "Warning: the file header is not the LiteRT-LM signature."
    }.getOrNull()

    override val isReady: Boolean get() = state.status == Status.VERIFIED && engine != null
    override val label: String get() = state.modelName?.substringBeforeLast('.') ?: "Gemma"

    /** Loads the model and runs one short generation; the state becomes VERIFIED only if text actually came back. */
    fun load(onChange: (State) -> Unit) {
        val model = installedModel() ?: run { state = initialState(); onChange(state); return }
        if (model.length() < MIN_MODEL_BYTES) { state = initialState(); onChange(state); return }
        loadingMarker.delete() // an explicit user request to retry
        state = State(Status.LOADING, model.name, null, "Loading ${model.name} on device…"); onChange(state)
        executor.execute {
            close()
            loadingMarker.writeText(System.currentTimeMillis().toString())
            val attempts = listOf<Pair<String, () -> Backend>>("GPU" to { Backend.GPU() }, "CPU" to { Backend.CPU() })
            var lastError: Throwable? = null
            for ((name, backend) in attempts) {
                try {
                    val started = System.currentTimeMillis()
                    val created = Engine(EngineConfig(modelPath = model.absolutePath, backend = backend(), cacheDir = context.cacheDir.absolutePath))
                    created.initialize()
                    engine = created
                    val reply = generateBlocking("Reply with the single word READY.", "You are a test harness.", 16)
                    if (reply.isBlank()) throw IllegalStateException("Model returned empty text")
                    state = State(Status.VERIFIED, model.name, name, "Inference verified on device: \"${reply.take(40).trim()}\"", System.currentTimeMillis() - started)
                    loadingMarker.delete()
                    onChange(state); return@execute
                } catch (error: Throwable) {
                    lastError = error
                    runCatching { engine?.close() }; engine = null
                    if (error is OutOfMemoryError) break
                }
            }
            loadingMarker.delete()
            state = State(Status.FAILED, model.name, null, "Could not run on this device: ${lastError?.javaClass?.simpleName}: ${lastError?.message?.take(160)}")
            onChange(state)
        }
    }

    override fun generate(prompt: String, system: String, maxTokens: Int, timeoutMs: Long): LlmResult {
        if (!isReady) return LlmResult.Unavailable(state.detail ?: "Gemma is not loaded")
        val started = System.currentTimeMillis()
        val future: Future<String> = executor.submit(Callable { generateBlocking(prompt, system, maxTokens) })
        return try {
            val text = future.get(timeoutMs, TimeUnit.MILLISECONDS)
            state = state.copy(lastLatencyMs = System.currentTimeMillis() - started)
            if (text.isBlank()) LlmResult.Failed("Gemma returned empty text") else LlmResult.Text(text.trim(), System.currentTimeMillis() - started)
        } catch (_: TimeoutException) {
            cancel(); future.cancel(true); LlmResult.Failed("Gemma timed out after ${timeoutMs / 1000}s")
        } catch (error: OutOfMemoryError) {
            LlmResult.Failed("Out of memory while generating")
        } catch (error: Exception) {
            LlmResult.Failed("Gemma error: ${(error.cause ?: error).message?.take(160)}")
        }
    }

    override fun cancel() { runCatching { activeConversation?.cancelProcess() } }

    private fun generateBlocking(prompt: String, system: String, maxTokens: Int): String {
        val current = engine ?: throw IllegalStateException("Engine not initialized")
        val conversation = current.createConversation(
            ConversationConfig(
                systemInstruction = Contents.of(system),
                samplerConfig = SamplerConfig(1, 1.0, 0.0, 0),
                maxOutputToken = maxTokens
            )
        )
        activeConversation = conversation
        try {
            val reply = conversation.sendMessage(prompt)
            return reply.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
        } finally {
            activeConversation = null
            conversation.close()
        }
    }

    fun close() { runCatching { engine?.close() }; engine = null }

    fun importModel(input: java.io.InputStream, name: String): File {
        val safe = name.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_").let { if (it.endsWith(".litertlm")) it else "$it.litertlm" }
        val temporary = File(modelDir, "$safe.part")
        temporary.outputStream().use { input.copyTo(it, 1 shl 20) }
        if (temporary.length() < MIN_MODEL_BYTES) {
            val mb = temporary.length() / 1_000_000; temporary.delete()
            throw java.io.IOException("File is $mb MB, too small to be a Gemma model (expected hundreds of MB)")
        }
        loadingMarker.delete()
        modelDir.listFiles()?.filter { it.name.endsWith(".litertlm") }?.forEach { it.delete() }
        val target = File(modelDir, safe)
        if (!temporary.renameTo(target)) throw java.io.IOException("Could not store model")
        close(); state = initialState()
        return target
    }

    fun removeModel() { close(); modelDir.listFiles()?.forEach { it.delete() }; state = initialState() }

    companion object { const val MIN_MODEL_BYTES = 100_000_000L }
}
