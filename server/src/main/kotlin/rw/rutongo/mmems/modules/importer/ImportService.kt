package rw.rutongo.mmems.modules.importer

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.apache.poi.openxml4j.opc.OPCPackage
import org.apache.poi.ss.usermodel.DataFormatter
import org.apache.poi.util.XMLHelper
import org.apache.poi.openxml4j.util.ZipSecureFile
import org.apache.poi.xssf.eventusermodel.XSSFReader
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler
import org.apache.poi.ss.util.CellReference
import org.xml.sax.InputSource
import rw.rutongo.mmems.identity.AuthFailure
import rw.rutongo.mmems.identity.Principal
import rw.rutongo.mmems.shared.enums.Role
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.Connection
import java.sql.SQLException
import java.util.Locale
import java.util.UUID
import javax.sql.DataSource

@Serializable enum class ImportKind { ASSET, TECHNICIAN, OPERATOR }
@Serializable data class ImportProfileView(val id: String, val name: String, val kind: ImportKind, val sheetName: String?, val columnMapping: Map<String,String>, val version: Long)
@Serializable data class ImportBatchView(val id: String, val state: String, val fileSha256: String, val newCount: Int, val updatedCount: Int, val unchangedCount: Int, val rejectedCount: Int, val newEquipmentTypes: List<String> = emptyList())
@Serializable data class ImportRowView(val rowNumber: Int, val businessKey: String?, val state: String, val rejectionReason: String?, val warnings: List<String>)
@Serializable data class ImportRowsPage(val batch: ImportBatchView, val rows: List<ImportRowView>, val nextRowNumber: Int?)
@Serializable private data class PersistedRow(val values: Map<String,String?>, val extra: Map<String,String>, val warnings: List<String>, val siteId: String? = null, val tunnelId: String? = null, val shiftId: String? = null)

class ImportService(private val dataSource: DataSource, storageDirectory: Path = Path.of(System.getenv("IMPORT_STORAGE_DIR") ?: "data/imports")) {
    private val storage = storageDirectory.toAbsolutePath().normalize()
    private val json = Json { ignoreUnknownKeys = false }

    init {
        ZipSecureFile.setMinInflateRatio(0.01)
        ZipSecureFile.setMaxEntrySize(50L * 1024 * 1024)
        ZipSecureFile.setMaxTextSize(20L * 1024 * 1024)
    }

    fun createProfile(actorId: UUID,name: String,kind: ImportKind,sheetName: String?,mapping: Map<String,String>): ImportProfileView {
        require(name.isNotBlank()) { "Profile name is required" }
        require(mapping.isNotEmpty() && mapping.values.all(String::isNotBlank)) { "Column mappings must contain header names" }
        val allowed=allowedFields.getValue(kind)
        require(mapping.keys.all { it in allowed }) { "The mapping contains unsupported fields for $kind" }
        val required=requiredFields.getValue(kind)
        require(mapping.keys.containsAll(required)) { "Required mappings are missing: ${required-mapping.keys}" }
        val id=UUID.randomUUID()
        val mappingJson=buildJsonObject { mapping.toSortedMap().forEach { (key,value)->put(key,value.trim()) } }.toString()
        try {
            dataSource.connection.use { c -> c.prepareStatement("insert into import_profile(id,name,kind,sheet_name,column_mapping,created_by) values (?,?,?,?,?::jsonb,?)").use { ps -> ps.setObject(1,id); ps.setString(2,name.trim()); ps.setString(3,kind.name); ps.setString(4,sheetName?.trim()?.takeIf(String::isNotEmpty)); ps.setString(5,mappingJson); ps.setObject(6,actorId); ps.executeUpdate() } }
        } catch(e: SQLException) { if(e.sqlState=="23505") throw AuthFailure("IMPORT_PROFILE_EXISTS"); throw e }
        return profile(id)!!
    }

    fun profiles(): List<ImportProfileView> = dataSource.connection.use { c ->
        c.prepareStatement("select id from import_profile where active order by name").use { ps -> ps.executeQuery().use { rs -> buildList { while(rs.next()) profile(c,rs.getObject(1,UUID::class.java))?.let(::add) } } }
    }

