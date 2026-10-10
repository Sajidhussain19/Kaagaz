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

    fun systemPrompt(): String = "You plan paperwork missions. Reply with JSON only, no prose: " +
        "{\"steps\":[{\"action\":\"ACTION\",\"args\":{}}]}. Allowed actions: " + MissionAction.values().joinToString { it.name } +
        ". Use args {\"query\":\"...\"} for SEARCH_DOCUMENTS, {\"form\":\"uni-app\"} for PREPARE_FORM/FILL_FORM/RUN_CRASH_TEST, " +
        "{\"target\":\"bill\"} for SCHEDULE_REMINDER. At most 7 steps."

    fun plan(goal: String, llm: LlmClient): MissionPlan {
        if (llm.isReady) {
            when (val r = llm.generate("Goal: $goal", systemPrompt(), maxTokens = 220, timeoutMs = 40_000)) {
                is LlmResult.Text -> parse(r.text)?.let { steps -> return MissionPlan(goal, enforce(goal, steps), "${llm.label} (validated)", "Plan proposed by Gemma in ${r.latencyMs} ms and checked against the action whitelist.") }
                    ?: return deterministic(goal).copy(plannerNote = "Gemma's plan was not valid JSON with allowed actions, so the rule-based planner was used.")
                is LlmResult.Failed -> return deterministic(goal).copy(plannerNote = "Gemma failed (${r.reason}); rule-based planner used.")
                is LlmResult.Unavailable -> Unit
            }
        }
        return deterministic(goal)
    }

    /** Strict parser: unknown actions, malformed args, or more than 7 steps invalidate the step or the plan. */
    fun parse(raw: String): List<MissionStep>? {
        val json = Regex("\\{[\\s\\S]*}").find(raw)?.value ?: return null
        val obj = runCatching { com.google.gson.JsonParser.parseString(json).asJsonObject }.getOrNull() ?: return null
        val array = obj.getAsJsonArray("steps") ?: return null
        if (array.size() == 0 || array.size() > 7) return null
        val steps = array.mapNotNull { element ->
            val o = runCatching { element.asJsonObject }.getOrNull() ?: return@mapNotNull null
            val action = runCatching { MissionAction.valueOf(o.get("action").asString.trim().uppercase(Locale.ENGLISH)) }.getOrNull() ?: return@mapNotNull null
            val args = o.getAsJsonObject("args")?.entrySet()?.associate { (k, v) -> k to (runCatching { v.asString }.getOrNull() ?: "") }.orEmpty()
                .filterKeys { it in setOf("query", "form", "target") }.mapValues { it.value.take(80) }
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
