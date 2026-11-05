package rw.rutongo.mmems.modules.equipment

import rw.rutongo.mmems.identity.AuthFailure
import rw.rutongo.mmems.identity.Principal
import rw.rutongo.mmems.identity.RolePermissions
import rw.rutongo.mmems.shared.enums.Role
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.util.UUID
import javax.sql.DataSource

@Serializable data class EquipmentSummary(
    val id: String,
    val assetId: String,
    val equipmentCode: String,
    val name: String,
    val type: String,
    val siteId: String,
    val siteName: String,
    val tunnelId: String?,
    val tunnelName: String?,
    val lifecycleState: String,
    val operationalStatus: String,
    val engineHours: Double?,
    val version: Long,
)

@Serializable data class SiteView(val id: String, val code: String, val name: String, val state: String, val timezone: String,val version: Long)
@Serializable data class TunnelView(val id: String, val siteId: String, val code: String, val name: String, val state: String,val version: Long)
@Serializable data class ShiftView(val id: String, val code: String, val name: String,val version: Long)
@Serializable data class EquipmentTypeView(val id: String,val name: String,val source: String,val active: Boolean)
@Serializable data class ShiftScheduleView(val id: String,val shiftId: String,val shiftCode: String,val siteId: String?,val startLocal: String,val endLocal: String,val inspectionWindowMinutes: Int,val hardBackstopHours: Int,val effectiveFrom: String,val effectiveTo: String?)
@Serializable data class PersonnelView(val id: String,val employeeId: String,val name: String,val siteId: String?,val tunnelId: String?,val shiftId: String?,val active: Boolean)

class EquipmentService(private val dataSource: DataSource) {
    fun sites(user: Principal): List<SiteView> = dataSource.connection.use { c ->
        c.prepareStatement("""
            select s.id,s.code,s.name,s.state,s.timezone,s.version from site s
            where s.state <> 'RETIRED'
              and (exists(select 1 from user_site_access a where a.user_id=? and a.site_id=s.id)
                or (? and not exists(select 1 from user_site_access a where a.user_id=?)))
            order by s.name
        """.trimIndent()).use { ps ->
            ps.setObject(1,user.id); ps.setBoolean(2,user.role in broadRoles); ps.setObject(3,user.id)
            ps.executeQuery().use { rs -> buildList { while(rs.next()) add(SiteView(rs.getObject("id",UUID::class.java).toString(),rs.getString("code"),rs.getString("name"),rs.getString("state"),rs.getString("timezone"),rs.getLong("version"))) } }
        }
    }

    fun tunnels(user: Principal, siteId: UUID? = null): List<TunnelView> = dataSource.connection.use { c ->
        c.prepareStatement("""
            select t.id,t.site_id,t.code,t.name,t.state,t.version from tunnel t join site s on s.id=t.site_id
            where s.state='ACTIVE' and (?::uuid is null or t.site_id=?)
              and (exists(select 1 from user_site_access a where a.user_id=? and a.site_id=t.site_id)
                or (? and not exists(select 1 from user_site_access a where a.user_id=?)))
            order by s.name,t.name
        """.trimIndent()).use { ps ->
            ps.setObject(1,siteId); ps.setObject(2,siteId); ps.setObject(3,user.id); ps.setBoolean(4,user.role in broadRoles); ps.setObject(5,user.id)
            ps.executeQuery().use { rs -> buildList { while(rs.next()) add(TunnelView(rs.getObject("id",UUID::class.java).toString(),rs.getObject("site_id",UUID::class.java).toString(),rs.getString("code"),rs.getString("name"),rs.getString("state"),rs.getLong("version"))) } }
        }
    }

    fun shifts(): List<ShiftView> = dataSource.connection.use { c ->
        c.prepareStatement("select id,code,name,version from shift order by code").use { ps -> ps.executeQuery().use { rs -> buildList { while(rs.next()) add(ShiftView(rs.getObject("id",UUID::class.java).toString(),rs.getString("code"),rs.getString("name"),rs.getLong("version"))) } } }
    }