    fun preview(actor: Principal,profileId: UUID,fileName: String,bytes: ByteArray): ImportBatchView {
        require(fileName.endsWith(".xlsx",ignoreCase=true)) { "Only .xlsx workbooks are accepted" }
        require(bytes.isNotEmpty() && bytes.size<=MAX_FILE_SIZE) { "Workbook must be between 1 byte and 20 MiB" }
        val sha=sha256(bytes)
        val profile=profile(profileId) ?: throw AuthFailure("IMPORT_PROFILE_NOT_FOUND")
        val batchId=UUID.randomUUID()
        Files.createDirectories(storage)
        val filePath=storage.resolve("${batchId}.xlsx").normalize()
        require(filePath.parent==storage) { "Invalid import storage path" }
        Files.write(filePath,bytes)
        try {
            dataSource.connection.use { c -> c.autoCommit=false; try {
                c.prepareStatement("insert into import_batch(id,profile_id,user_id,file_name,file_sha256,storage_key,state) values (?,?,?,?,?,?,'PREVIEWED')").use { ps -> ps.setObject(1,batchId); ps.setObject(2,profileId); ps.setObject(3,actor.id); ps.setString(4,fileName.take(255)); ps.setString(5,sha); ps.setString(6,filePath.fileName.toString()); ps.executeUpdate() }
                val counts=Counts()
                val seenKeys=mutableSetOf<String>()
                streamWorkbook(bytes,profile.sheetName) { rowNumber,headers ->
                    val mapped=mapRow(profile,headers)
                    if(mapped.values.values.all { it.isNullOrBlank() } && mapped.extra.values.all(String::isBlank)) return@streamWorkbook
                    val validation=validate(c,profile.kind,mapped.values)
                    val scopeError=validation.siteId!=null && !hasSiteAccess(c,actor,validation.siteId)
                    val errors=validation.errors+if(scopeError) listOf("The site is outside the importer's authorized scope") else emptyList()
                    val warnings=(mapped.warnings+validation.warnings).distinct()
                    val key=businessKey(profile.kind,mapped.values)
                    val rowHash=if(errors.isEmpty()) normalizedHash(mapped.values,mapped.extra) else null
                    val duplicate=key!=null && key in seenKeys
                    if(key!=null && errors.isEmpty() && !duplicate) seenKeys.add(key)
                    val state=when {
                        duplicate->"REJECTED"
                        errors.isNotEmpty()->"REJECTED"
                        else->classify(c,profile.kind,key!!,rowHash!!)
                    }
                    val reason=(errors+if(duplicate) listOf("Duplicate business key '$key' in this workbook") else emptyList()).takeIf { it.isNotEmpty() }?.joinToString("; ")
                    val persisted=PersistedRow(mapped.values,mapped.extra,warnings,validation.siteId?.toString(),validation.tunnelId?.toString(),validation.shiftId?.toString())
                    c.prepareStatement("insert into import_row(batch_id,row_number,business_key,row_hash,state,data,extra,warnings,rejection_reason) values (?,?,?, ?, ?,?::jsonb,?::jsonb,?::jsonb,?)").use { ps ->
                        ps.setObject(1,batchId); ps.setInt(2,rowNumber+1); ps.setString(3,key); ps.setString(4,rowHash); ps.setString(5,state); ps.setString(6,json.encodeToString(persisted)); ps.setString(7,json.encodeToString(mapped.extra)); ps.setString(8,json.encodeToString(warnings)); ps.setString(9,reason); ps.executeUpdate()
                    }
                    counts.add(state)
                }
                c.prepareStatement("update import_batch set new_count=?,updated_count=?,unchanged_count=?,rejected_count=? where id=?").use { ps -> ps.setInt(1,counts.new); ps.setInt(2,counts.updated); ps.setInt(3,counts.unchanged); ps.setInt(4,counts.rejected); ps.setObject(5,batchId); ps.executeUpdate() }
                audit(c,actor.id,"IMPORT_PREVIEWED",batchId,sha,counts)
                c.commit()
            } catch(e: Exception) { c.rollback(); throw e } }
        } catch(e: Exception) { Files.deleteIfExists(filePath); throw e }
        return batch(batchId)
    }

