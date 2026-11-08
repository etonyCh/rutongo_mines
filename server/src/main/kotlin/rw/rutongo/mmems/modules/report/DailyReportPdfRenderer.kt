@file:Suppress(
    "TooManyFunctions",
    "LargeClass",
    "LongMethod",
    "LongParameterList",
    "MagicNumber",
    "CyclomaticComplexMethod",
)

package rw.rutongo.mmems.modules.report

import com.lowagie.text.Document
import com.lowagie.text.Element
import com.lowagie.text.Font
import com.lowagie.text.PageSize
import com.lowagie.text.Paragraph
import com.lowagie.text.pdf.BaseFont
import com.lowagie.text.pdf.PdfPCell
import com.lowagie.text.pdf.PdfPTable
import com.lowagie.text.pdf.PdfPageEventHelper
import com.lowagie.text.pdf.PdfTemplate
import com.lowagie.text.pdf.PdfWriter
import rw.rutongo.mmems.shared.api.DailyReportSnapshotDto
import rw.rutongo.mmems.shared.api.ReportBreakdownDto
import rw.rutongo.mmems.shared.api.ReportEquipmentDto
import rw.rutongo.mmems.shared.api.ReportInspectionDto
import rw.rutongo.mmems.shared.api.ReportMaintenanceDto
import rw.rutongo.mmems.shared.api.ReportNarrativeDto
import rw.rutongo.mmems.shared.api.ReportPartLineDto
import rw.rutongo.mmems.shared.enums.EquipmentCondition
import rw.rutongo.mmems.shared.enums.ReportPeriod
import rw.rutongo.mmems.shared.enums.ReportState
import java.awt.Color
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.text.Normalizer
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class DailyReportPdfRenderer(
    private val fontDirectory: Path? = null,
) {
    private val fonts = PdfFonts(fontDirectory)

    fun render(
        snapshot: DailyReportSnapshotDto,
        narrative: ReportNarrativeDto,
        state: ReportState,
        versionNo: Int,
        approvedByName: String? = null,
        approvedAt: String? = null,
    ): ByteArray {
        val output = ByteArrayOutputStream()
        val document = Document(PageSize.A4, 42f, 42f, 58f, 52f)
        val writer = PdfWriter.getInstance(document, output)
        writer.pageEvent = ReportPageEvent(snapshot, state, versionNo, fonts, approvedByName, approvedAt)
        document.addTitle("${snapshot.siteName} daily report ${snapshot.reportDate} ${snapshot.period.name}")
        document.addAuthor("Rutongo Mines")
        document.open()
        addLetterhead(document, snapshot, versionNo)
        addSummary(document, snapshot)
        addEquipment(document, snapshot.equipment)
        addInspections(document, snapshot.inspections)
        addBreakdowns(document, snapshot.breakdowns)
        addMaintenance(document, snapshot.maintenance)
        addParts(document, snapshot.partsRequired)
        addOutstanding(document, snapshot)
        addNarrative(document, narrative)
        addApproval(document, state, approvedByName, approvedAt)
        document.close()
        writer.close()
        return output.toByteArray()
    }

    private fun addLetterhead(
        document: Document,
        snapshot: DailyReportSnapshotDto,
        versionNo: Int,
    ) {
        document.add(paragraph("RUTONGO MINES", 19f, bold = true, spacingAfter = 2f))
        document.add(paragraph("MECHANICAL DEPARTMENT", 10f, spacingAfter = 1f))
        document.add(paragraph("DAILY ASSET STATUS REPORT", 14f, bold = true, spacingAfter = 1f))
        document.add(paragraph("${snapshot.siteName.uppercase()} • DAILY MAINTENANCE & ASSET STATUS", 10f, spacingAfter = 10f))
        val info = PdfPTable(4).apply { widthPercentage = 100f }
        info.addCell(infoCell("Date", snapshot.reportDate, 1f))
        info.addCell(infoCell("Site", snapshot.siteName, 1f))
        info.addCell(infoCell("Period", periodLabel(snapshot.period), 1f))
        info.addCell(infoCell("Version", "${periodLabel(snapshot.period)} / v$versionNo", 1f))
        info.addCell(infoCell("Supervisor / EID", listOfNotNull(snapshot.supervisorName, snapshot.supervisorEid).joinToString(" / ").ifBlank { "Not Recorded" }, 2f))
        info.addCell(infoCell("Technicians", if (snapshot.technicians.isEmpty()) "Not Recorded" else snapshot.technicians.joinToString(", "), 2f))
        info.addCell(infoCell("Data cutoff", formatInstant(snapshot.dataCutoffAt), 1f))
        info.addCell(infoCell("Generation", formatNow(), 1f))
        document.add(info)
        document.add(paragraph("", 4f, spacingAfter = 4f))
    }

    private fun addSummary(
        document: Document,
        snapshot: DailyReportSnapshotDto,
    ) {
        sectionTitle(document, "SUMMARY")
        val summary = snapshot.summary
        val cards = PdfPTable(4).apply { widthPercentage = 100f }
        cards.addCell(card("Operational", summary.operational, Color(0xE4F3E6)))
        cards.addCell(card("Under Repair", summary.underRepair, Color(0xFCE8D6)))
        cards.addCell(card("Down", summary.down, Color(0xF8D7D7)))
        cards.addCell(card("Operational with Defects", summary.operationalWithDefects, Color(0xFFF3CD)))
        document.add(cards)
        document.add(paragraph("", 2f, spacingAfter = 2f))
        val activity = PdfPTable(4).apply { widthPercentage = 100f }
        activity.addCell(infoCell("Inspections completed", summary.inspectionsCompleted.toString(), 1f))
        activity.addCell(infoCell("Inspections missing", summary.inspectionsMissing.toString(), 1f))
        activity.addCell(infoCell("Breakdowns reported", summary.breakdownsReported.toString(), 1f))
        activity.addCell(infoCell("Breakdowns open", summary.breakdownsOpen.toString(), 1f))
        activity.addCell(infoCell("Repairs completed", summary.repairsCompleted.toString(), 1f))
        activity.addCell(infoCell("Repairs in progress", summary.repairsInProgress.toString(), 1f))
        activity.addCell(infoCell("Downtime (minutes)", summary.downtimeMinutes.toString(), 1f))
        activity.addCell(infoCell("Maintenance recorded", summary.maintenanceRecorded.toString(), 1f))
        activity.addCell(infoCell("Maintenance due", summary.maintenanceDue.toString(), 1f))
        activity.addCell(infoCell("Maintenance overdue", summary.maintenanceOverdue.toString(), 1f))
        activity.addCell(infoCell("Parts pending", summary.pendingPartLines.toString(), 1f))
        activity.addCell(infoCell("Not Recorded", summary.notRecorded.toString(), 1f))
        activity.addCell(infoCell("Total equipment", summary.totalEquipment.toString(), 1f))
        document.add(activity)
    }

    private fun addEquipment(
        document: Document,
        equipment: List<ReportEquipmentDto>,
    ) {
        sectionTitle(document, "EQUIPMENT")
        if (equipment.isEmpty()) {
            document.add(paragraph("No equipment recorded for this site.", 9f))
            return
        }
        equipment.forEach { item ->
            val block =
                PdfPTable(1).apply {
                    widthPercentage = 100f
                    setKeepTogether(true)
                    setSpacingBefore(6f)
                }
            val header = PdfPCell()
            header.backgroundColor = statusColor(item.condition)
            header.addElement(paragraph("${item.equipmentCode} • ${item.name}", 10f, bold = true, spacingAfter = 1f))
            header.addElement(paragraph("${item.typeName} • ${conditionLabel(item.condition)} • Asset ${item.assetId}", 8f, spacingAfter = 1f))
            block.addCell(header)
            val details = PdfPCell()
            details.addElement(paragraph("Tunnel: ${item.tunnelName ?: "Not Recorded"}    Shift: ${item.shiftCode ?: "Not Recorded"}", 8f, spacingAfter = 1f))
            details.addElement(paragraph("Operational status: ${item.operationalStatus ?: "Not Recorded"}", 8f, spacingAfter = 1f))
            details.addElement(paragraph("Last inspection: ${inspectionSummary(item)}", 8f, spacingAfter = 1f))
            details.addElement(paragraph("Last maintenance: ${item.lastMaintenanceAt?.let(::formatInstant) ?: "Not Recorded"}", 8f, spacingAfter = 1f))
            item.defects.forEach { defect ->
                details.addElement(paragraph("Defect: ${defect.description} (${defect.severity}, ${defect.state})", 8f, spacingAfter = 1f))
            }
            item.missingInspections.forEach { missing ->
                details.addElement(paragraph("Inspection missing — ${missing.shiftCode}, expected ${formatInstant(missing.windowStart)}–${formatInstant(missing.windowEnd)}", 8f, spacingAfter = 1f))
            }
            item.openBreakdown?.let { breakdown ->
                details.addElement(paragraph("Breakdown open: ${breakdown.category} — ${breakdown.problem} (${breakdown.state})", 8f, spacingAfter = 1f))
            }
            if (item.defects.isEmpty() && item.missingInspections.isEmpty() && item.openBreakdown == null) {
                details.addElement(paragraph("No open defects, missing inspections or breakdowns.", 8f))
            }
            block.addCell(details)
            document.add(block)
        }
    }

    private fun addInspections(
        document: Document,
        inspections: List<ReportInspectionDto>,
    ) {
        sectionTitle(document, "INSPECTIONS")
        if (inspections.isEmpty()) {
            document.add(paragraph("No inspections recorded for this period.", 9f))
            return
        }
        inspections.forEach { inspection ->
            document.add(paragraph("${inspection.equipmentCode} • ${inspection.result} • ${inspection.shiftCode} • inspected by ${inspection.inspectorName ?: "Not Recorded"} (${inspection.inspectorEid}) • ${formatInstant(inspection.submittedAt)}", 8f, spacingAfter = 2f))
        }
    }

    private fun addBreakdowns(
        document: Document,
        breakdowns: List<ReportBreakdownDto>,
    ) {
        sectionTitle(document, "BREAKDOWNS & REPAIRS")
        if (breakdowns.isEmpty()) {
            document.add(paragraph("No breakdown reported.", 9f))
            return
        }
        breakdowns.forEach { breakdown ->
            val lines =
                listOf(
                    "${breakdown.equipmentCode} • ${breakdown.category} • ${breakdown.state}",
                    breakdown.problem,
                    "Reported ${formatInstant(breakdown.reportedAt)} • repair start ${breakdown.repairStart?.let(::formatInstant) ?: "Not Recorded"} • repair end ${breakdown.repairEnd?.let(::formatInstant) ?: "Not Recorded"}",
                    "Downtime ${breakdown.downtimeMinutes ?: "Not Recorded"} minutes • technician ${breakdown.assignedTechnicianName ?: "Not Recorded"}",
                    "Parts required: ${breakdown.partsRequired ?: "Not Recorded"}",
                    "Action taken: ${breakdown.actionTaken ?: "Not Recorded"}",
                )
            document.add(paragraph(lines.joinToString("\n"), 8f, spacingAfter = 5f))
        }
    }

    private fun addMaintenance(
        document: Document,
        maintenance: List<ReportMaintenanceDto>,
    ) {
        sectionTitle(document, "MAINTENANCE")
        if (maintenance.isEmpty()) {
            document.add(paragraph("No maintenance recorded for this period.", 9f))
            return
        }
        maintenance.forEach { record ->
            document.add(paragraph("${record.equipmentCode} • ${record.serviceTypeName} • ${record.verificationStatus} • ${formatInstant(record.performedAt)} • technician ${record.technicianName ?: "Not Recorded"}\n${record.workPerformed}", 8f, spacingAfter = 5f))
        }
    }

    private fun addParts(
        document: Document,
        parts: List<ReportPartLineDto>,
    ) {
        sectionTitle(document, "PARTS REQUIRED")
        if (parts.isEmpty()) {
            document.add(paragraph("No parts required for this period.", 9f))
            return
        }
        parts.forEach { line ->
            document.add(paragraph("${line.equipmentCode ?: "No equipment"} • ${line.partNumber} ${line.description} • ${line.state} • requested ${line.requestedQty} ${line.unit} • issued ${line.issuedQty} • used ${line.usedQty} • returned ${line.returnedQty}", 8f, spacingAfter = 2f))
        }
    }

    private fun addOutstanding(
        document: Document,
        snapshot: DailyReportSnapshotDto,
    ) {
        sectionTitle(document, "OUTSTANDING ITEMS")
        if (snapshot.outstanding.isEmpty()) {
            document.add(paragraph("No outstanding items.", 9f))
            return
        }
        snapshot.outstanding.forEach { document.add(paragraph("• $it", 8f, spacingAfter = 1f)) }
    }

    private fun addNarrative(
        document: Document,
        narrative: ReportNarrativeDto,
    ) {
        sectionTitle(document, "NARRATIVE")
        narrativeField(document, "Executive summary", narrative.executiveSummary)
        narrativeField(document, "Safety observations", narrative.safetyObservations)
        narrativeField(document, "Maintenance", narrative.maintenanceSummary)
        narrativeField(document, "Parts", narrative.partsSummary)
        narrativeField(document, "Recommendations", narrative.recommendations)
        narrativeField(document, "Remarks", narrative.remarks)
    }

    private fun narrativeField(
        document: Document,
        label: String,
        value: String?,
    ) {
        document.add(paragraph("$label: ${value?.trim()?.ifBlank { null } ?: "Not Recorded"}", 9f, spacingAfter = 3f))
    }

    private fun addApproval(
        document: Document,
        state: ReportState,
        approvedByName: String?,
        approvedAt: String?,
    ) {
        sectionTitle(document, "APPROVAL")
        val approved = state == ReportState.APPROVED || state == ReportState.SUPERSEDED
        val text =
            when (state) {
                ReportState.APPROVED, ReportState.SUPERSEDED ->
                    "APPROVED by ${approvedByName ?: "Not Recorded"} on ${approvedAt?.let(::formatInstant) ?: "Not Recorded"}"
                ReportState.SUBMITTED, ReportState.RESUBMITTED -> "SUBMITTED — awaiting approval"
                else -> "DRAFT — not approved"
            }
        val table =
            PdfPTable(1).apply {
                widthPercentage = 100f
                setKeepTogether(true)
            }
        val cell = PdfPCell(paragraph(text, 10f, bold = true))
        cell.backgroundColor = if (approved) Color(0xE4F3E6) else Color(0xFFF3CD)
        table.addCell(cell)
        document.add(table)
    }

    private fun sectionTitle(
        document: Document,
        title: String,
    ) {
        document.add(paragraph(title, 11f, bold = true, spacingBefore = 10f, spacingAfter = 4f))
    }

    private fun paragraph(
        text: String,
        size: Float,
        bold: Boolean = false,
        spacingBefore: Float = 0f,
        spacingAfter: Float = 2f,
    ): Paragraph =
        Paragraph(text, fonts.font(size, bold)).apply {
            this.spacingBefore = spacingBefore
            this.spacingAfter = spacingAfter
            leading = size + 3f
        }

    private fun infoCell(
        label: String,
        value: String,
        span: Float,
    ): PdfPCell {
        val cell = PdfPCell()
        cell.addElement(paragraph(label.uppercase(), 7f, spacingAfter = 0f))
        cell.addElement(paragraph(value, 9f, bold = true, spacingAfter = 0f))
        cell.setColspan(span.toInt())
        cell.setPadding(4f)
        cell.borderColor = Color(0xD9D9D9)
        return cell
    }

    private fun card(
        label: String,
        value: Int,
        color: Color,
    ): PdfPCell {
        val cell = PdfPCell()
        cell.backgroundColor = color
        cell.addElement(paragraph(value.toString(), 18f, bold = true, spacingAfter = 0f))
        cell.addElement(paragraph(label, 8f, spacingAfter = 0f))
        cell.setPadding(6f)
        cell.borderColor = Color.WHITE
        return cell
    }

    private fun statusColor(condition: EquipmentCondition): Color =
        when (condition) {
            EquipmentCondition.DOWN -> Color(0xF8D7D7)
            EquipmentCondition.UNDER_REPAIR -> Color(0xFCE8D6)
            EquipmentCondition.OPERATIONAL_WITH_DEFECTS -> Color(0xFFF3CD)
            EquipmentCondition.OPERATIONAL -> Color(0xE4F3E6)
            EquipmentCondition.NOT_RECORDED -> Color(0xEDEDED)
        }

    private fun conditionLabel(condition: EquipmentCondition): String = condition.name.replace('_', ' ')

    private fun inspectionSummary(item: ReportEquipmentDto): String {
        val at = item.lastInspectionAt?.let(::formatInstant) ?: return "Not Recorded"
        val result = item.lastInspectionResult ?: "Not Recorded"
        return "Confirmed $result (inspected $at)"
    }

    private fun periodLabel(period: ReportPeriod): String = if (period == ReportPeriod.FULL_DAY) "FULL DAY" else period.name

    private fun formatInstant(value: String): String = runCatching { Instant.parse(value).atZone(ZoneId.of("Africa/Kigali")).format(DATE_TIME) }.getOrDefault(value)

    private fun formatNow(): String = Instant.now().atZone(ZoneId.of("Africa/Kigali")).format(DATE_TIME)

    companion object {
        private val DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

        fun fileName(
            site: String,
            date: LocalDate,
            period: ReportPeriod,
        ): String {
            fun clean(s: String) =
                Normalizer
                    .normalize(s, Normalizer.Form.NFD)
                    .replace(Regex("[^A-Za-z0-9]+"), "_")
                    .trim('_')
                    .uppercase()
            val shift = if (period == ReportPeriod.FULL_DAY) "FULL_DAY" else period.name
            return "RUTONGO_MINES_DAILY_REPORT_${clean(site)}_${date}_$shift.pdf"
        }
    }
}

