package com.kaagaz.app.core

import android.graphics.Rect
import android.net.Uri

data class SelectedDocument(
    val id: String,
    val uri: Uri,
    val name: String,
    val type: DocumentType,
    val mimeType: String,
    val pageCount: Int = 1
)

data class SavedDocumentSummary(
    val id: String,
    val name: String,
    val type: DocumentType,
    val mimeType: String,
    val pageCount: Int,
    val createdAt: Long,
    val policyNumber: String?,
    val insuredName: String?,
    val expiryDate: String?,
    val fileAvailable: Boolean
)

data class StoredDocument(
    val document: SelectedDocument,
    val ocrPages: List<OcrPageResult>,
    val fields: List<InsuranceField>
)

enum class ReminderState { SAVED_PERMISSION_REQUIRED, SCHEDULED, SCHEDULING_FAILED }

data class DeadlineReminder(
    val documentId: String,
    val deadlineType: String,
    val deadlineEpochDay: Long,
    val sourceValue: String,
    val sourceStatus: String,
    val offsetsDays: Set<Int>,
    val state: ReminderState,
    val updatedAt: Long
)

enum class DocumentType { IMAGE, PDF }

data class OcrLineEvidence(
    val pageNumber: Int,
    val text: String,
    val boundingBox: Rect?,
    val confidence: Float,
    val sourceWidth: Int,
    val sourceHeight: Int
)

data class OcrPageResult(
    val pageNumber: Int,
    val text: String,
    val lines: List<OcrLineEvidence>
)

enum class InsuranceFieldKey(val label: String) {
    INSURER("Insurer name"),
    POLICY_NUMBER("Policy number"),
    INSURED_NAME("Insured person's name"),
    VEHICLE_REGISTRATION("Vehicle registration number"),
    PREMIUM("Premium amount"),
    POLICY_START("Policy start date"),
    POLICY_EXPIRY("Policy expiry date")
}

enum class VerificationStatus { SUPPORTED, NEEDS_REVIEW, NOT_FOUND }

data class InsuranceField(
    val key: InsuranceFieldKey,
    val value: String?,
    val evidence: List<OcrLineEvidence>,
    val status: VerificationStatus,
    val reviewReason: String? = null,
    val userValue: String? = null,
    val isUserConfirmed: Boolean = false
) {
    val displayedValue: String? get() = userValue ?: value
    val isEdited: Boolean get() = userValue != null && userValue != value
}
