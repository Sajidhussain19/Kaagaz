package com.kaagaz.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class MissionOsEngineTest {
    private val docs = MissionOsFixtures.docs()
    private val facts = MissionOsFixtures.facts(docs)
    private val kinds = docs.associate { it.id to it.kind }
    private val ledger = TrustLedger.build(facts, kinds, setOf(FactType.FULL_NAME, FactType.ADDRESS))
    private val today = LocalDate.of(2026, 10, 10)

    private fun fact(doc: String, type: FactType) = facts.firstOrNull { it.documentId == doc && it.type == type }
    private fun entry(type: FactType) = ledger.first { it.type == type }

    // ---- classification and extraction ----

    @Test fun classifiesFictionalDocuments() {
        assertEquals(DocKind.RESUME, kinds["resume"])
        assertEquals(DocKind.EDUCATION_CERTIFICATE, kinds["cert"])
        assertEquals(DocKind.TRANSCRIPT, kinds["transcript"])
        assertEquals(DocKind.BILL, kinds["bill1"])
    }

    @Test fun extractsLabelledFactsWithPageEvidence() {
        val dob = fact("resume", FactType.DATE_OF_BIRTH)!!
        assertEquals("2002-03-14", dob.normalized)
        assertEquals(1, dob.page)
        assertEquals("Date of birth: 14 March 2002", dob.evidence)
        assertEquals("9876543210", fact("resume", FactType.PHONE)!!.normalized)
        assertEquals("2340", fact("bill1", FactType.BILL_AMOUNT)!!.normalized)
        assertEquals("DPD-2026-0915", fact("bill1", FactType.BILL_NUMBER)!!.normalized)
        assertEquals("2026-10-20", fact("bill1", FactType.DUE_DATE)!!.normalized)
    }

    @Test fun extractsCertificateSentenceFacts() {
        assertEquals("2023", fact("cert", FactType.GRADUATION_YEAR)!!.normalized)
        assertTrue(facts.any { it.documentId == "cert" && it.type == FactType.DEGREE && it.rawValue.startsWith("Bachelor of Technology") })
    }

    @Test fun realOcrQuirksDoNotCreateFalseConflicts() {
        assertEquals("aarav.mehta.demo@example.com", fact("resume", FactType.EMAIL)!!.normalized)
        assertEquals(TrustState.VERIFIED, entry(FactType.DEGREE).state)
        assertEquals(TrustState.VERIFIED, entry(FactType.SKILLS).state)
        assertEquals(TrustState.VERIFIED, entry(FactType.FULL_NAME).state)
        assertTrue(facts.none { it.type == FactType.DEGREE && it.rawValue.contains("CERTIFICATE", ignoreCase = true) })
        assertTrue(facts.none { it.type == FactType.FULL_NAME && it.rawValue.contains("awarded") })
    }

    @Test fun onlySupportedInsuranceFieldsBecomeFacts() {
        val reviewOnly = InsuranceField(InsuranceFieldKey.INSURED_NAME, "Statement date", emptyList(), VerificationStatus.NEEDS_REVIEW)
        val supported = InsuranceField(InsuranceFieldKey.POLICY_NUMBER, "NDL-CHILD-2027-0042", emptyList(), VerificationStatus.SUPPORTED)
        val out = FactExtractor.extract("p", "p.pdf", emptyList(), listOf(reviewOnly, supported))
        assertEquals(listOf(FactType.POLICY_NUMBER), out.map { it.type })
    }

    @Test fun billNamesStayDocumentScoped() {
        assertNull(fact("bill1", FactType.FULL_NAME))
        assertEquals("aarav mehta", fact("bill1", FactType.ACCOUNT_HOLDER)!!.normalized)
    }

    @Test fun columnByColumnTableWithoutGeometryPairsByPosition() {
        val page = MissionOsFixtures.page(1, "Full name", "Date of birth", "Riya Sen", "02 January 1999")
        val extracted = FactExtractor.extract("d", "table.pdf", listOf(page)) { null }
        assertEquals("riya sen", extracted.first { it.type == FactType.FULL_NAME }.normalized)
        assertEquals("1999-01-02", extracted.first { it.type == FactType.DATE_OF_BIRTH }.normalized)
    }

    @Test fun missingFieldIsNeverInvented() {
        assertTrue(facts.none { it.type == FactType.ADDRESS })
        assertEquals(TrustState.MISSING, entry(FactType.ADDRESS).state)
    }

    // ---- trust ledger ----

    @Test fun plantedConflictsAreDetected() {
        val year = entry(FactType.GRADUATION_YEAR)
        assertEquals(TrustState.CONFLICTING, year.state)
        assertEquals(setOf("2023", "2024"), year.groups.map { it.normalized }.toSet())
        assertEquals(TrustState.CONFLICTING, entry(FactType.DATE_OF_BIRTH).state)
    }

    @Test fun equalScoresWithDifferentFormattingAgree() {
        assertEquals(TrustState.VERIFIED, entry(FactType.CGPA).state)
        assertEquals(3, entry(FactType.CGPA).groups.single().facts.size)
    }

    @Test fun nameVariantNeedsConfirmationNotConflict() {
        val variants = TrustLedger.build(listOf(
            Fact("a", "a.pdf", FactType.FULL_NAME, "Aarav Mehta", "aarav mehta", 1, "Name: Aarav Mehta", .9f, ExtractionMethod.LABEL_RULE),
            Fact("b", "b.pdf", FactType.FULL_NAME, "Aarav K Mehta", "aarav k mehta", 1, "Name: Aarav K Mehta", .9f, ExtractionMethod.LABEL_RULE)
        ), mapOf("a" to DocKind.RESUME, "b" to DocKind.TRANSCRIPT))
        assertEquals(TrustState.NEEDS_CONFIRMATION, variants.single().state)
    }

    @Test fun userResolutionVerifiesButKeepsCompetingEvidence() {
        val resolved = TrustLedger.build(facts, kinds, emptySet(), mapOf(FactType.GRADUATION_YEAR to Resolution(FactType.GRADUATION_YEAR, "2023", 1L)))
            .first { it.type == FactType.GRADUATION_YEAR }
        assertEquals(TrustState.VERIFIED, resolved.state)
        assertTrue(resolved.resolvedByUser)
        assertEquals("2023", resolved.usable!!.normalized)
        assertEquals(2, resolved.groups.size)
    }

    @Test fun lowConfidenceSourceNeedsConfirmation() {
        val weak = TrustLedger.build(listOf(Fact("a", "a.pdf", FactType.EMAIL, "x@y.com", "x@y.com", 1, "Email: x@y.com", .4f, ExtractionMethod.LABEL_RULE)), mapOf("a" to DocKind.RESUME))
        assertEquals(TrustState.NEEDS_CONFIRMATION, weak.single().state)
    }

    // ---- crash test ----

    private val findings = CrashTest.run(docs, facts, ledger, FormLibrary.universityApplication,
        mapOf("year" to "2025", "email" to "aarav.mehta.demo@example.com"), today)

    @Test fun crashTestReportsConflictsWithEvidenceFromBothDocuments() {
        val conflict = findings.first { it.id == "conflict:GRADUATION_YEAR" }
        assertEquals(Severity.HIGH, conflict.severity)
        assertEquals(setOf("resume", "cert"), conflict.evidence.map { it.documentId }.toSet())
        assertTrue(conflict.evidence.all { it.page == 1 })
    }

    @Test fun crashTestFlagsDuplicateBillAndExactDeadline() {
        val dup = findings.first { it.kind == FindingKind.DUPLICATE_BILL }
        assertEquals(setOf("bill1", "bill2"), dup.evidence.map { it.documentId }.toSet())
        val due = findings.filter { it.kind == FindingKind.DUE_DATE }
        assertTrue(due.all { it.title == "₹2,340 due in 10 days" })
    }

    @Test fun crashTestFlagsSensitiveRequestsAsReviewOnly() {
        val sensitive = findings.filter { it.kind == FindingKind.SENSITIVE_REQUEST }
        assertEquals(2, sensitive.size)
        assertTrue(sensitive.all { it.severity == Severity.REVIEW })
    }

    @Test fun crashTestFlagsUnsupportedDraftValueButNotSupportedOne() {
        assertTrue(findings.any { it.id == "unsupported:uni-app:year" })
        assertFalse(findings.any { it.id == "unsupported:uni-app:email" })
    }

    @Test fun crashTestReportsMissingSupportingDocument() {
        val onlyResume = docs.filter { it.id == "resume" }
        val result = CrashTest.run(onlyResume, facts, TrustLedger.build(facts.filter { it.documentId == "resume" }, kinds), FormLibrary.universityApplication, today = today)
        assertTrue(result.any { it.id == "missingdoc:uni-app:EDUCATION_CERTIFICATE" })
    }

    @Test fun outstandingPremiumDetectedFromRealStatementFixture() {
        val statement = AuditDocument("st", "statement.pdf", DocKind.PAYMENT_STATEMENT, RealisticOcrFixtures.childStatement)
        val result = CrashTest.run(listOf(statement), emptyList(), emptyList(), today = today)
        val outstanding = result.single { it.kind == FindingKind.OUTSTANDING_PAYMENT }
        assertTrue(outstanding.explanation, outstanding.explanation.contains("2,500"))
    }

    // ---- form mapping ----

    @Test fun formMappingFillsOnlyVerifiedNonSensitiveFields() {
        val map = FormMapper.map(FormLibrary.universityApplication, ledger).associateBy { it.field.id }
        assertTrue(map.getValue("email").fillable)
        assertEquals("aarav.mehta.demo@example.com", map.getValue("email").value)
        assertEquals("aarav-resume.pdf p.1", map.getValue("email").note)
        assertFalse(map.getValue("year").fillable)
        assertEquals(TrustState.CONFLICTING, map.getValue("year").state)
        assertFalse(map.getValue("bank_password").fillable)
        assertNull(map.getValue("aadhaar").value)
    }

    @Test fun pastedLabelsAreIdentifiedAndSensitiveOnesFlagged() {
        val spec = FormLibrary.fromLabels("Job form", listOf("Applicant name", "E-mail address", "Year of passing", "OTP received", "Favourite colour"))
        assertEquals(listOf(FactType.FULL_NAME, FactType.EMAIL, FactType.GRADUATION_YEAR, null, null), spec.fields.map { it.type })
        assertNotNull(spec.fields[3].sensitiveReason)
        assertNull(spec.fields[4].sensitiveReason)
    }

    // ---- retrieval and grounded answers ----

    private val corpus = GroundedAnswerer.Corpus(docs, MissionOsFixtures.chunks(docs), facts, ledger)

    @Test fun retrievalRanksRelevantPassageFirstAndIgnoresIrrelevantQuestions() {
        val retriever = HybridRetriever(corpus.chunks, facts)
        assertTrue(retriever.search("When is the electricity bill due?").first().chunk.documentId.startsWith("bill"))
        assertTrue(retriever.search("What is the capital of Peru?").isEmpty())
    }

    @Test fun conflictingFactQuestionIsNotAnsweredWithOneValue() {
        val answer = GroundedAnswerer(NoLlm).answer("What is my graduation year?", corpus)
        assertEquals(AnswerSource.CONFLICT, answer.source)
        assertTrue(answer.text.contains("2023") && answer.text.contains("2024"))
    }

    @Test fun verifiedFactAnsweredWithEvidence() {
        val answer = GroundedAnswerer(NoLlm).answer("What is my email?", corpus)
        assertEquals(AnswerSource.FACT_LEDGER, answer.source)
        assertEquals("resume", answer.evidence.single().documentId)
    }

    @Test fun unknownQuestionAbstains() {
        assertEquals(AnswerSource.ABSTAIN, GroundedAnswerer(NoLlm).answer("What is the capital of Peru?", corpus).source)
    }

    private class FakeLlm(private val reply: String) : LlmClient {
        var prompts = mutableListOf<String>()
        override val isReady = true
        override val label = "fake-gemma"
        override fun generate(prompt: String, system: String, maxTokens: Int, timeoutMs: Long): LlmResult { prompts += prompt; return LlmResult.Text(reply, 5) }
    }

    @Test fun groundedLlmAnswerWithCitationIsAccepted() {
        val llm = FakeLlm("The bill of INR 2,340.00 is due on 20 October 2026 [1].")
        val answer = GroundedAnswerer(llm).answer("When is the electricity bill due?", corpus)
        assertEquals(AnswerSource.GEMMA, answer.source)
        assertTrue(llm.prompts.single().contains("[1] (electricity-bill"))
    }

    @Test fun llmAnswerWithInventedNumberIsRejected() {
        val answer = GroundedAnswerer(FakeLlm("The bill of INR 2,999 is due on 20 October 2026 [1].")).answer("When is the electricity bill due?", corpus)
        assertTrue(answer.source != AnswerSource.GEMMA)
        assertTrue(answer.note!!.contains("2999"))
    }

    @Test fun llmAnswerWithoutCitationIsRejected() {
        val answer = GroundedAnswerer(FakeLlm("It is due soon.")).answer("When is the electricity bill due?", corpus)
        assertTrue(answer.note!!.contains("cited no evidence"))
    }

    @Test fun llmNotFoundBecomesAbstention() {
        assertEquals(AnswerSource.ABSTAIN, GroundedAnswerer(FakeLlm("NOT_FOUND")).answer("When is the electricity bill due?", corpus).source)
    }

    @Test fun gemmaUnavailableAnswersDocumentFactWithExactValueAndDuplicateNote() {
        val answer = GroundedAnswerer(NoLlm).answer("When is the electricity bill due?", corpus)
        assertEquals(AnswerSource.FACT_LEDGER, answer.source)
        assertEquals("Due date: 20 Oct 2026 · amount due ₹2,340.", answer.text)
        assertTrue(answer.note!!.contains("duplicate"))
    }

    @Test fun unmappedQuestionFallsBackToBestOcrLineLabelledNoLlm() {
        val answer = GroundedAnswerer(NoLlm).answer("How many units were consumed?", corpus)
        assertEquals(AnswerSource.EXTRACTIVE, answer.source)
        assertEquals("“Units consumed: 312”", answer.text)
        assertTrue(answer.note!!.contains("No language model"))
    }

    @Test fun longDocumentChunksAndRetrievesQuickly() {
        val pages = (1..300).map { n -> MissionOsFixtures.page(n, "Section $n general terms apply to this fictional agreement.", "Clause $n says the renewal notice period is ${n % 30 + 1} days.") }
        val target = MissionOsFixtures.page(301, "The security deposit refund is INR 18,000 within 45 days of handover.")
        val chunks = Chunker.chunks("long", "long.pdf", pages + target) { null }
        val started = System.nanoTime()
        val hits = HybridRetriever(chunks).search("When is the security deposit refunded?")
        val ms = (System.nanoTime() - started) / 1_000_000
        assertEquals(301, hits.first().chunk.page)
        assertTrue("retrieval took $ms ms", ms < 2000)
    }

    // ---- mission orchestration ----

    @Test fun plannerRejectsUnknownActionsAndBadArgs() {
        assertNull(MissionPlanner.parse("""{"steps":[{"action":"DELETE_ALL_FILES","args":{}}]}"""))
        assertNull(MissionPlanner.parse("not json"))
        val parsed = MissionPlanner.parse("""Sure! {"steps":[{"action":"prepare_form","args":{"form":"uni-app","cmd":"rm -rf"}},{"action":"FILL_FORM","args":{"form":"evil"}}]}""")!!
        assertEquals(listOf(MissionAction.PREPARE_FORM), parsed.map { it.action })
        assertEquals(mapOf("form" to "uni-app"), parsed.single().args)
    }

    @Test fun plannerSurvivesMalformedModelJson() {
        assertNull(MissionPlanner.parse("""{"steps":{"action":"BUILD_LEDGER"}}"""))
        assertNull(MissionPlanner.parse("""{"steps":[{"action":["BUILD_LEDGER"]}]}"""))
        assertEquals(listOf(MissionAction.BUILD_LEDGER), MissionPlanner.parse("""{"steps":[{"action":"BUILD_LEDGER","args":"none"}]}""")!!.map { it.action })
        assertEquals(emptyMap<String, String>(), MissionPlanner.parse("""{"steps":[{"action":"SEARCH_DOCUMENTS","args":{"query":{"x":1}}}]}""")!!.single().args.filterValues { it.isNotEmpty() })
        assertNull(MissionPlanner.parse("""{"steps":[{"action":"BUILD_LEDGER"}"""))
    }

    @Test fun enforcedPlanValidatesBeforeConsequentialSteps() {
        val steps = MissionPlanner.enforce("apply", listOf(MissionStep(MissionAction.FILL_FORM, mapOf("form" to "uni-app"))))
        val order = steps.map { it.action }
        assertTrue(order.indexOf(MissionAction.RUN_CRASH_TEST) < order.indexOf(MissionAction.FILL_FORM))
        assertTrue(order.indexOf(MissionAction.PREPARE_FORM) < order.indexOf(MissionAction.FILL_FORM))
        assertEquals(MissionAction.SEARCH_DOCUMENTS, order.first())
    }

    private class RecordingTools(private val searchOk: Boolean = true) : MissionTools {
        val calls = mutableListOf<String>()
        override fun search(query: String) = ToolResult(searchOk, "search").also { calls += "search" }
        override fun buildLedger() = ToolResult(true, "ledger").also { calls += "ledger" }
        override fun crashTest(formId: String?) = ToolResult(true, "crash").also { calls += "crash" }
        override fun prepareForm(formId: String) = ToolResult(true, "prepare").also { calls += "prepare" }
        override fun fillForm(formId: String) = ToolResult(true, "fill").also { calls += "fill" }
        override fun saveProfile() = ToolResult(true, "profile").also { calls += "profile" }
        override fun scheduleReminder(target: String?) = ToolResult(true, "remind").also { calls += "remind" }
    }

    @Test fun consequentialStepsWaitForApprovalAndOnlyApprovedRun() {
        val tools = RecordingTools()
        val plan = MissionPlanner.deterministic("Prepare my university application")
        val runner = MissionRunner(tools, plan) {}
        runner.runAutomatic()
        assertEquals(MissionPhase.APPROVAL, runner.phase)
        assertFalse("fill" in tools.calls)
        runner.approve(emptySet())
        assertFalse("fill" in tools.calls)
        assertEquals(StepStatus.SKIPPED, runner.steps.first { it.action == MissionAction.FILL_FORM }.status)
        assertEquals(MissionPhase.DONE, runner.phase)
    }

    @Test fun approvedStepExecutesAndIsVerified() {
        val tools = RecordingTools()
        val runner = MissionRunner(tools, MissionPlanner.deterministic("Remind me about my bill due date")) {}
        runner.runAutomatic(); runner.approve(setOf(MissionAction.SCHEDULE_REMINDER))
        assertTrue("remind" in tools.calls)
        assertEquals(StepStatus.DONE, runner.steps.first { it.action == MissionAction.SCHEDULE_REMINDER }.status)
    }

    @Test fun failedRetrievalFailsMissionAndCancelStopsIt() {
        val failing = MissionRunner(RecordingTools(searchOk = false), MissionPlanner.deterministic("anything")) {}
        failing.runAutomatic()
        assertEquals(MissionPhase.FAILED, failing.phase)
        val tools = RecordingTools()
        lateinit var runner: MissionRunner
        runner = MissionRunner(tools, MissionPlanner.deterministic("anything")) { r -> if (r.steps.first().status == StepStatus.DONE) r.cancel() }
        runner.runAutomatic()
        assertEquals(MissionPhase.CANCELLED, runner.phase)
        assertFalse("crash" in tools.calls)
    }

    @Test fun lineFormatPlanAcceptsOnlyWhitelistedNames() {
        val steps = MissionPlanner.parseLines("1. SEARCH_DOCUMENTS\n2. BUILD_LEDGER\n3. DELETE_EVERYTHING\n- SCHEDULE_REMINDER", "remind me about my bill")!!
        assertEquals(listOf(MissionAction.SEARCH_DOCUMENTS, MissionAction.BUILD_LEDGER, MissionAction.SCHEDULE_REMINDER), steps.map { it.action })
        assertEquals(mapOf("target" to "bill"), steps.last().args)
        assertNull(MissionPlanner.parseLines("Sure, I can help with that.", "x"))
        val plan = MissionPlanner.plan("remind me about my bill", FakeLlm("SEARCH_DOCUMENTS\nSCHEDULE_REMINDER"))
        assertTrue(plan.plannedBy.contains("fake-gemma"))
        val order = plan.steps.map { it.action }
        assertTrue(order.indexOf(MissionAction.RUN_CRASH_TEST) < order.indexOf(MissionAction.SCHEDULE_REMINDER))
    }

    @Test fun plannerFallsBackWhenModelUnavailableOrInvalid() {
        assertEquals("Rule-based planner", MissionPlanner.plan("apply to university", NoLlm).plannedBy)
        val invalid = MissionPlanner.plan("apply to university", FakeLlm("I think you should apply!"))
        assertTrue(invalid.plannerNote!!.contains("no allowed step names"))
        val valid = MissionPlanner.plan("apply", FakeLlm("""{"steps":[{"action":"PREPARE_FORM","args":{"form":"uni-app"}}]}"""))
        assertTrue(valid.plannedBy.contains("fake-gemma"))
    }
}
