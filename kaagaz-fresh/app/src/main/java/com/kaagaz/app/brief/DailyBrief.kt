package com.kaagaz.app.brief

import com.kaagaz.app.audit.Finding
import com.kaagaz.app.audit.Severity
import com.kaagaz.app.knowledge.Fact
import com.kaagaz.app.knowledge.FactType
import com.kaagaz.app.knowledge.LedgerEntry
import com.kaagaz.app.knowledge.Normalizer
import com.kaagaz.app.knowledge.TrustState
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * "What needs you today": at most four items, ranked by urgency, each pointing to the screen that resolves it.
 * Built only from computed state (ledger, extracted dates, crash-test findings); no model is involved.
 */
object DailyBrief {
    enum class Target { LEDGER, CRASH_TEST, DOCUMENT, FORM }
    enum class Tone { URGENT, ATTENTION, INFO, GOOD }

    data class Item(val title: String, val detail: String, val tone: Tone, val target: Target, val documentId: String? = null)

    fun build(ledger: List<LedgerEntry>, facts: List<Fact>, findings: List<Finding>, today: LocalDate = LocalDate.now()): List<Item> {
        val items = mutableListOf<Pair<Int, Item>>()

        facts.filter { it.type == FactType.DUE_DATE || it.type == FactType.POLICY_EXPIRY }
            .mapNotNull { f -> runCatching { LocalDate.parse(f.normalized) }.getOrNull()?.let { f to ChronoUnit.DAYS.between(today, it) } }
            .filter { (_, days) -> days in -30..14 }
            .distinctBy { (f, _) -> f.type to f.normalized }
            .forEach { (f, days) ->
                val amount = facts.firstOrNull { it.documentId == f.documentId && it.type == FactType.BILL_AMOUNT }
                    ?.let { Normalizer.display(FactType.BILL_AMOUNT, it.normalized, it.rawValue) }
                val what = if (f.type == FactType.POLICY_EXPIRY) "Policy expires" else "${amount ?: "A bill"} due"
                val title = when { days < 0 -> "$what ${-days} days ago"; days == 0L -> "$what today"; else -> "$what in $days days" }
                items += (if (days < 0) 0 else 10 + days.toInt()) to Item(title, "${f.documentName} · p.${f.page}", if (days <= 3) Tone.URGENT else Tone.ATTENTION, Target.DOCUMENT, f.documentId)
            }

        val conflicts = ledger.filter { it.state == TrustState.CONFLICTING }
        if (conflicts.isNotEmpty()) items += 5 to Item(
            "${conflicts.size} conflicting ${if (conflicts.size == 1) "record" else "records"}",
            conflicts.joinToString { it.type.label } + " differ across your documents", Tone.URGENT, Target.LEDGER)

        val duplicates = findings.count { it.kind == com.kaagaz.app.audit.FindingKind.DUPLICATE_BILL }
        if (duplicates > 0) items += 8 to Item("Possible duplicate bill", "The same bill appears more than once — avoid paying twice", Tone.ATTENTION, Target.CRASH_TEST)

        val sensitive = findings.count { it.severity == Severity.REVIEW }
        if (sensitive > 0) items += 30 to Item("$sensitive over-reaching form ${if (sensitive == 1) "field" else "fields"}", "Kaagaz will not fill them; review before sharing", Tone.INFO, Target.CRASH_TEST)

        val missing = ledger.filter { it.state == TrustState.MISSING }
        if (missing.isNotEmpty()) items += 40 to Item("${missing.size} profile ${if (missing.size == 1) "fact" else "facts"} missing",
            missing.take(3).joinToString { it.type.label }, Tone.INFO, Target.LEDGER)

        if (items.isEmpty()) return listOf(Item("All clear", "No conflicts, deadlines or open findings right now", Tone.GOOD, Target.LEDGER))
        return items.sortedBy { it.first }.map { it.second }.take(4)
    }
}
