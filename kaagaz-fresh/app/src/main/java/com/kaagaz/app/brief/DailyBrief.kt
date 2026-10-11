package com.kaagaz.app.brief

import com.kaagaz.app.audit.Finding
import com.kaagaz.app.audit.FindingKind
import com.kaagaz.app.knowledge.DocKind
import com.kaagaz.app.knowledge.Fact
import com.kaagaz.app.knowledge.FactType
import com.kaagaz.app.knowledge.LedgerEntry
import com.kaagaz.app.knowledge.Normalizer
import com.kaagaz.app.knowledge.TrustState
import com.kaagaz.app.letters.FixItLetters
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * "Today": at most four plain-language actions, ranked by urgency, each with one verb and the screen that does it.
 * Built only from computed state (ledger, extracted dates, crash-test findings); no model is involved.
 */
object DailyBrief {
    enum class Target { LEDGER, CRASH_TEST, DOCUMENT, FORM }
    enum class Tone { URGENT, ATTENTION, INFO, GOOD }

    data class Item(
        val title: String,
        val detail: String,
        val tone: Tone,
        val target: Target,
        val documentId: String? = null,
        val action: String = "Open",
        val letterFor: FactType? = null,
        val findingId: String? = null
    )

    data class Brief(val status: String, val items: List<Item>)

    fun build(ledger: List<LedgerEntry>, facts: List<Fact>, findings: List<Finding>, today: LocalDate = LocalDate.now(), kinds: Map<String, DocKind> = emptyMap()): List<Item> =
        brief(ledger, facts, findings, today, kinds).items

    fun brief(ledger: List<LedgerEntry>, facts: List<Fact>, findings: List<Finding>, today: LocalDate = LocalDate.now(), kinds: Map<String, DocKind> = emptyMap()): Brief {
        val items = mutableListOf<Pair<Int, Item>>()
        val day = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)

        facts.filter { it.type == FactType.DUE_DATE || it.type == FactType.POLICY_EXPIRY }
            .mapNotNull { f -> runCatching { LocalDate.parse(f.normalized) }.getOrNull()?.let { Triple(f, it, ChronoUnit.DAYS.between(today, it)) } }
            .filter { (_, _, days) -> days in -30..14 }
            .distinctBy { (f, _, _) -> f.type to f.normalized }
            .forEach { (f, date, days) ->
                val emi = facts.firstOrNull { it.documentId == f.documentId && it.type == FactType.EMI_AMOUNT }
                val amount = (facts.firstOrNull { it.documentId == f.documentId && it.type == FactType.BILL_AMOUNT } ?: emi)
                    ?.let { Normalizer.display(it.type, it.normalized, it.rawValue) }
                val what = if (emi != null || kinds[f.documentId] == DocKind.LOAN_STATEMENT) "loan EMI" else billName(f.documentName)
                val title = if (f.type == FactType.POLICY_EXPIRY) "Renew policy" else "Pay $what" + (amount?.let { " · $it" } ?: "")
                val whenText = when { days < 0 -> "Was due ${date.format(day)} · ${-days} days overdue"; days == 0L -> "Due today"; days == 1L -> "Due tomorrow"; else -> "Due ${date.format(day)} · in $days days" }
                items += (if (days < 0) 0 else 10 + days.toInt()) to Item(title, whenText, if (days <= 3) Tone.URGENT else Tone.ATTENTION, Target.DOCUMENT, f.documentId, if (emi != null) "View loan" else "View bill")
            }

        ledger.filter { it.state == TrustState.CONFLICTING }.forEach { e ->
            val says = e.groups.take(2).joinToString(" · ") { g -> "${FixItLetters.kindName(kinds[g.facts.first().documentId]).replaceFirstChar { it.uppercase() }} says ${g.display}" }
            items += 5 to Item("Confirm your ${e.type.label.lowercase(Locale.ENGLISH)}", says, Tone.ATTENTION, Target.LEDGER, action = "Resolve", letterFor = e.type)
        }

        findings.firstOrNull { it.kind == FindingKind.DUPLICATE_BILL }?.let { f ->
            items += 8 to Item("Don't pay this bill twice", f.title.replace(" appears in ", " is in "), Tone.ATTENTION, Target.CRASH_TEST, action = "Review", findingId = f.id)
        }

        val missing = ledger.filter { it.state == TrustState.MISSING }
        if (missing.isNotEmpty()) items += 40 to Item("Add ${missing.take(2).joinToString(" and ") { it.type.label.lowercase(Locale.ENGLISH) }}",
            "No saved document states ${if (missing.size == 1) "it" else "them"} yet", Tone.INFO, Target.LEDGER, action = "Scan")

        val ranked = items.sortedBy { it.first }.map { it.second }.take(4)
        if (ranked.isEmpty()) return Brief("You're all caught up", listOf(Item("All clear", "No deadlines, conflicts or open findings right now", Tone.GOOD, Target.LEDGER)))
        val overdue = ranked.count { it.detail.contains("overdue") }
        val status = "${ranked.size} ${if (ranked.size == 1) "thing needs" else "things need"} you" + if (overdue > 0) " · $overdue overdue" else " · nothing overdue"
        return Brief(status, ranked)
    }

    /** "electricity-bill.pdf" → "electricity bill". */
    private fun billName(documentName: String) = documentName.substringBeforeLast('.').replace(Regex("[-_]+"), " ")
        .replace(Regex("(?i)\\b(copy|duplicate|scan|\\d+)\\b"), "").replace(Regex("\\s+"), " ").trim().ifBlank { "bill" }
}
