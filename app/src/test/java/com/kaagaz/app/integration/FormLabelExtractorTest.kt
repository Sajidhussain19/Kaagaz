package com.kaagaz.app.integration

import com.kaagaz.app.fixtures.MissionOsFixtures
import com.kaagaz.app.forms.FormLabelExtractor
import com.kaagaz.app.forms.FormLibrary
import com.kaagaz.app.forms.FormMapper
import com.kaagaz.app.knowledge.FactExtractor
import com.kaagaz.app.knowledge.TrustLedger
import com.kaagaz.app.knowledge.TrustState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FormLabelExtractorTest {
    private val form = listOf(MissionOsFixtures.page(1,
        "NATIONAL MERIT SCHOLARSHIP BOARD (FICTIONAL)",
        "SCHOLARSHIP APPLICATION FORM",
        "Please fill in every field in block letters.",
        "Applicant name: ____________________", "Date of birth: ____________________", "Email address: ____________________",
        "Mobile number: ____________________", "University / college: ____________________", "Degree programme: ____________________",
        "Year of passing: ____________________", "CGPA: ____________________", "Current employer: ____________________",
        "Bank account number: ____________________", "Net banking password: ____________________", "Signature: ____________________"))

    @Test fun readsBlankFieldLabelsAndIgnoresInstructions() {
        val labels = FormLabelExtractor.labels(form)
        assertEquals(12, labels.size)
        assertEquals("Applicant name", labels.first())
        assertTrue(FormLabelExtractor.isBlankForm(form))
        assertFalse(FormLabelExtractor.isBlankForm(MissionOsFixtures.resume))
    }

    @Test fun blankFormProducesNoJunkFacts() {
        assertTrue(FactExtractor.extract("f", "form.pdf", form).isEmpty())
    }

    @Test fun realFormIsFilledFromVerifiedFactsWithSensitiveAndSignatureLeftForTheUser() {
        val docs = MissionOsFixtures.docs()
        val facts = MissionOsFixtures.facts(docs)
        val spec = FormLibrary.fromLabels("Scholarship form", FormLabelExtractor.labels(form))
        val ledger = TrustLedger.build(facts, docs.associate { it.id to it.kind }, spec.fields.mapNotNull { it.type }.toSet())
        val map = FormMapper.map(spec, ledger).associateBy { it.field.label }
        assertEquals("Aarav Mehta", map.getValue("Applicant name").value)
        assertTrue(map.getValue("Email address").fillable)
        assertEquals(TrustState.CONFLICTING, map.getValue("Year of passing").state)
        assertFalse(map.getValue("Bank account number").fillable)
        assertFalse(map.getValue("Net banking password").fillable)
        assertNull(map.getValue("Signature").value)
        assertTrue(map.getValue("Signature").note.contains("Sign this yourself"))
    }
}
