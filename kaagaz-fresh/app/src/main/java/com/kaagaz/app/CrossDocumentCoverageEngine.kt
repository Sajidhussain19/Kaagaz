package com.kaagaz.app

/** Combines only documents that the user explicitly associated. */
object CrossDocumentCoverageEngine {
    data class Source(
        val documentId: String,
        val documentName: String,
        val pages: List<OcrPageResult>,
        val fields: List<InsuranceField> = emptyList()
    )

    fun supports(question: String): Boolean {
        val text = question.lowercase()
        return listOf("paid", "payment", "premium remains", "scheduled", "outstanding", "how much is due").any(text::contains)
    }

    fun answer(question: String, primary: Source, associated: List<Source>): CoverageIntelligenceEngine.Answer {
        if (!supports(question)) return tagged(CoverageIntelligenceEngine.answer(question, primary.pages, primary.fields), primary)
        val associatedResults = associated.map { source -> source to CoverageIntelligenceEngine.answer(question, source.pages, source.fields) }
            .filter { it.second.kind == CoverageIntelligenceEngine.AnswerKind.RESULTS && it.second.passages.isNotEmpty() }
        val primaryResult = primary to CoverageIntelligenceEngine.answer(question, primary.pages, primary.fields)
        val results = associatedResults.ifEmpty {
            listOf(primaryResult).filter { it.second.kind == CoverageIntelligenceEngine.AnswerKind.RESULTS && it.second.passages.isNotEmpty() }
        }
        if (results.isEmpty()) return tagged(CoverageIntelligenceEngine.answer(question, primary.pages, primary.fields), primary)

        val distinctSummaries = results.map { it.second.summary }.distinct()
        val passages = results.flatMap { (source, answer) ->
            answer.passages.map { it.copy(sourceDocumentId = source.documentId, sourceDocumentName = source.documentName) }
        }.distinctBy { passage ->
            "${passage.sourceDocumentId}:${passage.category}:${passage.evidence.joinToString { "${it.pageNumber}:${it.boundingBox}:${it.text}" }}"
        }
        if (distinctSummaries.size > 1) return CoverageIntelligenceEngine.Answer(
            question,
            "Associated documents contain conflicting recorded values. Review each cited source before relying on an amount.",
            CoverageIntelligenceEngine.AnswerKind.AMBIGUOUS,
            passages
        )
        return CoverageIntelligenceEngine.Answer(question, distinctSummaries.single(), CoverageIntelligenceEngine.AnswerKind.RESULTS, passages)
    }

    private fun tagged(answer: CoverageIntelligenceEngine.Answer, source: Source) = answer.copy(
        passages = answer.passages.map { it.copy(sourceDocumentId = source.documentId, sourceDocumentName = source.documentName) }
    )
}
