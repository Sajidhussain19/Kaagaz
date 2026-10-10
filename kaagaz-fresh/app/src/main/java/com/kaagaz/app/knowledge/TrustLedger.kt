package com.kaagaz.app.knowledge

import java.math.BigDecimal

enum class TrustState(val label: String) {
    VERIFIED("Verified"),
    CONFLICTING("Conflicting"),
    MISSING("Missing"),
    NEEDS_CONFIRMATION("Needs confirmation")
}

/** All facts that agree on one value, so the UI can show every source for it. */
data class ValueGroup(val normalized: String, val display: String, val facts: List<Fact>) {
    val documents: List<String> get() = facts.map { it.documentName }.distinct()
}

data class Resolution(val type: FactType, val chosenNormalized: String, val resolvedAt: Long)

data class LedgerEntry(
    val type: FactType,
    val state: TrustState,
    val groups: List<ValueGroup>,
    val reason: String,
    val resolvedByUser: Boolean = false
) {
    /** The value Kaagaz may use: only when verified (by evidence or by an explicit user resolution). */
    val usable: ValueGroup? get() = if (state == TrustState.VERIFIED) groups.firstOrNull() else null
}

/**
 * Person-level facts compared across the user's own documents. Only documents that describe the user (résumé,
 * certificates, transcripts, general paperwork) take part; insurance and bills often name other family members,
 * so their names stay document-scoped. Conflicts are never silently resolved: a user [Resolution] is required.
 */
object TrustLedger {
    val personKinds = setOf(DocKind.RESUME, DocKind.EDUCATION_CERTIFICATE, DocKind.TRANSCRIPT, DocKind.OTHER)
    const val MIN_CONFIDENCE = 0.65f

    fun build(
        facts: List<Fact>,
        docKinds: Map<String, DocKind>,
        required: Set<FactType> = emptySet(),
        resolutions: Map<FactType, Resolution> = emptyMap()
    ): List<LedgerEntry> {
        val personFacts = facts.filter { it.type.scope == FactScope.PERSON && (docKinds[it.documentId] ?: DocKind.OTHER) in personKinds }
        val byType = personFacts.groupBy { it.type }
        val types = (byType.keys + required).sortedBy { it.ordinal }
        return types.map { type -> entry(type, byType[type].orEmpty(), resolutions[type]) }
    }

    private fun entry(type: FactType, facts: List<Fact>, resolution: Resolution?): LedgerEntry {
        if (facts.isEmpty()) return LedgerEntry(type, TrustState.MISSING, emptyList(), "No saved document states this.")
        val groups = group(type, facts)
        if (resolution != null) {
            val chosen = groups.firstOrNull { it.normalized == resolution.chosenNormalized }
            if (chosen != null) return LedgerEntry(type, TrustState.VERIFIED, listOf(chosen) + (groups - chosen),
                if (groups.size > 1) "You chose this value; the other sources still disagree." else "You confirmed this value.", resolvedByUser = true)
        }
        if (groups.size > 1) {
            val variants = type == FactType.FULL_NAME && groups.all { g -> groups.all { o -> g === o || sameNameTokens(g.normalized, o.normalized) } }
            return if (variants) LedgerEntry(type, TrustState.NEEDS_CONFIRMATION, groups, "The name is written differently across documents.")
            else LedgerEntry(type, TrustState.CONFLICTING, groups, "${groups.size} different values in ${facts.map { it.documentId }.distinct().size} documents.")
        }
        val only = groups.single()
        val weak = only.facts.all { it.confidence in 0f..MIN_CONFIDENCE && !it.userConfirmed }
        return if (weak) LedgerEntry(type, TrustState.NEEDS_CONFIRMATION, groups, "The source text was read with low OCR confidence.")
        else LedgerEntry(type, TrustState.VERIFIED, groups, "Stated in ${only.documents.size} document${if (only.documents.size == 1) "" else "s"}.")
    }

    /** Groups facts that mean the same value. Scores compare numerically so "8.4" and "8.40" agree. */
    fun group(type: FactType, facts: List<Fact>): List<ValueGroup> {
        fun canonical(f: Fact) = when (type) {
            FactType.CGPA -> runCatching { BigDecimal(f.normalized).stripTrailingZeros().toPlainString() }.getOrDefault(f.normalized)
            else -> f.normalized
        }
        return facts.groupBy(::canonical).map { (value, list) ->
            val best = list.maxByOrNull { it.confidence }!!
            ValueGroup(value, Normalizer.display(type, best.normalized, best.rawValue), list.sortedBy { it.documentName })
        }.sortedByDescending { it.facts.size }
    }

    /** "aarav mehta" vs "aarav k mehta": one token set contains the other. */
    fun sameNameTokens(a: String, b: String): Boolean {
        val ta = a.split(' ').filter { it.length > 1 }.toSet(); val tb = b.split(' ').filter { it.length > 1 }.toSet()
        return ta.isNotEmpty() && tb.isNotEmpty() && (ta.containsAll(tb) || tb.containsAll(ta))
    }

    data class Counts(val verified: Int, val conflicting: Int, val missing: Int, val needsConfirmation: Int)

    fun counts(entries: List<LedgerEntry>) = Counts(
        entries.count { it.state == TrustState.VERIFIED }, entries.count { it.state == TrustState.CONFLICTING },
        entries.count { it.state == TrustState.MISSING }, entries.count { it.state == TrustState.NEEDS_CONFIRMATION }
    )
}