private class PdfFonts(
    fontDirectory: Path?,
) {
    private val regularBase = load("/fonts/DejaVuSans.ttf", fontDirectory?.resolve("DejaVuSans.ttf"))
    private val boldBase = load("/fonts/DejaVuSans-Bold.ttf", fontDirectory?.resolve("DejaVuSans-Bold.ttf"))

    fun font(
        size: Float,
        bold: Boolean,
    ): Font = Font(if (bold) boldBase else regularBase, size, if (bold) Font.BOLD else Font.NORMAL, Color(0x1F1F1F))

    fun footer(): Font = Font(regularBase, 8f, Font.NORMAL, Color(0x555555))

    fun watermark(size: Float): Font = Font(boldBase, size, Font.BOLD, Color(0x9A9A9A))

    private fun load(
        resource: String,
        override: Path?,
    ): BaseFont {
        val bytes =
            override
                ?.takeIf {
                    java.nio.file.Files
                        .exists(it)
                }?.let {
                    java.nio.file.Files
                        .readAllBytes(it)
                }
                ?: PdfFonts::class.java.getResourceAsStream(resource)?.use { it.readBytes() }
                ?: error("Missing report font $resource")
        return BaseFont.createFont(resource, BaseFont.IDENTITY_H, BaseFont.EMBEDDED, BaseFont.CACHED, bytes, null)
    }
}