    fun schedules(user: Principal): List<ShiftScheduleView> = dataSource.connection.use { c ->
        c.prepareStatement("""
            select ss.id,ss.shift_id,s.code shift_code,ss.site_id,ss.start_local,ss.end_local,
              ss.inspection_window_minutes,ss.hard_backstop_hours,ss.effective_from,ss.effective_to
            from shift_schedule ss join shift s on s.id=ss.shift_id
            where ss.site_id is null
              or exists(select 1 from user_site_access a where a.user_id=? and a.site_id=ss.site_id)
              or (? and not exists(select 1 from user_site_access a where a.user_id=?))
            order by ss.site_id nulls first,s.code,ss.effective_from
        """.trimIndent()).use { ps ->
            ps.setObject(1,user.id); ps.setBoolean(2,user.role in broadRoles); ps.setObject(3,user.id)
            ps.executeQuery().use { rs -> buildList { while(rs.next()) add(ShiftScheduleView(rs.getObject("id",UUID::class.java).toString(),rs.getObject("shift_id",UUID::class.java).toString(),rs.getString("shift_code"),rs.getObject("site_id",UUID::class.java)?.toString(),rs.getString("start_local"),rs.getString("end_local"),rs.getInt("inspection_window_minutes"),rs.getInt("hard_backstop_hours"),rs.getString("effective_from"),rs.getString("effective_to"))) } }
        }
    }

    fun createSite(actor: UUID,code: String,name: String,timezone: String): SiteView {
        require(code.isNotBlank() && name.isNotBlank()) { "Site code and name are required" }
        ZoneId.of(timezone)
        val id=UUID.randomUUID()
        dataSource.connection.use { c -> c.autoCommit=false; try {
            c.prepareStatement("insert into site(id,code,name,state,timezone) values (?,?,?,'ACTIVE',?)").use { ps -> ps.setObject(1,id); ps.setString(2,code.trim().uppercase()); ps.setString(3,name.trim()); ps.setString(4,timezone); ps.executeUpdate() }
            c.prepareStatement("insert into user_site_access(user_id,site_id) select ?,? where exists(select 1 from user_site_access where user_id=?)").use { ps -> ps.setObject(1,actor); ps.setObject(2,id); ps.setObject(3,actor); ps.executeUpdate() }
            writeAudit(c,actor,"SITE_CREATED","site",id,"{}")
            c.commit()
        } catch(e: SQLException) { c.rollback(); if(e.sqlState=="23505") throw AuthFailure("SITE_ALREADY_EXISTS"); throw e } catch(e: Exception) { c.rollback(); throw e } }
        return dataSource.connection.use { c -> c.prepareStatement("select id,code,name,state,timezone,version from site where id=?").use { it.setObject(1,id); it.executeQuery().use { rs -> rs.next(); SiteView(rs.getObject(1,UUID::class.java).toString(),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getLong(6)) } } }
    }

    fun updateSite(actor: Principal,id: UUID,expectedVersion: Long,name: String,timezone: String,state: String): SiteView {
        require(name.isNotBlank()) { "Site name is required" }
        require(state in setOf("ACTIVE","INACTIVE","DISABLED","RETIRED")) { "Site state is invalid" }
        ZoneId.of(timezone)
        dataSource.connection.use { c -> c.autoCommit=false; try {
            if(!hasSiteAccess(c,actor,id)) throw AuthFailure("SITE_NOT_AUTHORIZED")
            c.prepareStatement("update site set name=?,timezone=?,state=?,updated_at=now(),version=version+1 where id=? and version=?").use { ps -> ps.setString(1,name.trim()); ps.setString(2,timezone); ps.setString(3,state); ps.setObject(4,id); ps.setLong(5,expectedVersion); if(ps.executeUpdate()!=1) throw AuthFailure("SITE_VERSION_CONFLICT") }
            writeAudit(c,actor.id,"SITE_UPDATED","site",id,"{\"state\":\"$state\"}")
            c.commit()
        } catch(e: Exception) { c.rollback(); throw e } }
        return dataSource.connection.use { c -> c.prepareStatement("select id,code,name,state,timezone,version from site where id=?").use { ps -> ps.setObject(1,id); ps.executeQuery().use { rs -> if(rs.next()) SiteView(rs.getObject(1,UUID::class.java).toString(),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getLong(6)) else throw AuthFailure("SITE_NOT_FOUND") } } }
    }