    fun batch(id: UUID): ImportBatchView = dataSource.connection.use { c -> batch(c,id) ?: throw AuthFailure("IMPORT_NOT_FOUND") }

    fun rows(id: UUID,fromRow: Int=1,limit: Int=100): ImportRowsPage = dataSource.connection.use { c ->
        val batch=batch(c,id) ?: throw AuthFailure("IMPORT_NOT_FOUND")
        val pageSize=limit.coerceIn(1,500)
        val rows=c.prepareStatement("select row_number,business_key,state,rejection_reason,warnings from import_row where batch_id=? and row_number>=? order by row_number limit ?").use { ps ->
            ps.setObject(1,id); ps.setInt(2,fromRow.coerceAtLeast(1)); ps.setInt(3,pageSize+1); ps.executeQuery().use { rs -> buildList { while(rs.next()) add(ImportRowView(rs.getInt(1),rs.getString(2),rs.getString(3),rs.getString(4),json.decodeFromString(rs.getString(5)))) } }
        }
        val hasMore=rows.size>pageSize
        val visible=rows.take(pageSize)
        ImportRowsPage(batch,visible,if(hasMore) visible.lastOrNull()?.rowNumber?.plus(1) else null)
    }

    fun commit(actor: Principal,id: UUID,applyUpdates: Boolean): ImportBatchView = dataSource.connection.use { c -> c.autoCommit=false; try {
        val profileId=c.prepareStatement("select profile_id,state,file_sha256 from import_batch where id=? for update").use { ps -> ps.setObject(1,id); ps.executeQuery().use { rs -> if(rs.next()) Triple(rs.getObject(1,UUID::class.java),rs.getString(2),rs.getString(3)) else throw AuthFailure("IMPORT_NOT_FOUND") } }
        if(profileId.second=="COMMITTED") { c.rollback(); return batch(id) }
        if(profileId.second!="PREVIEWED") throw AuthFailure("IMPORT_NOT_COMMITTABLE")
        val profile=profile(c,profileId.first) ?: throw AuthFailure("IMPORT_PROFILE_NOT_FOUND")
        c.prepareStatement("select row_number,row_hash,state,data from import_row where batch_id=? order by row_number").use { ps ->
            ps.setObject(1,id); ps.executeQuery().use { rs -> while(rs.next()) {
                val state=rs.getString("state")
                if(state=="REJECTED" || state=="UNCHANGED" || (state=="UPDATED" && !applyUpdates)) continue
                val row=json.decodeFromString<PersistedRow>(rs.getString("data"))
                if(row.siteId==null || !hasSiteAccess(c,actor,UUID.fromString(row.siteId))) throw AuthFailure("IMPORT_SITE_NOT_AUTHORIZED")
                val rowHash=rs.getString("row_hash") ?: throw AuthFailure("IMPORT_ROW_HASH_MISSING")
                val currentState=classify(c,profile.kind,businessKey(profile.kind,row.values)!!,rowHash)
                if(currentState=="UNCHANGED") continue
                if(state=="NEW" && currentState=="UPDATED") throw AuthFailure("IMPORT_PREVIEW_STALE")
                if(currentState=="UPDATED" && !applyUpdates) continue
                upsert(c,profile.kind,row,rowHash)
            } }
        }
        c.prepareStatement("update import_batch set state='COMMITTED',apply_updates=?,committed_at=now() where id=?").use { it.setBoolean(1,applyUpdates); it.setObject(2,id); it.executeUpdate() }
        val counts=Counts(batch(c,id)!!)
        audit(c,actor.id,"IMPORT_COMMITTED",id,profileId.third,counts)
        c.commit()
        batch(id)
    } catch(e: Exception) { c.rollback(); throw e } }

    fun rejectionCsv(id: UUID): String = dataSource.connection.use { c ->
        c.prepareStatement("select row_number,business_key,rejection_reason from import_row where batch_id=? and state='REJECTED' order by row_number").use { ps ->
            ps.setObject(1,id); ps.executeQuery().use { rs -> buildString { appendLine("row_number,business_key,rejection_reason"); while(rs.next()) appendLine(listOf(rs.getString(1),rs.getString(2),rs.getString(3)).joinToString(",") { csv(it ?: "") }) } }
        }
    }

