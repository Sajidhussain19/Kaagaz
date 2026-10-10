package com.kaagaz.app

import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** The only operations a mission can perform. Model output is parsed into these or discarded. */
enum class MissionAction(val title: String, val consequential: Boolean) {
    SEARCH_DOCUMENTS("Find relevant documents", false),
    BUILD_LEDGER("Extract and cross-check facts", false),
    RUN_CRASH_TEST("Run the Paperwork Crash Test", false),
    PREPARE_FORM("Prepare a field-by-field form preview", false),
    FILL_FORM("Fill verified fields into the form", true),
    SAVE_PROFILE("Save verified facts to your encrypted profile", true),
    SCHEDULE_REMINDER("Schedule a deadline reminder", true)
}

enum class MissionPhase { PLAN, RETRIEVE, EXTRACT, VALIDATE, PROPOSE, APPROVAL, EXECUTE, VERIFY, DONE, FAILED, CANCELLED }

enum class StepStatus { PENDING, RUNNING, DONE, NEEDS_APPROVAL, SKIPPED, FAILED }

data class MissionStep(val action: MissionAction, val args: Map<String, String> = emptyMap(), val status: StepStatus = StepStatus.PENDING, val result: String? = null, val evidence: List<EvidenceRef> = emptyList())

data class MissionPlan(val goal: String, val steps: List<MissionStep>, val plannedBy: String, val plannerNote: String? = null)

/** Deterministic Kotlin tools behind every action. Implementations must report what actually happened. */
interface MissionTools {
    fun search(query: String): ToolResult
    fun buildLedger(): ToolResult
    fun crashTest(formId: String?): ToolResult
    fun prepareForm(formId: String): ToolResult
    fun fillForm(formId: String): ToolResult
    fun saveProfile(): ToolResult
    fun scheduleReminder(target: String?): ToolResult
}

data class ToolResult(val ok: Boolean, val summary: String, val evidence: List<EvidenceRef> = emptyList(), val verified: Boolean = ok)

object MissionPlanner {
    private val formIds = setOf(FormLibrary.universityApplication.id)

    fun systemPrompt(): String = "You plan paperwork missions by choosing steps from a fixed list. " +
        "Reply with only the step names, one per line, in order, at most 7. Allowed steps:\n" +
        MissionAction.values().joinToString("\n") { "${it.name} - ${it.title}" } +
        "\nExample for 'remind me about my bill':\nSEARCH_DOCUMENTS\nBUILD_LEDGER\nRUN_CRASH_TEST\nSCHEDULE_REMINDER"

    /** One allowed action name per line (what small models produce reliably). Unknown lines are ignored. */
    fun parseLines(raw: String, goal: String): List<MissionStep>? {
        val names = MissionAction.values().associateBy { it.name }
        val actions = raw.lines().mapNotNull { line ->
            Regex("[A-Z][A-Z_]{3,}").findAll(line.uppercase(Locale.ENGLISH)).map { it.value }.firstOrNull { it in names }?.let { names[it] }
        }.distinct().take(7)
        if (actions.isEmpty()) return null
        return actions.map { action ->
            MissionStep(action, when (action) {
                MissionAction.SEARCH_DOCUMENTS -> mapOf("query" to goal.take(80))
                MissionAction.PREPARE_FORM, MissionAction.FILL_FORM -> mapOf("form" to FormLibrary.universityApplication.id)
                MissionAction.RUN_CRASH_TEST -> if (listOf("application", "form", "apply").any { it in goal.lowercase(Locale.ENGLISH) }) mapOf("form" to FormLibrary.universityApplication.id) else emptyMap()
                MissionAction.SCHEDULE_REMINDER -> mapOf("target" to "bill")
                else -> emptyMap()
            })
        }
    }

    fun plan(goal: String, llm: LlmClient): MissionPlan = try { planWith(goal, llm) } catch (e: Exception) {
        deterministic(goal).copy(plannerNote = "Model planning failed (${e.javaClass.simpleName}); rule-based planner used.")
    }

    private fun planWith(goal: String, llm: LlmClient): MissionPlan {
        if (llm.isReady) {
            when (val r = llm.generate("Goal: $goal", systemPrompt(), maxTokens = 220, timeoutMs = 40_000)) {
                is LlmResult.Text -> (parse(r.text) ?: parseLines(r.text, goal))?.let { steps ->
                    return MissionPlan(goal, enforce(goal, steps), "${llm.label} (validated)",
                        "Gemma chose ${steps.size} step${if (steps.size == 1) "" else "s"} in ${r.latencyMs} ms; every step was checked against the action whitelist and safety steps were enforced.")
                } ?: return deterministic(goal).copy(plannerNote = "Gemma's reply contained no allowed step names (“${r.text.take(60).replace('\n', ' ')}…”), so the rule-based planner was used.")
                is LlmResult.Failed -> return deterministic(goal).copy(plannerNote = "Gemma failed (${r.reason}); rule-based planner used.")
                is LlmResult.Unavailable -> Unit
            }
        }
        return deterministic(goal)
    }

