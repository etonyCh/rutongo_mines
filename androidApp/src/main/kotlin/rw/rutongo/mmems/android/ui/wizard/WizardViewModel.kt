package rw.rutongo.mmems.android.ui.wizard

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import rw.rutongo.mmems.android.AppContainer
import rw.rutongo.mmems.android.data.LocalStore
import rw.rutongo.mmems.android.sync.SyncWorker
import rw.rutongo.mmems.shared.api.AttestationDto
import rw.rutongo.mmems.shared.api.MeView
import rw.rutongo.mmems.shared.enums.Answer
import rw.rutongo.mmems.shared.enums.Severity
import rw.rutongo.mmems.shared.inspection.DraftAnswer
import rw.rutongo.mmems.shared.inspection.DraftValidation
import rw.rutongo.mmems.shared.inspection.DraftValidator
import rw.rutongo.mmems.shared.inspection.InspectionDraft
import rw.rutongo.mmems.shared.inspection.WizardStep
import java.time.Instant
import java.util.UUID

data class PhotoCaptureRequest(
    val photoId: String,
    val itemId: String,
    val answerCode: String,
)

data class WizardUiState(
    val draft: InspectionDraft? = null,
    val step: WizardStep = WizardStep.CONTEXT,
    val validation: DraftValidation? = null,
    val submitting: Boolean = false,
    val queued: Boolean = false,
    val message: String? = null,
    val loadError: String? = null,
    val capture: PhotoCaptureRequest? = null,
    val photoCount: Int = 0,
)

