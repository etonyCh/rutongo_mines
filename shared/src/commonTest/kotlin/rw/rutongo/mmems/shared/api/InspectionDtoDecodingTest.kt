package rw.rutongo.mmems.shared.api

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class InspectionDtoDecodingTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `inspection detail decodes nested server response`() {
        val response =
            """
            {
              "inspection": {
                "id": "inspection-1",
                "clientUuid": "client-1",
                "equipmentId": "equipment-1",
                "siteId": "site-1",
                "shiftId": "shift-1",
                "reportDate": "2026-01-01",
                "inspectorId": "inspector-1",
                "inspectorEid": "EID-1",
                "result": "COMPLETED",
                "submittedLate": false,
                "hoursWarnings": [],
                "capturedAt": "2026-01-01T00:00:00Z",
                "startedAt": "2026-01-01T00:00:00Z",
                "submittedAt": "2026-01-01T00:05:00Z"
              },
              "answers": [
                {
                  "itemId": "item-1",
                  "category": "SAFETY",
                  "label": "Brakes",
                  "answer": "PASS",
                  "photoIds": []
                }
              ],
              "defects": []
            }
            """.trimIndent()

        val detail = json.decodeFromString(InspectionDetailDto.serializer(), response)

        assertEquals("inspection-1", detail.inspection.id)
        assertEquals("COMPLETED", detail.inspection.result)
        assertEquals("PASS", detail.answers.single().answer)
    }
}