    /** Strict parser: unknown actions, malformed args, or more than 7 steps invalidate the step or the plan. */
    fun parse(raw: String): List<MissionStep>? = runCatching { parseStrict(raw) }.getOrNull()

    /** Any shape surprise from the model (args as a string, steps as an object, …) throws and invalidates the plan. */
    private fun parseStrict(raw: String): List<MissionStep>? {
        val json = Regex("\\{[\\s\\S]*}").find(raw)?.value ?: return null
        // Gson is lenient; a reply truncated at the token limit must not pass as a shorter plan.
        if (json.count { it == '{' } != json.count { it == '}' } || json.count { it == '[' } != json.count { it == ']' }) return null
        val obj = com.google.gson.JsonParser.parseString(json).asJsonObject
        val array = obj.get("steps")?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
        if (array.size() == 0 || array.size() > 7) return null
        val steps = array.mapNotNull { element ->
            val o = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val actionElement = o.get("action")?.takeIf { it.isJsonPrimitive } ?: return@mapNotNull null
            val action = runCatching { MissionAction.valueOf(actionElement.asString.trim().uppercase(Locale.ENGLISH)) }.getOrNull() ?: return@mapNotNull null
            val argsElement = o.get("args")
            val args = (if (argsElement != null && argsElement.isJsonObject) argsElement.asJsonObject.entrySet().associate { (k, v) ->
                k to (if (v.isJsonPrimitive) v.asString else "")
            } else emptyMap()).filterKeys { it in setOf("query", "form", "target") }.mapValues { it.value.take(80) }
            if (args["form"] != null && args["form"] !in formIds) return@mapNotNull null
            MissionStep(action, args)
        }
        return steps.takeIf { it.isNotEmpty() }
    }

    /** Mandatory safety rails regardless of planner: validate before any consequential step, and dedupe. */
    fun enforce(goal: String, steps: List<MissionStep>): List<MissionStep> {
        val out = steps.distinctBy { it.action to it.args }.toMutableList()
        if (out.none { it.action == MissionAction.BUILD_LEDGER }) out.add(0, MissionStep(MissionAction.BUILD_LEDGER))
        if (out.none { it.action == MissionAction.SEARCH_DOCUMENTS }) out.add(0, MissionStep(MissionAction.SEARCH_DOCUMENTS, mapOf("query" to goal.take(80))))
        val firstConsequential = out.indexOfFirst { it.action.consequential }
        if (firstConsequential >= 0 && out.subList(0, firstConsequential).none { it.action == MissionAction.RUN_CRASH_TEST }) {
            out.add(firstConsequential, MissionStep(MissionAction.RUN_CRASH_TEST, out.firstOrNull { it.args["form"] != null }?.args?.filterKeys { it == "form" }.orEmpty()))
        }
        if (out.any { it.action == MissionAction.FILL_FORM } && out.none { it.action == MissionAction.PREPARE_FORM }) {
            out.add(out.indexOfFirst { it.action == MissionAction.FILL_FORM }, MissionStep(MissionAction.PREPARE_FORM, mapOf("form" to "uni-app")))
        }
        return out.take(8)
    }

    fun deterministic(goal: String): MissionPlan {
        val g = goal.lowercase(Locale.ENGLISH)
        val form = mapOf("form" to FormLibrary.universityApplication.id)
        val steps = when {
            listOf("application", "apply", "form", "university", "admission", "job").any { it in g } -> listOf(
                MissionStep(MissionAction.SEARCH_DOCUMENTS, mapOf("query" to "name date of birth degree university graduation cgpa")),
                MissionStep(MissionAction.BUILD_LEDGER), MissionStep(MissionAction.RUN_CRASH_TEST, form),
                MissionStep(MissionAction.PREPARE_FORM, form), MissionStep(MissionAction.FILL_FORM, form))
            listOf("bill", "payment", "due", "deadline", "remind").any { it in g } -> listOf(
                MissionStep(MissionAction.SEARCH_DOCUMENTS, mapOf("query" to "amount due date bill number")),
                MissionStep(MissionAction.BUILD_LEDGER), MissionStep(MissionAction.RUN_CRASH_TEST),
                MissionStep(MissionAction.SCHEDULE_REMINDER, mapOf("target" to "bill")))
            listOf("profile", "save my", "details").any { it in g } -> listOf(
                MissionStep(MissionAction.SEARCH_DOCUMENTS, mapOf("query" to goal.take(80))),
                MissionStep(MissionAction.BUILD_LEDGER), MissionStep(MissionAction.RUN_CRASH_TEST), MissionStep(MissionAction.SAVE_PROFILE))
            else -> listOf(MissionStep(MissionAction.SEARCH_DOCUMENTS, mapOf("query" to goal.take(80))), MissionStep(MissionAction.BUILD_LEDGER), MissionStep(MissionAction.RUN_CRASH_TEST))
        }
        return MissionPlan(goal, enforce(goal, steps), "Rule-based planner", "No language model verified on this device; plan chosen by keyword rules.")
    }
}

