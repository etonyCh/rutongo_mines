@file:Suppress("TooManyFunctions", "LongMethod", "LargeClass", "LongParameterList", "ComplexCondition", "MagicNumber")

package rw.rutongo.mmems.modules.exporter

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import rw.rutongo.mmems.identity.AuthFailure
import rw.rutongo.mmems.identity.Principal
import rw.rutongo.mmems.identity.RolePermissions
import rw.rutongo.mmems.shared.api.ExportDatasetDto
import rw.rutongo.mmems.shared.enums.ExportFormat
import rw.rutongo.mmems.shared.enums.Role
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

data class ExportedFile(
    val fileName: String,
    val contentType: String,
    val bytes: ByteArray,
)

class ExportService(
    private val dataSource: DataSource,
    private val now: () -> Instant = Instant::now,
) {
    private val json = Json { encodeDefaults = true }

    fun datasets(actor: Principal): List<ExportDatasetDto> {
        requireExport(actor)
        return SPECS.values.map { ExportDatasetDto(it.name, listOf("xlsx", "csv"), "EXPORT_DATA") }
    }

    fun export(
        actor: Principal,
        dataset: String,
        format: ExportFormat,
        siteId: UUID?,
        from: Instant?,
        to: Instant?,
    ): ExportedFile {
        requireExport(actor)
        require(from == null || to == null || !to.isBefore(from)) { "The export end must not be before its start." }
        val spec = SPECS[dataset.lowercase()] ?: throw AuthFailure("EXPORT_DATASET_NOT_FOUND")
        val canViewCost = RolePermissions.allows(actor.role, "COST_VIEW")
        val rows =
            dataSource.connection.use { c ->
                if (siteId != null && !hasSiteAccess(c, actor, siteId)) throw AuthFailure("SITE_NOT_AUTHORIZED")
                load(c, spec, actor, siteId, from, to, canViewCost)
            }
        val headers = spec.headers + if (canViewCost) spec.costHeaders else emptyList()
        val bytes =
            when (format) {
                ExportFormat.CSV -> csv(headers, rows)
                ExportFormat.XLSX -> xlsx(spec.name, headers, rows)
            }
        audit(actor, spec.name, format, siteId, from, to, rows.size)
        val extension = format.name.lowercase()
        return ExportedFile(
            fileName = "rutongo-${spec.name}-${now().toString().replace(":", "-")}.$extension",
            contentType =
                if (format == ExportFormat.CSV) {
                    "text/csv; charset=utf-8"
                } else {
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                },
            bytes = bytes,
        )
    }

    private fun load(
        c: Connection,
        spec: DatasetSpec,
        actor: Principal,
        siteId: UUID?,
        from: Instant?,
        to: Instant?,
        canViewCost: Boolean,
    ): List<List<Any?>> {
        val where = mutableListOf<String>()
        val params = mutableListOf<Any>()
        if (spec.siteColumn != null) {
            where +=
                """
                (exists(select 1 from user_site_access a where a.user_id=? and a.site_id=${spec.siteColumn})
                 or (? and not exists(select 1 from user_site_access a where a.user_id=?)))
                """.trimIndent()
            params += actor.id
            params += actor.role in BROAD_ROLES
            params += actor.id
        }
        if (siteId != null && spec.siteColumn != null) {
            where += "${spec.siteColumn}=?"
            params += siteId
        }
        if (from != null && spec.dateColumn != null) {
            where += "${spec.dateColumn}>=?"
            params += Timestamp.from(from)
        }
        if (to != null && spec.dateColumn != null) {
            where += "${spec.dateColumn}<=?"
            params += Timestamp.from(to)
        }
        val select = spec.select + if (canViewCost) spec.costSelect else ""
        val whereSql = if (where.isEmpty()) "" else " where ${where.joinToString(" and ")}"
        val sql = "select $select from ${spec.from}$whereSql order by ${spec.orderBy} limit 10000"
        return c.prepareStatement(sql).use { ps ->
            params.forEachIndexed { index, value -> ps.setObject(index + 1, value) }
            ps.executeQuery().use { rs -> readRows(rs) }
        }
    }

    private fun readRows(rs: ResultSet): List<List<Any?>> {
        val columns = rs.metaData.columnCount
        return buildList {
            while (rs.next()) {
                add((1..columns).map { index -> rs.getObject(index) })
            }
        }
    }

    private fun csv(
        headers: List<String>,
        rows: List<List<Any?>>,
    ): ByteArray {
        val text =
            buildString {
                appendRow(headers)
                rows.forEach { appendRow(it) }
            }
        return text.toByteArray(Charsets.UTF_8)
    }

    private fun StringBuilder.appendRow(values: List<Any?>) {
        values.forEachIndexed { index, value ->
            if (index > 0) append(',')
            append(csvValue(value))
        }
        append("\r\n")
    }

    private fun csvValue(value: Any?): String {
        val text =
            when (value) {
                null -> ""
                is Timestamp -> value.toInstant().toString()
                is java.sql.Date -> value.toLocalDate().toString()
                is java.math.BigDecimal -> value.stripTrailingZeros().toPlainString()
                else -> value.toString()
            }
        val escaped = text.replace("\"", "\"\"")
        return if (text.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"$escaped\"" else escaped
    }

    private fun xlsx(
        name: String,
        headers: List<String>,
        rows: List<List<Any?>>,
    ): ByteArray {
        XSSFWorkbook().use { workbook ->
            val sheet = workbook.createSheet(name.take(31))
            val header = sheet.createRow(0)
            headers.forEachIndexed { index, value -> header.createCell(index).setCellValue(value) }
            rows.forEachIndexed { rowIndex, values ->
                val row = sheet.createRow(rowIndex + 1)
                values.forEachIndexed { columnIndex, value ->
                    val cell = row.createCell(columnIndex)
                    when (value) {
                        null -> cell.setBlank()
                        is Number -> cell.setCellValue(value.toDouble())
                        is Timestamp -> cell.setCellValue(value.toInstant().toString())
                        is java.sql.Date -> cell.setCellValue(value.toLocalDate().toString())
                        else -> cell.setCellValue(value.toString())
                    }
                }
            }
            headers.indices.forEach { sheet.autoSizeColumn(it) }
            return ByteArrayOutputStream().use {
                workbook.write(it)
                it.toByteArray()
            }
        }
    }

    private fun audit(
        actor: Principal,
        dataset: String,
        format: ExportFormat,
        siteId: UUID?,
        from: Instant?,
        to: Instant?,
        rows: Int,
    ) {
        val metadata =
            json.encodeToString(
                mapOf(
                    "dataset" to dataset,
                    "format" to format.name,
                    "rows" to rows.toString(),
                    "siteId" to (siteId?.toString() ?: ""),
                    "from" to (from?.toString() ?: ""),
                    "to" to (to?.toString() ?: ""),
                ),
            )
        dataSource.connection.use { c ->
            c.prepareStatement("insert into audit_event(actor_id,action,entity_type,entity_id,occurred_at,metadata,event_hash) values (?, 'DATA_EXPORTED','export',?,now(),?::jsonb,?)").use { ps ->
                ps.setObject(1, actor.id)
                ps.setString(2, dataset)
                ps.setString(3, metadata)
                ps.setBytes(4, MessageDigest.getInstance("SHA-256").digest("$dataset:DATA_EXPORTED:$metadata".toByteArray()))
                ps.executeUpdate()
            }
        }
    }

    private fun hasSiteAccess(
        c: Connection,
        actor: Principal,
        siteId: UUID,
    ): Boolean =
        c.prepareStatement("select exists(select 1 from user_site_access where user_id=? and site_id=?) or (? and not exists(select 1 from user_site_access where user_id=?))").use { ps ->
            ps.setObject(1, actor.id)
            ps.setObject(2, siteId)
            ps.setBoolean(3, actor.role in BROAD_ROLES)
            ps.setObject(4, actor.id)
            ps.executeQuery().use {
                it.next()
                it.getBoolean(1)
            }
        }

    private fun requireExport(actor: Principal) {
        if (!RolePermissions.allows(actor.role, "EXPORT_DATA")) throw AuthFailure("FORBIDDEN")
    }

    private data class DatasetSpec(
        val name: String,
        val headers: List<String>,
        val select: String,
        val from: String,
        val siteColumn: String?,
        val dateColumn: String?,
        val orderBy: String,
        val costHeaders: List<String> = emptyList(),
        val costSelect: String = "",
    )

    companion object {
        private val BROAD_ROLES = setOf(Role.MANAGER, Role.GENERAL_MANAGER, Role.ADMINISTRATOR)
        private val SPECS =
            listOf(
                DatasetSpec(
                    "equipment",
                    listOf("Site", "Asset ID", "Equipment Code", "Name", "Type", "Tunnel", "Lifecycle", "Status", "Engine Hours", "Updated At"),
                    "s.code,e.asset_id,e.equipment_code,e.name,et.name,t.name,e.lifecycle_state,e.current_status,e.engine_hours_current,e.updated_at",
                    "equipment e join site s on s.id=e.site_id join equipment_type et on et.id=e.type_id left join tunnel t on t.id=e.tunnel_id",
                    "e.site_id",
                    "e.updated_at",
                    "s.code,e.equipment_code",
                ),
                DatasetSpec(
                    "inspections",
                    listOf("Site", "Asset ID", "Equipment", "Shift", "Report Date", "Inspector EID", "Result", "Submitted Late", "Submitted At"),
                    "s.code,e.asset_id,e.equipment_code,sh.code,i.report_date,i.inspector_eid,i.result,i.submitted_late,i.submitted_at",
                    "inspection i join equipment e on e.id=i.equipment_id join site s on s.id=i.site_id join shift sh on sh.id=i.shift_id",
                    "i.site_id",
                    "i.submitted_at",
                    "i.submitted_at desc",
                ),
                DatasetSpec(
                    "defects",
                    listOf("Site", "Asset ID", "Equipment", "Severity", "State", "Description", "Reported At", "Resolved At"),
                    "s.code,e.asset_id,e.equipment_code,d.severity,d.state,d.description,d.reported_at,d.resolved_at",
                    "defect d join equipment e on e.id=d.equipment_id join site s on s.id=e.site_id",
                    "e.site_id",
                    "d.reported_at",
                    "d.reported_at desc",
                ),
                DatasetSpec(
                    "breakdowns",
                    listOf("Site", "Asset ID", "Equipment", "Category", "State", "Problem", "Reported At", "Breakdown Start", "Repair Start", "Repair End", "Downtime Minutes"),
                    "s.code,e.asset_id,e.equipment_code,b.category,b.state,b.problem,b.reported_at,b.breakdown_start,b.repair_start,b.repair_end,case when b.repair_end is null then null else floor(extract(epoch from (b.repair_end-coalesce(b.repair_start,b.breakdown_start)))/60) end",
                    "breakdown b join equipment e on e.id=b.equipment_id join site s on s.id=b.site_id",
                    "b.site_id",
                    "b.reported_at",
                    "b.reported_at desc",
                ),
                DatasetSpec(
                    "maintenance",
                    listOf("Site", "Asset ID", "Equipment", "Service Type", "Work Performed", "Performed At", "Verification", "Engine Hours", "Currency"),
                    "s.code,e.asset_id,e.equipment_code,st.name,m.work_performed,m.performed_at,m.verification_status,m.engine_hours,m.currency",
                    "maintenance_record m join equipment e on e.id=m.equipment_id join site s on s.id=m.site_id join service_type st on st.id=m.service_type_id",
                    "m.site_id",
                    "m.performed_at",
                    "m.performed_at desc",
                    listOf("Labour Cost", "Parts Cost", "Total Cost"),
                    ",m.labour_cost,m.parts_cost,m.total_cost",
                ),
                DatasetSpec(
                    "part-requests",
                    listOf("Site", "Requested At", "State", "Requested By", "Equipment", "Breakdown", "Lines", "Notes"),
                    "s.code,r.requested_at,r.state,u.full_name,e.equipment_code,r.breakdown_id,(select count(*) from part_request_line l where l.request_id=r.id),r.notes",
                    "part_request r join site s on s.id=r.site_id join app_user u on u.id=r.requested_by left join equipment e on e.id=r.equipment_id",
                    "r.site_id",
                    "r.requested_at",
                    "r.requested_at desc",
                ),
                DatasetSpec(
                    "working-hours",
                    listOf("Site", "Asset ID", "Equipment", "Shift", "Report Date", "Hours Start", "Hours End", "Recorded Hours", "Status"),
                    "s.code,e.asset_id,e.equipment_code,sh.code,wh.report_date,wh.hours_start,wh.hours_end,wh.recorded_hours,wh.status",
                    "working_hours_entry wh join equipment e on e.id=wh.equipment_id join site s on s.id=wh.site_id join shift sh on sh.id=wh.shift_id",
                    "wh.site_id",
                    "wh.report_date::timestamp",
                    "wh.report_date desc,e.equipment_code",
                ),
                DatasetSpec(
                    "reports",
                    listOf("Site", "Report Date", "Period", "Version", "State", "Prepared By", "Submitted At", "Approved At", "PDF SHA-256"),
                    "s.code,dr.report_date,dr.period,rv.version_no,rv.state,u.full_name,rv.submitted_at,rv.approved_at,rv.pdf_sha256",
                    "report_version rv join daily_report dr on dr.id=rv.report_id join site s on s.id=dr.site_id join app_user u on u.id=dr.prepared_by",
                    "dr.site_id",
                    "dr.report_date::timestamp",
                    "dr.report_date desc,rv.version_no desc",
                ),
                DatasetSpec(
                    "parts",
                    listOf("Part Number", "Description", "Category", "Unit", "Min Stock", "On Hand", "Low Stock"),
                    "p.part_number,p.description,p.category,p.unit,p.min_stock,coalesce(s.on_hand,0),(coalesce(s.on_hand,0)<p.min_stock)",
                    "part p left join (select part_id,sum(on_hand) on_hand from part_stock group by part_id) s on s.part_id=p.id",
                    null,
                    null,
                    "p.part_number",
                    listOf("Unit Cost"),
                    ",p.unit_cost",
                ),
                DatasetSpec(
                    "stock-movements",
                    listOf("Occurred At", "Part Number", "Location", "Quantity", "Type", "Request ID", "User", "Reason"),
                    "sm.occurred_at,p.part_number,sm.location,sm.qty,sm.type,sm.request_id,u.full_name,sm.reason",
                    "stock_movement sm join part p on p.id=sm.part_id join app_user u on u.id=sm.user_id left join part_request r on r.id=sm.request_id",
                    "r.site_id",
                    "sm.occurred_at",
                    "sm.occurred_at desc",
                ),
            ).associateBy { it.name }
    }
}