class WizardViewModel(
    private val container: AppContainer,
    expectedId: String?,
    draftId: String?,
    me: MeView,
) : ViewModel() {
    private val _state = MutableStateFlow(WizardUiState())
    val state: StateFlow<WizardUiState> = _state.asStateFlow()

    init {
        val draft = if (draftId != null) container.store.draft(draftId) else expectedId?.let { createDraft(it, me) }
        if (draft == null) {
            _state.value = WizardUiState(loadError = "This inspection or draft could not be opened on this device.")
        } else {
            val validation = DraftValidator.validate(draft)
            _state.value =
                WizardUiState(
                    draft = draft,
                    step = draft.step,
                    validation = validation,
                    photoCount = container.store.pendingPhotosForInspection(draft.clientUuid).size,
                )
            container.store.saveDraft(draft)
        }
    }

    private fun createDraft(
        expectedId: String,
        me: MeView,
    ): InspectionDraft? {
        val expected = container.store.expected(expectedId) ?: return null
        val equipment = container.store.equipment(expected.equipment_id) ?: return null
        val checklist = container.store.checklistNow()
        val now = Instant.now().toString()
        return InspectionDraft(
            clientUuid = UUID.randomUUID().toString(),
            expectationId = expected.id,
            equipmentId = expected.equipment_id,
            equipmentName = expected.equipment_name,
            equipmentCode = expected.equipment_code,
            siteId = expected.site_id,
            siteName = expected.site_name,
            shiftId = expected.shift_id,
            shiftCode = expected.shift_code,
            reportDate = expected.report_date,
            operatorName = me.fullName,
            operatorEid = me.employeeId,
            engineHoursStart = equipment.engine_hours,
            lastEngineHours = equipment.engine_hours,
            capturedAt = now,
            startedAt = now,
            step = WizardStep.CONTEXT,
            answers =
                checklist.map {
                    DraftAnswer(
                        itemId = it.id,
                        code = it.code,
                        label = it.label,
                        category = it.category,
                        mandatory = it.mandatory == 1L,
                        safetyCritical = it.safety_critical == 1L,
                        photoRequiredOnFail = it.photo_required_on_fail == 1L,
                        naReasonRequired = it.na_reason_required == 1L,
                    )
                },
        )
    }

    private fun mutate(transform: (InspectionDraft) -> InspectionDraft) {
        val current = _state.value.draft ?: return
        val next = transform(current)
        val validation = DraftValidator.validate(next)
        container.store.saveDraft(next)
        _state.value = _state.value.copy(draft = next, validation = validation, message = null)
    }

    fun goTo(step: WizardStep) {
        mutate { it.copy(step = step) }
        _state.value = _state.value.copy(step = step)
    }

    fun next() {
        val order = WizardStep.entries
        val current = _state.value.step
        val index = order.indexOf(current)
        if (index < order.lastIndex) goTo(order[index + 1])
    }

    fun previous() {
        val order = WizardStep.entries
        val current = _state.value.step
        val index = order.indexOf(current)
        if (index > 0) goTo(order[index - 1])
    }

    fun answer(
        itemId: String,
        value: Answer,
    ) {
        mutate { draft ->
            draft.copy(
                answers =
                    draft.answers.map {
                        when {
                            it.itemId != itemId -> it
                            value == Answer.FAIL -> it.copy(answer = value, naReason = null, severity = it.severity ?: Severity.MINOR)
                            value == Answer.NA -> it.copy(answer = value, defectDescription = null, severity = null, immediateAction = null)
                            else -> it.copy(answer = value, naReason = null, defectDescription = null, severity = null, immediateAction = null)
                        }
                    },
            )
        }
    }

    fun naReason(
        itemId: String,
        reason: String,
    ) {
        mutate { draft -> draft.copy(answers = draft.answers.map { if (it.itemId == itemId) it.copy(naReason = reason) else it }) }
    }

    fun failure(
        itemId: String,
        description: String? = null,
        severity: Severity? = null,
        immediateAction: String? = null,
    ) {
        mutate { draft ->
            draft.copy(
                answers =
                    draft.answers.map {
                        if (it.itemId == itemId) {
                            it.copy(
                                defectDescription = description ?: it.defectDescription,
                                severity = severity ?: it.severity ?: Severity.MINOR,
                                immediateAction = immediateAction ?: it.immediateAction,
                            )
                        } else {
                            it
                        }
                    },
            )
        }
    }

    fun startHours(value: String) {
        mutate { it.copy(engineHoursStart = value.toDoubleOrNull()) }
    }

    fun endHours(value: String) {
        mutate { it.copy(engineHoursEnd = value.toDoubleOrNull()) }
    }

    fun fuelStart(value: String) {
        mutate { it.copy(fuelStartPercent = value.toDoubleOrNull()) }
    }

    fun fuelAdded(value: String) {
        mutate { it.copy(fuelAddedLitres = value.toDoubleOrNull()) }
    }

    fun operator(
        name: String,
        eid: String,
    ) {
        mutate { it.copy(operatorName = name, operatorEid = eid) }
    }

    fun requestPhoto(itemId: String) {
        val draft = _state.value.draft ?: return
        val answer = draft.answers.firstOrNull { it.itemId == itemId } ?: return
        _state.value =
            _state.value.copy(
                capture = PhotoCaptureRequest(UUID.randomUUID().toString(), itemId, answer.code),
            )
    }

    fun cancelPhoto() {
        _state.value = _state.value.copy(capture = null)
    }

    fun completePhoto(uri: Uri) {
        val request = _state.value.capture ?: return
        val draft = _state.value.draft ?: return
        val path =
            copyToPrivateStorage(uri, request.photoId) ?: run {
                _state.value = _state.value.copy(capture = null, message = "The photo could not be saved.")
                return
            }
        val size = java.io.File(path).length()
        container.store.enqueuePhoto(
            photoId = request.photoId,
            inspectionClientUuid = draft.clientUuid,
            answerCode = request.answerCode,
            siteId = draft.siteId,
            targetType = "INSPECTION_ANSWER",
            targetId = request.itemId,
            filePath = path,
            mime = "image/jpeg",
            sizeBytes = size,
        )
        mutate { current ->
            current.copy(
                answers = current.answers.map { if (it.itemId == request.itemId) it.copy(photoIds = (it.photoIds + request.photoId).distinct()) else it },
            )
        }
        _state.value = _state.value.copy(capture = null, photoCount = container.store.pendingPhotosForInspection(draft.clientUuid).size)
    }

    private fun copyToPrivateStorage(
        uri: Uri,
        photoId: String,
    ): String? =
        runCatching {
            val directory = java.io.File(container.appContext.filesDir, "inspection_photos").apply { mkdirs() }
            val target = java.io.File(directory, "$photoId.jpg")
            container.appContext.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            target.absolutePath
        }.getOrNull()

    fun sign(
        name: String,
        eid: String,
    ) {
        if (name.isBlank() || eid.isBlank()) {
            _state.value = _state.value.copy(message = "Enter the signer name and employee ID.")
            return
        }
        mutate {
            it.copy(
                operatorName = name,
                operatorEid = eid,
                attestation = AttestationDto(name = name, employeeId = eid, method = "DEVICE_CONFIRMATION"),
            )
        }
    }

    fun submit() {
        val draft = _state.value.draft ?: return
        val withCapturedAt = draft.copy(capturedAt = Instant.now().toString())
        val validation = DraftValidator.validate(withCapturedAt)
        if (!validation.valid) {
            _state.value = _state.value.copy(draft = withCapturedAt, validation = validation, message = "Complete the highlighted requirements before submitting.")
            return
        }
        _state.value = _state.value.copy(submitting = true, message = null)
        viewModelScope.launch {
            val submission = DraftValidator.toSubmission(withCapturedAt)
            container.store.enqueueOutbox(
                clientUuid = withCapturedAt.clientUuid,
                kind = "INSPECTION",
                equipmentId = withCapturedAt.equipmentId,
                payload =
                    LocalStore.json.encodeToString(
                        rw.rutongo.mmems.shared.api.SubmitInspectionDto
                            .serializer(),
                        submission,
                    ),
            )
            container.store.saveDraft(withCapturedAt)
            SyncWorker.enqueueNow(container.appContext)
            _state.value =
                _state.value.copy(
                    draft = withCapturedAt,
                    submitting = false,
                    queued = true,
                    message = "Inspection queued. Photos upload before the inspection and retry automatically.",
                )
        }
    }
}