    private fun profile(id: UUID): ImportProfileView? = dataSource.connection.use { profile(it,id) }
    private fun profile(c: Connection,id: UUID): ImportProfileView? = c.prepareStatement("select id,name,kind,sheet_name,column_mapping,version from import_profile where id=? and active").use { ps -> ps.setObject(1,id); ps.executeQuery().use { rs -> if(!rs.next()) null else ImportProfileView(rs.getObject(1,UUID::class.java).toString(),rs.getString(2),ImportKind.valueOf(rs.getString(3)),rs.getString(4),json.decodeFromString(rs.getString(5)),rs.getLong(6)) } }
    private fun batch(c: Connection,id: UUID): ImportBatchView? = c.prepareStatement("select id,state,file_sha256,new_count,updated_count,unchanged_count,rejected_count from import_batch where id=?").use { ps -> ps.setObject(1,id); ps.executeQuery().use { rs -> if(!rs.next()) null else {
        val types=c.prepareStatement("select distinct r.data->'values'->>'type' from import_row r join import_batch b on b.id=r.batch_id where r.batch_id=? and r.state='NEW' and r.data->'values'->>'type' is not null and not exists(select 1 from equipment_type et where lower(et.name)=lower(r.data->'values'->>'type')) order by 1").use { ts -> ts.setObject(1,id); ts.executeQuery().use { tr -> buildList { while(tr.next()) add(tr.getString(1)) } } }
        ImportBatchView(rs.getObject(1,UUID::class.java).toString(),rs.getString(2),rs.getString(3),rs.getInt(4),rs.getInt(5),rs.getInt(6),rs.getInt(7),types)
    } } }

    private data class Mapped(val values: Map<String,String?>,val extra: Map<String,String>,val warnings: List<String>)
    private data class Validation(val errors: List<String>,val warnings: List<String>,val siteId: UUID?,val tunnelId: UUID?,val shiftId: UUID?)
    private fun mapRow(profile: ImportProfileView,headers: Map<String,String>): Mapped {
        val normalizedHeaders=headers.keys.associateBy(::normalize)
        val values=profile.columnMapping.mapValues { (_,header) -> normalizedHeaders[normalize(header)]?.let(headers::get)?.trim()?.ifEmpty { null } }
        val used=profile.columnMapping.values.map(::normalize).toSet()
        val extra=headers.filterKeys { normalize(it) !in used }.mapKeys { it.key.trim() }.mapValues { it.value.trim() }
        return Mapped(values,extra,emptyList())
    }

    private fun validate(c: Connection,kind: ImportKind,values: Map<String,String?>): Validation {
        val errors=mutableListOf<String>(); val warnings=mutableListOf<String>()
        requiredFields.getValue(kind).forEach { if(values[it].isNullOrBlank()) errors+= "$it is required" }
        val siteRaw=values["site"]?.let(::normalize)
        val siteName=when(siteRaw) { "sanza"->{warnings+="Site alias 'Sanza' resolved to 'Sanzari'";"sanzari"}; else->siteRaw }
        val siteId=siteName?.let { findSite(c,it) }
        if(siteRaw!=null && siteId==null) errors+="Site '${values["site"]}' does not match an active site"
        val tunnelRaw=values["tunnel"]?.trim()?.takeIf(String::isNotEmpty)
        val tunnelId=if(tunnelRaw!=null && siteId!=null) findTunnel(c,siteId,tunnelRaw) else null
        if(tunnelRaw!=null && tunnelId==null) errors+="Tunnel '$tunnelRaw' does not match an active tunnel at the selected site"
        val shiftRaw=values["shift"]?.trim()?.takeIf(String::isNotEmpty)
        val shiftId=shiftRaw?.let { findShift(c,it) }
        if(shiftRaw!=null && shiftId==null) errors+="Shift '$shiftRaw' is not configured"
        values["manufacture_year"]?.let { raw ->
            val year=raw.toIntOrNull()
            if(year==null) errors+="manufacture_year must be a whole number" else if(year !in 1900..2200) errors+="manufacture_year is outside the supported range"
        }
        values["engine_hours_current"]?.let { raw ->
            val hours=raw.toBigDecimalOrNull()
            if(hours==null) errors+="engine_hours_current must be numeric" else if(hours.signum()<0) errors+="engine_hours_current cannot be negative"
        }
        return Validation(errors,warnings,siteId,tunnelId,shiftId)
    }

