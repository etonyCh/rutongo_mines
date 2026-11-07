package rw.rutongo.mmems.android

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import rw.rutongo.mmems.android.ui.MainScaffold
import rw.rutongo.mmems.android.ui.theme.MmemsTheme
import rw.rutongo.mmems.shared.api.ChecklistItemDto
import rw.rutongo.mmems.shared.api.EquipmentDto
import rw.rutongo.mmems.shared.api.ExpectedInspectionDto
import rw.rutongo.mmems.shared.api.MeView
import rw.rutongo.mmems.shared.api.SiteDto
import rw.rutongo.mmems.shared.enums.Role

class OfflineWorkflowTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var container: AppContainer
    private val siteId = "11111111-1111-1111-1111-111111111111"
    private val equipmentId = "22222222-2222-2222-2222-222222222222"
    private val expectedId = "33333333-3333-3333-3333-333333333333"
    private val me =
        MeView(
            id = "44444444-4444-4444-4444-444444444444",
            username = "mechanic",
            fullName = "Aline Uwase",
            employeeId = "EMP-42",
            role = Role.MECHANIC,
            siteIds = listOf(siteId),
        )

    @Before
    fun setUp() {
        container = ApplicationProvider.getApplicationContext<MmemsApplication>().container
        container.store.purgeLocalData()
        container.store.replaceReferences(
            sites = listOf(SiteDto(siteId, "SITE-A", "Kigali site", "ACTIVE", "Africa/Kigali", 1)),
            equipment =
                listOf(
                    EquipmentDto(
                        id = equipmentId,
                        assetId = "AST-1",
                        equipmentCode = "EQ-01",
                        name = "LHD 01",
                        type = "LHD",
                        siteId = siteId,
                        siteName = "Kigali site",
                        lifecycleState = "ACTIVE",
                        operationalStatus = "OPERATIONAL",
                        engineHours = 100.0,
                        version = 3,
                    ),
                ),
            checklist =
                listOf(
                    ChecklistItemDto("check-1", "SAFETY", "BRAKE", "Brake test", 1, true, false, false, false, true),
                    ChecklistItemDto("check-2", "MECHANICAL", "HYDRAULIC", "Hydraulic inspection", 2, true, false, false, false, true),
                ),
            shifts = emptyList(),
            expected =
                listOf(
                    ExpectedInspectionDto(
                        id = expectedId,
                        equipmentId = equipmentId,
                        equipmentName = "LHD 01",
                        equipmentCode = "EQ-01",
                        siteId = siteId,
                        siteName = "Kigali site",
                        shiftId = "shift-day",
                        shiftCode = "DAY",
                        reportDate = "2026-01-01",
                        windowStart = "2026-01-01T06:00:00Z",
                        windowEnd = "2026-01-01T18:00:00Z",
                        state = "OPEN",
                    ),
                ),
            syncedAt = "2026-01-01T05:00:00Z",
        )
    }

    @Test
    fun mechanicCompletesOfflineInspectionAndQueuesIt() {
        compose.setContent { MmemsTheme { MainScaffold(container, me, onLogout = {}) } }
        compose.onNodeWithText("Inspections").performClick()
        compose.waitForText("START")
        compose.onNodeWithText("START").performClick()
        compose.onNodeWithText("NEXT").performClick()
        compose.onNodeWithText("NEXT").performClick()
        compose.onNodeWithText("PASS").performClick()
        compose.onNodeWithText("NEXT").performClick()
        compose.onNodeWithText("PASS").performClick()
        compose.onNodeWithText("NEXT").performClick()
        compose.onNodeWithText("NEXT").performClick()
        compose.onNodeWithText("NEXT").performClick()
        compose.onAllNodes(hasSetTextAction())[0].performTextReplacement("102")
        compose.onNodeWithText("NEXT").performClick()
        compose.onAllNodesWithText("Review").onFirst().assertIsDisplayed()
        compose.onNodeWithText("NEXT").performClick()
        compose.onNodeWithText("Signer name").assertIsDisplayed()
        compose.onNodeWithText("SIGN AND SUBMIT").performClick()
        compose.waitForText("Inspection queued")
        compose.onNodeWithText("CONTINUE").performClick()
        compose.waitForText("WAITING TO SYNC")

        val inspection = container.store.pendingOutbox().firstOrNull { it.kind == "INSPECTION" }
        assertNotNull(inspection)
        assertTrue(inspection!!.state in setOf("PENDING", "RETRY"))
        assertEquals(equipmentId, inspection.equipment_id)
        assertNotNull(container.store.draft(inspection.client_uuid))
    }

    @Test
    fun mechanicQueuesStatusUpdateWhileOffline() {
        compose.setContent { MmemsTheme { MainScaffold(container, me, onLogout = {}) } }
        compose.onNodeWithText("Equipment").performClick()
        compose.waitForText("LHD 01")
        compose.onNodeWithText("LHD 01").performClick()
        compose.waitForText("Record status")
        compose.onNodeWithText("DOWN").performClick()
        compose.onNodeWithText("DOWN").assertIsSelected()
        compose.waitForText("Problem (required)")
        compose.onAllNodes(hasSetTextAction())[0].performTextReplacement("Hydraulic leak")
        compose.waitForIdle()
        compose.onNodeWithText("QUEUE STATUS UPDATE").performScrollTo().performClick()
        compose.waitUntil(10_000) { container.store.pendingOutbox().any { it.kind == "STATUS" } }

        val status = container.store.pendingOutbox().firstOrNull { it.kind == "STATUS" }
        assertNotNull(status)
        assertEquals(equipmentId, status!!.equipment_id)
        assertEquals(3L, status.if_match)
        assertTrue(status.payload.contains("\"status\":\"DOWN\""))
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.waitForText(text: String) {
        waitUntil(10_000) { onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }
}