    fun createTunnel(actor: Principal,siteId: UUID,code: String,name: String): TunnelView {
        require(code.isNotBlank() && name.isNotBlank()) { "Tunnel code and name are required" }
        val id=UUID.randomUUID()
        dataSource.connection.use { c -> c.autoCommit=false; try {
            if(!activeSite(c,siteId)) throw AuthFailure("SITE_NOT_FOUND")
            if(!hasSiteAccess(c,actor,siteId)) throw AuthFailure("SITE_NOT_AUTHORIZED")
            c.prepareStatement("insert into tunnel(id,site_id,code,name,state) values (?,?,?,?,'ACTIVE')").use { ps -> ps.setObject(1,id); ps.setObject(2,siteId); ps.setString(3,code.trim().uppercase()); ps.setString(4,name.trim()); ps.executeUpdate() }
            writeAudit(c,actor.id,"TUNNEL_CREATED","tunnel",id,"{\"siteId\":\"$siteId\"}")
            c.commit()
        } catch(e: SQLException) { c.rollback(); if(e.sqlState=="23505") throw AuthFailure("TUNNEL_ALREADY_EXISTS"); throw e } catch(e: Exception) { c.rollback(); throw e } }
        return TunnelView(id.toString(),siteId.toString(),code.trim().uppercase(),name.trim(),"ACTIVE",0)
    }

    fun updateTunnel(actor: Principal,id: UUID,expectedVersion: Long,code: String,name: String,state: String): TunnelView {
        require(code.isNotBlank() && name.isNotBlank()) { "Tunnel code and name are required" }
        require(state in setOf("ACTIVE","INACTIVE","DISABLED","RETIRED")) { "Tunnel state is invalid" }
        dataSource.connection.use { c -> c.autoCommit=false; try {
            val current=c.prepareStatement("select site_id from tunnel where id=?").use { ps -> ps.setObject(1,id); ps.executeQuery().use { rs -> if(rs.next()) rs.getObject(1,UUID::class.java) else throw AuthFailure("TUNNEL_NOT_FOUND") } }
            if(!hasSiteAccess(c,actor,current)) throw AuthFailure("SITE_NOT_AUTHORIZED")
            c.prepareStatement("update tunnel set code=?,name=?,state=?,version=version+1 where id=? and version=?").use { ps -> ps.setString(1,code.trim().uppercase()); ps.setString(2,name.trim()); ps.setString(3,state); ps.setObject(4,id); ps.setLong(5,expectedVersion); if(ps.executeUpdate()!=1) throw AuthFailure("TUNNEL_VERSION_CONFLICT") }
            writeAudit(c,actor.id,"TUNNEL_UPDATED","tunnel",id,"{\"state\":\"$state\"}")
            c.commit()
        } catch(e: SQLException) { c.rollback(); if(e.sqlState=="23505") throw AuthFailure("TUNNEL_ALREADY_EXISTS"); throw e } catch(e: Exception) { c.rollback(); throw e } }
        return dataSource.connection.use { c -> c.prepareStatement("select site_id,code,name,state,version from tunnel where id=?").use { ps -> ps.setObject(1,id); ps.executeQuery().use { rs -> if(rs.next()) TunnelView(id.toString(),rs.getObject(1,UUID::class.java).toString(),rs.getString(2),rs.getString(3),rs.getString(4),rs.getLong(5)) else throw AuthFailure("TUNNEL_NOT_FOUND") } } }
    }