    private fun classify(c: Connection,kind: ImportKind,key: String,hash: String): String {
        val table=when(kind) { ImportKind.ASSET->"equipment"; ImportKind.TECHNICIAN->"technician"; ImportKind.OPERATOR->"operator" }
        val column=when(kind) { ImportKind.ASSET->"asset_id"; ImportKind.TECHNICIAN->"eid"; ImportKind.OPERATOR->"employee_id" }
        val existing=c.prepareStatement("select import_row_hash from $table where $column=?").use { ps -> ps.setString(1,key); ps.executeQuery().use { rs -> if(rs.next()) rs.getString(1) else null } }
        return when { existing==null && !exists(c,table,column,key)->"NEW"; existing==hash->"UNCHANGED"; else->"UPDATED" }
    }

    private fun exists(c: Connection,table: String,column: String,key: String)=c.prepareStatement("select exists(select 1 from $table where $column=?)").use { it.setString(1,key); it.executeQuery().use { rs -> rs.next() && rs.getBoolean(1) } }
    private fun businessKey(kind: ImportKind,v: Map<String,String?>)=when(kind) { ImportKind.ASSET->v["asset_id"]; ImportKind.TECHNICIAN->v["eid"]; ImportKind.OPERATOR->v["employee_id"] }?.trim()?.takeIf(String::isNotEmpty)
    private fun findSite(c: Connection,name: String): UUID?=c.prepareStatement("select id from site where state='ACTIVE' and (regexp_replace(lower(trim(name)), '[[:space:]]+', '', 'g')=? or regexp_replace(lower(trim(code)), '[[:space:]]+', '', 'g')=?)").use { it.setString(1,name); it.setString(2,name); it.executeQuery().use { rs -> if(rs.next()) rs.getObject(1,UUID::class.java) else null } }
    private fun findTunnel(c: Connection,site: UUID,name: String): UUID?=c.prepareStatement("select id from tunnel where site_id=? and state='ACTIVE' and (lower(trim(name))=lower(trim(?)) or lower(trim(code))=lower(trim(?)))").use { it.setObject(1,site); it.setString(2,name); it.setString(3,name); it.executeQuery().use { rs -> if(rs.next()) rs.getObject(1,UUID::class.java) else null } }
    private fun findShift(c: Connection,name: String): UUID?=c.prepareStatement("select id from shift where lower(code)=lower(?) or lower(name)=lower(?)").use { it.setString(1,name); it.setString(2,name); it.executeQuery().use { rs -> if(rs.next()) rs.getObject(1,UUID::class.java) else null } }
    private fun hasSiteAccess(c: Connection,actor: Principal,siteId: UUID)=c.prepareStatement("select exists(select 1 from user_site_access where user_id=? and site_id=?) or (? and not exists(select 1 from user_site_access where user_id=?))").use { ps -> ps.setObject(1,actor.id); ps.setObject(2,siteId); ps.setBoolean(3,actor.role in setOf(Role.MANAGER,Role.GENERAL_MANAGER,Role.ADMINISTRATOR)); ps.setObject(4,actor.id); ps.executeQuery().use { rs -> rs.next() && rs.getBoolean(1) } }