/**
 * PLAN → RETRIEVE → EXTRACT → VALIDATE → PROPOSE → APPROVAL → EXECUTE → VERIFY. Non-consequential steps run
 * automatically; the runner stops at APPROVAL and executes consequential steps only after [approve].
 */
class MissionRunner(private val tools: MissionTools, plan: MissionPlan, private val onUpdate: (MissionRunner) -> Unit) {
    var plan: MissionPlan = plan; private set
    var phase: MissionPhase = MissionPhase.PROPOSE; private set
    var error: String? = null; private set
    private val cancelled = AtomicBoolean(false)

    val steps: List<MissionStep> get() = plan.steps
    val pendingApproval: List<MissionStep> get() = steps.filter { it.status == StepStatus.NEEDS_APPROVAL }

    fun cancel() { if (cancelled.getAndSet(true)) return; phase = MissionPhase.CANCELLED; onUpdate(this) }

    /** Runs every non-consequential step in order, then pauses for approval if anything consequential remains. */
    fun runAutomatic() {
        for (i in steps.indices) {
            if (cancelled.get()) return
            val step = steps[i]
            if (step.status != StepStatus.PENDING) continue
            if (step.action.consequential) { update(i) { it.copy(status = StepStatus.NEEDS_APPROVAL) }; continue }
            phase = phaseFor(step.action); update(i) { it.copy(status = StepStatus.RUNNING) }
            val result = execute(step)
            update(i) { it.copy(status = if (result.ok) StepStatus.DONE else StepStatus.FAILED, result = result.summary, evidence = result.evidence) }
            if (!result.ok && step.action == MissionAction.SEARCH_DOCUMENTS) { fail(result.summary); return }
        }
        phase = if (pendingApproval.isNotEmpty()) MissionPhase.APPROVAL else MissionPhase.DONE
        onUpdate(this)
    }

    /** Executes only the approved consequential steps, then verifies each one's result. */
    fun approve(approved: Set<MissionAction>) {
        if (phase != MissionPhase.APPROVAL) return
        for (i in steps.indices) {
            if (cancelled.get()) return
            val step = steps[i]
            if (step.status != StepStatus.NEEDS_APPROVAL) continue
            if (step.action !in approved) { update(i) { it.copy(status = StepStatus.SKIPPED, result = "Not approved — nothing was changed.") }; continue }
            phase = MissionPhase.EXECUTE; update(i) { it.copy(status = StepStatus.RUNNING) }
            val result = execute(step)
            phase = MissionPhase.VERIFY; onUpdate(this)
            val status = if (result.ok && result.verified) StepStatus.DONE else StepStatus.FAILED
            update(i) { it.copy(status = status, result = result.summary + if (result.ok && !result.verified) " (could not verify)" else "", evidence = result.evidence) }
        }
        phase = if (steps.any { it.status == StepStatus.FAILED }) MissionPhase.FAILED else MissionPhase.DONE
        onUpdate(this)
    }

    fun retryFailed() {
        plan = plan.copy(steps = steps.map { if (it.status == StepStatus.FAILED) it.copy(status = StepStatus.PENDING, result = null) else it })
        cancelled.set(false); error = null; runAutomatic()
    }

    private fun execute(step: MissionStep): ToolResult = try {
        when (step.action) {
            MissionAction.SEARCH_DOCUMENTS -> tools.search(step.args["query"] ?: plan.goal)
            MissionAction.BUILD_LEDGER -> tools.buildLedger()
            MissionAction.RUN_CRASH_TEST -> tools.crashTest(step.args["form"])
            MissionAction.PREPARE_FORM -> tools.prepareForm(step.args["form"] ?: FormLibrary.universityApplication.id)
            MissionAction.FILL_FORM -> tools.fillForm(step.args["form"] ?: FormLibrary.universityApplication.id)
            MissionAction.SAVE_PROFILE -> tools.saveProfile()
            MissionAction.SCHEDULE_REMINDER -> tools.scheduleReminder(step.args["target"])
        }
    } catch (e: Exception) { ToolResult(false, "Step failed: ${e.message?.take(120)}") }

    private fun phaseFor(action: MissionAction) = when (action) {
        MissionAction.SEARCH_DOCUMENTS -> MissionPhase.RETRIEVE
        MissionAction.BUILD_LEDGER -> MissionPhase.EXTRACT
        MissionAction.RUN_CRASH_TEST -> MissionPhase.VALIDATE
        else -> MissionPhase.PROPOSE
    }

    private fun update(index: Int, change: (MissionStep) -> MissionStep) {
        plan = plan.copy(steps = steps.mapIndexed { i, s -> if (i == index) change(s) else s }); onUpdate(this)
    }

    private fun fail(message: String) { error = message; phase = MissionPhase.FAILED; onUpdate(this) }
}