    fun updateShift(actor: UUID,code: String,expectedVersion: Long,name: String): ShiftView {
        require(name.isNotBlank()) { "Shift name is required" }
        dataSource.connection.use { c -> c.autoCommit=false; try {
            c.prepareStatement("update shift set name=?,version=version+1 where code=? and version=?").use { ps -> ps.setString(1,name.trim()); ps.setString(2,code.uppercase()); ps.setLong(3,expectedVersion); if(ps.executeUpdate()!=1) throw AuthFailure("SHIFT_VERSION_CONFLICT") }
            val id=c.prepareStatement("select id from shift where code=?").use { ps -> ps.setString(1,code.uppercase()); ps.executeQuery().use { rs -> rs.next(); rs.getObject(1,UUID::class.java) } }
            writeAudit(c,actor,"SHIFT_UPDATED","shift",id,"{}")
            c.commit()
        } catch(e: Exception) { c.rollback(); throw e } }
        return shifts().firstOrNull { it.code==code.uppercase() } ?: throw AuthFailure("SHIFT_NOT_FOUND")
    }

    fun createSchedule(actor: Principal,shiftCode: String,siteId: UUID?,start: LocalTime,end: LocalTime,windowMinutes: Int,backstopHours: Int,effectiveFrom: LocalDate,effectiveTo: LocalDate?): ShiftScheduleView {
        require(windowMinutes in 1..1440 && backstopHours in 1..168) { "Shift window and backstop are outside supported limits" }
        require(effectiveTo==null || !effectiveTo.isBefore(effectiveFrom)) { "effectiveTo must not precede effectiveFrom" }
        val id=UUID.randomUUID()
        dataSource.connection.use { c -> c.autoCommit=false; try {
            val shiftId=c.prepareStatement("select id from shift where code=?").use { ps -> ps.setString(1,shiftCode.uppercase()); ps.executeQuery().use { rs -> if(rs.next()) rs.getObject(1,UUID::class.java) else throw AuthFailure("SHIFT_NOT_FOUND") } }
            if(siteId!=null && !activeSite(c,siteId)) throw AuthFailure("SITE_NOT_FOUND")
            if(siteId!=null && !hasSiteAccess(c,actor,siteId)) throw AuthFailure("SITE_NOT_AUTHORIZED")
            c.prepareStatement("insert into shift_schedule(id,shift_id,site_id,start_local,end_local,inspection_window_minutes,hard_backstop_hours,effective_from,effective_to) values (?,?,?,?,?,?,?,?,?)").use { ps -> ps.setObject(1,id); ps.setObject(2,shiftId); ps.setObject(3,siteId); ps.setObject(4,start); ps.setObject(5,end); ps.setInt(6,windowMinutes); ps.setInt(7,backstopHours); ps.setObject(8,effectiveFrom); ps.setObject(9,effectiveTo); ps.executeUpdate() }
            writeAudit(c,actor.id,"SHIFT_SCHEDULE_CREATED","shift_schedule",id,"{\"shift\":\"$shiftCode\",\"siteId\":${siteId?.let { "\"$it\"" } ?: "null"}}")
            c.commit()
            return ShiftScheduleView(id.toString(),shiftId.toString(),shiftCode.uppercase(),siteId?.toString(),start.toString(),end.toString(),windowMinutes,backstopHours,effectiveFrom.toString(),effectiveTo?.toString())
        } catch(e: Exception) { c.rollback(); throw e } }
    }

    fun equipmentTypes(): List<EquipmentTypeView> = dataSource.connection.use { c -> c.prepareStatement("select id,name,source,active from equipment_type order by name").use { ps -> ps.executeQuery().use { rs -> buildList { while(rs.next()) add(EquipmentTypeView(rs.getObject(1,UUID::class.java).toString(),rs.getString(2),rs.getString(3),rs.getBoolean(4))) } } } }
    fun createEquipmentType(actor: Principal,name: String): EquipmentTypeView {
        require(name.isNotBlank()) { "Equipment type name is required" }
        val id=UUID.randomUUID()
        try { dataSource.connection.use { c -> c.autoCommit=false; try { c.prepareStatement("insert into equipment_type(id,name,source) values (?,?,'MANUAL')").use { it.setObject(1,id); it.setString(2,name.trim()); it.executeUpdate() }; writeAudit(c,actor.id,"EQUIPMENT_TYPE_CREATED","equipment_type",id,"{}"); c.commit() } catch(e: Exception) { c.rollback(); throw e } } }
        catch(e: SQLException) { if(e.sqlState=="23505") throw AuthFailure("EQUIPMENT_TYPE_EXISTS"); throw e }
        return EquipmentTypeView(id.toString(),name.trim(),"MANUAL",true)
    }

    fun technicians(user: Principal): List<PersonnelView> = personnel(user,"technician","eid")
    fun operators(user: Principal): List<PersonnelView> = personnel(user,"operator","employee_id")
    private fun personnel(user: Principal,table: String,key: String): List<PersonnelView> = dataSource.connection.use { c ->
        c.prepareStatement("select p.id,p.$key employee_id,p.name,p.site_id,p.tunnel_id,p.shift_id,p.active from $table p where p.site_id is null or exists(select 1 from user_site_access a where a.user_id=? and a.site_id=p.site_id) or (? and not exists(select 1 from user_site_access a where a.user_id=?)) order by p.name").use { ps ->
            ps.setObject(1,user.id); ps.setBoolean(2,user.role in broadRoles); ps.setObject(3,user.id)
            ps.executeQuery().use { rs -> buildList { while(rs.next()) add(PersonnelView(rs.getObject("id",UUID::class.java).toString(),rs.getString("employee_id"),rs.getString("name"),rs.getObject("site_id",UUID::class.java)?.toString(),rs.getObject("tunnel_id",UUID::class.java)?.toString(),rs.getObject("shift_id",UUID::class.java)?.toString(),rs.getBoolean("active"))) } }
        }
    }

    fun equipment(user: Principal, siteId: UUID?, query: String?, limit: Int): List<EquipmentSummary> = dataSource.connection.use { c ->
        c.prepareStatement("""
            select e.id,e.asset_id,e.equipment_code,e.name,et.name type_name,e.site_id,s.name site_name,
              e.tunnel_id,t.name tunnel_name,e.lifecycle_state,e.current_status,e.engine_hours_current,e.version
            from equipment e join equipment_type et on et.id=e.type_id join site s on s.id=e.site_id
            left join tunnel t on t.id=e.tunnel_id
            where (exists(select 1 from user_site_access a where a.user_id=? and a.site_id=e.site_id)
              or (? and not exists(select 1 from user_site_access a where a.user_id=?)))
              and (?::uuid is null or e.site_id=?)
              and (?::text is null or e.asset_id ilike ? or e.equipment_code ilike ? or e.name ilike ?)
            order by e.asset_id limit ?
        """.trimIndent()).use { ps ->
            ps.setObject(1,user.id); ps.setBoolean(2,user.role in broadRoles); ps.setObject(3,user.id)
            ps.setObject(4,siteId); ps.setObject(5,siteId)
            val pattern=query?.trim()?.takeIf(String::isNotEmpty)?.let { "%$it%" }
            ps.setString(6,pattern); ps.setString(7,pattern); ps.setString(8,pattern); ps.setString(9,pattern); ps.setInt(10,limit.coerceIn(1,200))
            ps.executeQuery().use { rs -> buildList { while(rs.next()) add(rs.toSummary()) } }
        }
    }

    fun equipment(user: Principal, id: UUID): EquipmentSummary? = dataSource.connection.use { c ->
        c.prepareStatement("""
            select e.id,e.asset_id,e.equipment_code,e.name,et.name type_name,e.site_id,s.name site_name,
              e.tunnel_id,t.name tunnel_name,e.lifecycle_state,e.current_status,e.engine_hours_current,e.version
            from equipment e join equipment_type et on et.id=e.type_id join site s on s.id=e.site_id
            left join tunnel t on t.id=e.tunnel_id
            where e.id=? and (exists(select 1 from user_site_access a where a.user_id=? and a.site_id=e.site_id)
              or (? and not exists(select 1 from user_site_access a where a.user_id=?)))
        """.trimIndent()).use { ps ->
            ps.setObject(1,id); ps.setObject(2,user.id); ps.setBoolean(3,user.role in broadRoles); ps.setObject(4,user.id)
            ps.executeQuery().use { rs -> if(rs.next()) rs.toSummary() else null }
        }
    }

    fun createEquipment(actor: Principal,assetId: String,equipmentCode: String,name: String,typeId: UUID,siteId: UUID,tunnelId: UUID?,description: String?,model: String?,serialNumber: String?,manufactureYear: Int?,engineHours: java.math.BigDecimal?,requiresDailyInspection: Boolean): EquipmentSummary {
        require(assetId.isNotBlank() && equipmentCode.isNotBlank() && name.isNotBlank()) { "Asset ID, equipment code and name are required" }
        require(manufactureYear==null || manufactureYear in 1900..2200) { "Manufacture year is outside the supported range" }
        require(engineHours==null || engineHours.signum()>=0) { "Engine hours cannot be negative" }
        val id=UUID.randomUUID()
        try { dataSource.connection.use { c -> c.autoCommit=false; try {
            if(!activeSite(c,siteId)) throw AuthFailure("SITE_NOT_FOUND")
            if(!hasSiteAccess(c,actor,siteId)) throw AuthFailure("SITE_NOT_AUTHORIZED")
            if(tunnelId!=null && !activeTunnel(c,tunnelId,siteId)) throw AuthFailure("TUNNEL_NOT_IN_SITE")
            val typeActive=c.prepareStatement("select exists(select 1 from equipment_type where id=? and active)").use { it.setObject(1,typeId); it.executeQuery().use { rs -> rs.next() && rs.getBoolean(1) } }
            if(!typeActive) throw AuthFailure("EQUIPMENT_TYPE_NOT_FOUND")
            c.prepareStatement("insert into equipment(id,asset_id,equipment_code,name,type_id,site_id,tunnel_id,description,model,serial_number,manufacture_year,engine_hours_current,requires_daily_inspection) values (?,?,?,?,?,?,?,?,?,?,?,?,?)").use { ps -> ps.setObject(1,id); ps.setString(2,assetId.trim()); ps.setString(3,equipmentCode.trim()); ps.setString(4,name.trim()); ps.setObject(5,typeId); ps.setObject(6,siteId); ps.setObject(7,tunnelId); ps.setString(8,description); ps.setString(9,model); ps.setString(10,serialNumber); ps.setObject(11,manufactureYear); ps.setBigDecimal(12,engineHours); ps.setBoolean(13,requiresDailyInspection); ps.executeUpdate() }
            writeAudit(c,actor.id,"EQUIPMENT_CREATED","equipment",id,"{\"siteId\":\"$siteId\"}")
            c.commit()
        } catch(e: Exception) { c.rollback(); throw e } } } catch(e: SQLException) { if(e.sqlState=="23505") throw AuthFailure("ASSET_ID_EXISTS"); throw e }
        return equipment(actor,id) ?: throw AuthFailure("EQUIPMENT_NOT_FOUND")
    }

    fun updateEquipment(actor: Principal,id: UUID,expectedVersion: Long,equipmentCode: String,name: String,typeId: UUID,description: String?,model: String?,serialNumber: String?,manufactureYear: Int?,engineHours: java.math.BigDecimal?,requiresDailyInspection: Boolean,lifecycleState: String): EquipmentSummary {
        require(equipmentCode.isNotBlank() && name.isNotBlank()) { "Equipment code and name are required" }
        require(manufactureYear==null || manufactureYear in 1900..2200) { "Manufacture year is outside the supported range" }
        require(engineHours==null || engineHours.signum()>=0) { "Engine hours cannot be negative" }
        require(lifecycleState in setOf("ACTIVE","INACTIVE","RETIRED")) { "Lifecycle state is invalid" }
        dataSource.connection.use { c -> c.autoCommit=false; try {
            val currentSite=c.prepareStatement("select site_id,version from equipment where id=? for update").use { ps -> ps.setObject(1,id); ps.executeQuery().use { rs -> if(rs.next()) rs.getObject(1,UUID::class.java) to rs.getLong(2) else throw AuthFailure("EQUIPMENT_NOT_FOUND") } }
            if(!hasSiteAccess(c,actor,currentSite.first)) throw AuthFailure("SITE_NOT_AUTHORIZED")
            if(currentSite.second!=expectedVersion) throw AuthFailure("EQUIPMENT_VERSION_CONFLICT")
            val typeActive=c.prepareStatement("select exists(select 1 from equipment_type where id=? and active)").use { it.setObject(1,typeId); it.executeQuery().use { rs -> rs.next() && rs.getBoolean(1) } }
            if(!typeActive) throw AuthFailure("EQUIPMENT_TYPE_NOT_FOUND")
            c.prepareStatement("update equipment set equipment_code=?,name=?,type_id=?,description=?,model=?,serial_number=?,manufacture_year=?,engine_hours_current=?,requires_daily_inspection=?,lifecycle_state=?,updated_at=now(),version=version+1 where id=? and version=?").use { ps -> ps.setString(1,equipmentCode.trim()); ps.setString(2,name.trim()); ps.setObject(3,typeId); ps.setString(4,description); ps.setString(5,model); ps.setString(6,serialNumber); ps.setObject(7,manufactureYear); ps.setBigDecimal(8,engineHours); ps.setBoolean(9,requiresDailyInspection); ps.setString(10,lifecycleState); ps.setObject(11,id); ps.setLong(12,expectedVersion); if(ps.executeUpdate()!=1) throw AuthFailure("EQUIPMENT_VERSION_CONFLICT") }
            writeAudit(c,actor.id,"EQUIPMENT_UPDATED","equipment",id,"{\"siteId\":\"${currentSite.first}\",\"lifecycleState\":\"$lifecycleState\"}")
            c.commit()
        } catch(e: Exception) { c.rollback(); throw e } }
        return equipment(actor,id) ?: throw AuthFailure("EQUIPMENT_NOT_FOUND")
    }

    fun move(user: Principal, id: UUID, destinationSite: UUID, destinationTunnel: UUID?, reason: String, expectedVersion: Long): EquipmentSummary {
        if (!RolePermissions.allows(user.role,"CONFIG_MANAGE")) throw AuthFailure("FORBIDDEN")
        require(reason.isNotBlank()) { "Movement reason is required" }
        dataSource.connection.use { c -> c.autoCommit=false; try {
            val current=c.prepareStatement("select site_id,tunnel_id,version from equipment where id=? for update").use { ps -> ps.setObject(1,id); ps.executeQuery().use { rs -> if(rs.next()) Triple(rs.getObject(1,UUID::class.java),rs.getObject(2,UUID::class.java),rs.getLong(3)) else throw AuthFailure("EQUIPMENT_NOT_FOUND") } }
            if(!hasSiteAccess(c,user,current.first)) throw AuthFailure("SOURCE_SITE_NOT_AUTHORIZED")
            if(current.third!=expectedVersion) throw AuthFailure("EQUIPMENT_VERSION_CONFLICT")
            if(!activeSite(c,destinationSite)) throw AuthFailure("SITE_NOT_FOUND")
            if(!hasSiteAccess(c,user,destinationSite)) throw AuthFailure("DESTINATION_SITE_NOT_AUTHORIZED")
            if(destinationTunnel!=null && !activeTunnel(c,destinationTunnel,destinationSite)) throw AuthFailure("TUNNEL_NOT_IN_SITE")
            if(current.first==destinationSite && current.second==destinationTunnel) throw AuthFailure("EQUIPMENT_LOCATION_UNCHANGED")
            val changed=c.prepareStatement("update equipment set site_id=?,tunnel_id=?,updated_at=now(),version=version+1 where id=? and version=?").use { ps -> ps.setObject(1,destinationSite); ps.setObject(2,destinationTunnel); ps.setObject(3,id); ps.setLong(4,expectedVersion); ps.executeUpdate() }
            if(changed!=1) throw AuthFailure("EQUIPMENT_VERSION_CONFLICT")
            c.prepareStatement("insert into equipment_movement(equipment_id,from_site_id,from_tunnel_id,to_site_id,to_tunnel_id,moved_at,user_id,reason) values (?,?,?,?,?,now(),?,?)").use { ps -> ps.setObject(1,id); ps.setObject(2,current.first); ps.setObject(3,current.second); ps.setObject(4,destinationSite); ps.setObject(5,destinationTunnel); ps.setObject(6,user.id); ps.setString(7,reason.trim()); ps.executeUpdate() }
            c.prepareStatement("insert into audit_event(actor_id,action,entity_type,entity_id,occurred_at,metadata,event_hash) values (?,?,'equipment',?,now(),?::jsonb,?)").use { ps -> ps.setObject(1,user.id); ps.setString(2,"EQUIPMENT_MOVED"); ps.setString(3,id.toString()); ps.setString(4,"{\"fromSiteId\":\"${current.first}\",\"toSiteId\":\"$destinationSite\"}"); ps.setBytes(5,java.security.MessageDigest.getInstance("SHA-256").digest("equipment:$id:$expectedVersion".toByteArray())); ps.executeUpdate() }
            c.commit()
        } catch(e: SQLException) { c.rollback(); throw e } catch(e: Exception) { c.rollback(); throw e } }
        return equipment(user,id) ?: throw AuthFailure("EQUIPMENT_NOT_FOUND")
    }

    private fun activeSite(c: Connection,id:UUID)=c.prepareStatement("select exists(select 1 from site where id=? and state='ACTIVE')").use { it.setObject(1,id); it.executeQuery().use { rs -> rs.next() && rs.getBoolean(1) } }
    private fun writeAudit(c: Connection,actor: UUID,action: String,type: String,id: UUID,metadata: String) { c.prepareStatement("insert into audit_event(actor_id,action,entity_type,entity_id,occurred_at,metadata,event_hash) values (?,?,?, ?,now(),?::jsonb,?)").use { ps -> ps.setObject(1,actor); ps.setString(2,action); ps.setString(3,type); ps.setString(4,id.toString()); ps.setString(5,metadata); ps.setBytes(6,java.security.MessageDigest.getInstance("SHA-256").digest("$action:$id".toByteArray())); ps.executeUpdate() } }
    private fun hasSiteAccess(c: Connection,user: Principal,siteId:UUID)=c.prepareStatement("select exists(select 1 from user_site_access where user_id=? and site_id=?) or (? and not exists(select 1 from user_site_access where user_id=?))").use { it.setObject(1,user.id); it.setObject(2,siteId); it.setBoolean(3,user.role in broadRoles); it.setObject(4,user.id); it.executeQuery().use { rs -> rs.next() && rs.getBoolean(1) } }
    private fun activeTunnel(c: Connection,id:UUID,siteId:UUID)=c.prepareStatement("select exists(select 1 from tunnel where id=? and site_id=? and state='ACTIVE')").use { it.setObject(1,id); it.setObject(2,siteId); it.executeQuery().use { rs -> rs.next() && rs.getBoolean(1) } }
    private fun ResultSet.toSummary()=EquipmentSummary(getObject("id",UUID::class.java).toString(),getString("asset_id"),getString("equipment_code"),getString("name"),getString("type_name"),getObject("site_id",UUID::class.java).toString(),getString("site_name"),getObject("tunnel_id",UUID::class.java)?.toString(),getString("tunnel_name"),getString("lifecycle_state"),getString("current_status") ?: "Not Recorded",getBigDecimal("engine_hours_current")?.toDouble(),getLong("version"))

    companion object { private val broadRoles=setOf(Role.MANAGER,Role.GENERAL_MANAGER,Role.ADMINISTRATOR) }
}