    private fun upsert(c: Connection,kind: ImportKind,row: PersistedRow,hash: String?) {
        val v=row.values; val extra=Json.encodeToString(row.extra)
        when(kind) {
            ImportKind.ASSET -> {
                val typeName=v["type"]!!.trim()
                val typeId=c.prepareStatement("insert into equipment_type(name,source) values (?,'IMPORT') on conflict(lower(name)) do update set name=equipment_type.name returning id").use { ps -> ps.setString(1,typeName); ps.executeQuery().use { rs -> rs.next(); rs.getObject(1,UUID::class.java) } }
                c.prepareStatement("""insert into equipment(asset_id,equipment_code,name,description,type_id,model,serial_number,manufacture_year,site_id,tunnel_id,engine_hours_current,remarks,extra,import_row_hash)
                    values (?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?) on conflict(asset_id) do update set equipment_code=excluded.equipment_code,name=excluded.name,description=excluded.description,type_id=excluded.type_id,model=excluded.model,serial_number=excluded.serial_number,manufacture_year=excluded.manufacture_year,site_id=excluded.site_id,tunnel_id=excluded.tunnel_id,engine_hours_current=excluded.engine_hours_current,remarks=excluded.remarks,extra=excluded.extra,import_row_hash=excluded.import_row_hash,updated_at=now(),version=equipment.version+1""".trimIndent()).use { ps ->
                    ps.setString(1,v["asset_id"]); ps.setString(2,v["equipment_code"]); ps.setString(3,v["name"]); ps.setString(4,v["description"]); ps.setObject(5,typeId); ps.setString(6,v["model"]); ps.setString(7,v["serial_number"]); ps.setObject(8,v["manufacture_year"]?.toIntOrNull()); ps.setObject(9,UUID.fromString(row.siteId)); ps.setObject(10,row.tunnelId?.let(UUID::fromString)); ps.setBigDecimal(11,v["engine_hours_current"]?.toBigDecimalOrNull()); ps.setString(12,v["remarks"]); ps.setString(13,extra); ps.setString(14,hash); ps.executeUpdate()
                }
            }
            ImportKind.TECHNICIAN -> c.prepareStatement("""insert into technician(eid,name,function,phone,site_id,tunnel_id,shift_id,extra,import_row_hash) values (?,?,?,?,?,?,?,?::jsonb,?) on conflict(eid) do update set name=excluded.name,function=excluded.function,phone=excluded.phone,site_id=excluded.site_id,tunnel_id=excluded.tunnel_id,shift_id=excluded.shift_id,extra=excluded.extra,import_row_hash=excluded.import_row_hash,updated_at=now(),version=technician.version+1""".trimIndent()).use { ps -> ps.setString(1,v["eid"]); ps.setString(2,v["name"]); ps.setString(3,v["function"]); ps.setString(4,v["phone"]); ps.setObject(5,row.siteId?.let(UUID::fromString)); ps.setObject(6,row.tunnelId?.let(UUID::fromString)); ps.setObject(7,row.shiftId?.let(UUID::fromString)); ps.setString(8,extra); ps.setString(9,hash); ps.executeUpdate() }
            ImportKind.OPERATOR -> c.prepareStatement("""insert into operator(employee_id,name,site_id,tunnel_id,shift_id,extra,import_row_hash) values (?,?,?,?,?,?::jsonb,?) on conflict(employee_id) do update set name=excluded.name,site_id=excluded.site_id,tunnel_id=excluded.tunnel_id,shift_id=excluded.shift_id,extra=excluded.extra,import_row_hash=excluded.import_row_hash,updated_at=now(),version=operator.version+1""".trimIndent()).use { ps -> ps.setString(1,v["employee_id"]); ps.setString(2,v["name"]); ps.setObject(3,row.siteId?.let(UUID::fromString)); ps.setObject(4,row.tunnelId?.let(UUID::fromString)); ps.setObject(5,row.shiftId?.let(UUID::fromString)); ps.setString(6,extra); ps.setString(7,hash); ps.executeUpdate() }
        }
    }

    private fun audit(c: Connection,actor: UUID,action: String,batchId: UUID,sha: String,counts: Counts) {
        val metadata="{\"fileSha256\":\"$sha\",\"new\":${counts.new},\"updated\":${counts.updated},\"unchanged\":${counts.unchanged},\"rejected\":${counts.rejected}}"
        c.prepareStatement("insert into audit_event(actor_id,action,entity_type,entity_id,occurred_at,metadata,event_hash) values (?,?,'import_batch',?,now(),?::jsonb,?)").use { ps -> ps.setObject(1,actor); ps.setString(2,action); ps.setString(3,batchId.toString()); ps.setString(4,metadata); ps.setBytes(5,MessageDigest.getInstance("SHA-256").digest("$action:$batchId:$sha".toByteArray())); ps.executeUpdate() }
    }