private class ReportPageEvent(
    private val snapshot: DailyReportSnapshotDto,
    private val state: ReportState,
    private val versionNo: Int,
    private val fonts: PdfFonts,
    private val approvedByName: String?,
    private val approvedAt: String?,
) : PdfPageEventHelper() {
    private lateinit var totalPages: PdfTemplate

    override fun onOpenDocument(
        writer: PdfWriter,
        document: Document,
    ) {
        totalPages = writer.directContent.createTemplate(40f, 18f)
    }

    override fun onEndPage(
        writer: PdfWriter,
        document: Document,
    ) {
        val canvas = writer.directContent
        val watermark =
            when (state) {
                ReportState.DRAFT, ReportState.RETURNED_FOR_CORRECTION -> "DRAFT"
                ReportState.APPROVED, ReportState.SUPERSEDED -> "APPROVED"
                else -> null
            }
        if (watermark != null) {
            canvas.saveState()
            canvas.setGState(
                com.lowagie.text.pdf
                    .PdfGState()
                    .apply { setFillOpacity(0.12f) },
            )
            canvas.beginText()
            canvas.setFontAndSize(fonts.watermark(52f).baseFont, 52f)
            canvas.showTextAligned(Element.ALIGN_CENTER, watermark, PageSize.A4.width / 2f, PageSize.A4.height / 2f, 45f)
            canvas.endText()
            canvas.restoreState()
        }
        val footerY = 28f
        canvas.beginText()
        canvas.setFontAndSize(fonts.footer().baseFont, 8f)
        canvas.setColorFill(Color(0x555555))
        canvas.showTextAligned(Element.ALIGN_LEFT, "${snapshot.siteName} • ${snapshot.reportDate} • ${periodLabel(snapshot.period)} • v$versionNo", 42f, footerY, 0f)
        canvas.showTextAligned(Element.ALIGN_RIGHT, "Page ${writer.pageNumber} / ", PageSize.A4.width - 78f, footerY, 0f)
        canvas.endText()
        canvas.addTemplate(totalPages, PageSize.A4.width - 76f, footerY)
        if (state == ReportState.APPROVED || state == ReportState.SUPERSEDED) {
            canvas.beginText()
            canvas.setFontAndSize(fonts.footer().baseFont, 8f)
            canvas.showTextAligned(Element.ALIGN_RIGHT, "APPROVED ${approvedByName ?: ""} ${approvedAt ?: ""}".trim(), PageSize.A4.width - 42f, footerY + 12f, 0f)
            canvas.endText()
        }
    }

    override fun onCloseDocument(
        writer: PdfWriter,
        document: Document,
    ) {
        totalPages.beginText()
        totalPages.setFontAndSize(fonts.footer().baseFont, 8f)
        totalPages.setColorFill(Color(0x555555))
        totalPages.showText(writer.pageNumber.toString())
        totalPages.endText()
    }

    private fun periodLabel(period: ReportPeriod): String = if (period == ReportPeriod.FULL_DAY) "FULL DAY" else period.name
}