    private fun streamWorkbook(bytes: ByteArray,requestedSheet: String?,consume: (Int,Map<String,String>)->Unit) {
        OPCPackage.open(ByteArrayInputStream(bytes)).use { pkg ->
            val reader=XSSFReader(pkg)
            val sheets=reader.sheetsData as XSSFReader.SheetIterator
            var found=false
            while(sheets.hasNext()) {
                val input=sheets.next()
                val sheetName=sheets.sheetName
                if(requestedSheet==null || sheetName.equals(requestedSheet,ignoreCase=true)) {
                    found=true
                    val sharedStrings=reader.sharedStringsTable
                    val header=mutableListOf<String>()
                    var current=linkedMapOf<Int,String>()
                    val contentHandler=object: XSSFSheetXMLHandler.SheetContentsHandler {
                        override fun startRow(rowNum: Int) { current=linkedMapOf() }
                        override fun endRow(rowNum: Int) {
                            if(rowNum==0) {
                                val max=current.keys.maxOrNull() ?: -1
                                for(i in 0..max) header+=current[i]?.trim().orEmpty()
                            } else {
                                val values=linkedMapOf<String,String>()
                                current.forEach { (col,value)-> if(col<header.size && header[col].isNotBlank()) values[header[col]]=value.trim() }
                                consume(rowNum,values)
                            }
                        }
                        override fun cell(cellReference: String,formattedValue: String,comment: org.apache.poi.xssf.usermodel.XSSFComment?) {
                            current[CellReference(cellReference).col.toInt()]=formattedValue
                        }
                        override fun headerFooter(text: String,isHeader: Boolean,tagName: String) { }
                    }
                    val formatter=DataFormatter(Locale.ROOT)
                    val sheetHandler=XSSFSheetXMLHandler(reader.stylesTable,null,sharedStrings,contentHandler,formatter,false)
                    val xml=XMLHelper.newXMLReader(); xml.contentHandler=sheetHandler
                    xml.parse(InputSource(input))
                    input.close()
                    break
                }
                input.close()
            }
            if(!found) throw IllegalArgumentException("The selected sheet '${requestedSheet ?: "first"}' was not found")
        }
    }

    private fun normalizedHash(values: Map<String,String?>,extra: Map<String,String>): String {
        val canonical=(values.toSortedMap().map { (k,v)->"$k=${normalizeValue(v.orEmpty())}" }+extra.toSortedMap().map { (k,v)->"extra.$k=${normalizeValue(v)}" }).joinToString("\n")
        return sha256(canonical.toByteArray(StandardCharsets.UTF_8))
    }
    private fun sha256(bytes: ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun normalize(value: String)=java.text.Normalizer.normalize(value.trim(),java.text.Normalizer.Form.NFKC).lowercase(Locale.ROOT).replace(Regex("\\s+"),"")
    private fun normalizeValue(value: String)=java.text.Normalizer.normalize(value.trim(),java.text.Normalizer.Form.NFKC)
    private fun csv(value: String)="\"${value.replace("\"","\"\"").replace("\n"," ").replace("\r"," ")}\""
    private class Counts(batch: ImportBatchView?=null) {
        var new=batch?.newCount ?: 0
        var updated=batch?.updatedCount ?: 0
        var unchanged=batch?.unchangedCount ?: 0
        var rejected=batch?.rejectedCount ?: 0
        fun add(state:String) { when(state) { "NEW"->new++; "UPDATED"->updated++; "UNCHANGED"->unchanged++; else->rejected++ } }
    }

    companion object {
        const val MAX_FILE_SIZE=20*1024*1024
        private val allowedFields=mapOf(
            ImportKind.ASSET to setOf("asset_id","equipment_code","name","description","type","model","serial_number","manufacture_year","site","tunnel","engine_hours_current","remarks"),
            ImportKind.TECHNICIAN to setOf("eid","name","function","phone","site","tunnel","shift"),
            ImportKind.OPERATOR to setOf("employee_id","name","site","tunnel","shift"),
        )
        private val requiredFields=mapOf(
            ImportKind.ASSET to setOf("asset_id","equipment_code","name","type","site"),
            ImportKind.TECHNICIAN to setOf("eid","name","site"),
            ImportKind.OPERATOR to setOf("employee_id","name","site"),
        )
    }
}
