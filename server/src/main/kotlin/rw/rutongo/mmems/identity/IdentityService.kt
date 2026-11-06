package rw.rutongo.mmems.identity

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import de.mkammerer.argon2.Argon2Factory
import rw.rutongo.mmems.shared.enums.Role
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.sql.Connection
import java.sql.SQLException
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.sql.DataSource

data class Principal(
    val id: UUID,
    val username: String,
    val role: Role,
    val mustChangePassword: Boolean,
)

data class SessionTokens(
    val accessToken: String,
    val refreshToken: String,
    val expiresIn: Long,
    val mustChangePassword: Boolean,
)

data class UserView(
    val id: String,
    val username: String,
    val fullName: String,
    val employeeId: String,
    val role: Role,
    val active: Boolean,
    val siteIds: List<String>,
    val version: Long,
)

class TokenSigner private constructor(
    private val privateKey: RSAPrivateKey,
    private val publicKey: RSAPublicKey,
) {
    fun sign(user: Principal): String {
        val now = Instant.now()
        val claims =
            JWTClaimsSet
                .Builder()
                .subject(user.id.toString())
                .issuer("rutongo-mmems")
                .issueTime(java.util.Date.from(now))
                .expirationTime(java.util.Date.from(now.plusSeconds(900)))
                .claim("username", user.username)
                .claim("role", user.role.name)
                .claim("mustChangePassword", user.mustChangePassword)
                .build()
        val jwt = SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID("primary").build(), claims)
        jwt.sign(RSASSASigner(privateKey))
        return jwt.serialize()
    }

    fun verify(raw: String): Principal? =
        try {
            val jwt = SignedJWT.parse(raw)
            if (jwt.header.algorithm != JWSAlgorithm.RS256 || !jwt.verify(RSASSAVerifier(publicKey))) {
                null
            } else {
                val claims = jwt.jwtClaimsSet
                if (claims.issuer != "rutongo-mmems" || claims.expirationTime.toInstant().isBefore(Instant.now())) {
                    null
                } else {
                    Principal(UUID.fromString(claims.subject), claims.getStringClaim("username"), Role.valueOf(claims.getStringClaim("role")), claims.getBooleanClaim("mustChangePassword"))
                }
            }
        } catch (_: Exception) {
            null
        }

    companion object {
        internal fun createForTests(): TokenSigner =
            KeyPairGenerator
                .getInstance("RSA")
                .apply { initialize(2048) }
                .generateKeyPair()
                .let { TokenSigner(it.private as RSAPrivateKey, it.public as RSAPublicKey) }

        fun fromEnvironment(): TokenSigner {
            val factory = KeyFactory.getInstance("RSA")
            val privateBytes = System.getenv("JWT_PRIVATE_KEY_B64")?.let(Base64.getDecoder()::decode)
            val publicBytes = System.getenv("JWT_PUBLIC_KEY_B64")?.let(Base64.getDecoder()::decode)
            val pair: KeyPair =
                if (privateBytes != null && publicBytes != null) {
                    KeyPair(factory.generatePublic(X509EncodedKeySpec(publicBytes)), factory.generatePrivate(PKCS8EncodedKeySpec(privateBytes)))
                } else {
                    require(System.getenv("APP_ENV") != "production") { "JWT_PRIVATE_KEY_B64 and JWT_PUBLIC_KEY_B64 are required in production" }
                    KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
                }
            return TokenSigner(pair.private as RSAPrivateKey, pair.public as RSAPublicKey)
        }
    }
}

class IdentityService(
    private val dataSource: DataSource,
    private val signer: TokenSigner,
) {
    private val argon = Argon2Factory.create(Argon2Factory.Argon2Types.ARGON2id)

    fun login(
        username: String,
        password: CharArray,
        clientType: String,
        deviceLabel: String?,
    ): SessionTokens? {
        if (clientType !in setOf("WEB", "ANDROID")) return null
        val user =
            dataSource.connection.use { connection ->
                connection.prepareStatement("select id, username, role, password_hash, active, must_change_password, locked_until from app_user where username = ?").use { statement ->
                    statement.setString(1, username.trim())
                    statement.executeQuery().use { result ->
                        if (result.next()) {
                            UserRecord(
                                result.getObject("id", UUID::class.java),
                                result.getString("username"),
                                Role.valueOf(result.getString("role")),
                                result.getString("password_hash"),
                                result.getBoolean("active"),
                                result.getBoolean("must_change_password"),
                                result.getTimestamp("locked_until")?.toInstant(),
                            )
                        } else {
                            null
                        }
                    }
                }
            }
        if (user == null) {
            burnPasswordCheck(password)
            return null
        }
        if (!user.active || user.lockedUntil?.isAfter(Instant.now()) == true || !argon.verify(user.passwordHash, password)) {
            recordFailure(user.id)
            return null
        }
        val principal = Principal(user.id, user.username, user.role, user.mustChangePassword)
        return dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                connection.prepareStatement("update app_user set failed_attempts=0, locked_until=null, last_login_at=now() where id=?").use {
                    it.setObject(1, user.id)
                    it.executeUpdate()
                }
                appendAudit(connection, user.id, "LOGIN_SUCCEEDED", "app_user", user.id.toString(), "{}")
                val result = issueSession(connection, principal, clientType, deviceLabel, null)
                connection.commit()
                result
            } catch (error: Exception) {
                connection.rollback()
                throw error
            }
        }
    }

    fun allowLoginAttempt(key: String): Boolean =
        dataSource.connection.use { c ->
            c.prepareStatement("insert into login_rate_bucket(bucket_key,window_started_at,attempts) values (?,now(),1) on conflict(bucket_key) do update set attempts=case when login_rate_bucket.window_started_at < now()-interval '1 minute' then 1 else login_rate_bucket.attempts+1 end, window_started_at=case when login_rate_bucket.window_started_at < now()-interval '1 minute' then now() else login_rate_bucket.window_started_at end returning attempts").use { ps ->
                ps.setString(1, key)
                ps.executeQuery().use { rs -> rs.next() && rs.getInt(1) <= 10 }
            }
        }

    fun refresh(
        rawToken: String,
        clientType: String,
        deviceLabel: String?,
    ): SessionTokens? =
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                val hash = sha256(rawToken)
                val stored =
                    connection.prepareStatement("select id,user_id,family_id,expires_at,used_at,revoked_at,client_type from refresh_token where token_hash=? for update").use { ps ->
                        ps.setBytes(1, hash)
                        ps.executeQuery().use { rs -> if (rs.next()) RefreshRecord(rs.getObject("id", UUID::class.java), rs.getObject("user_id", UUID::class.java), rs.getObject("family_id", UUID::class.java), rs.getTimestamp("expires_at").toInstant(), rs.getTimestamp("used_at")?.toInstant(), rs.getTimestamp("revoked_at")?.toInstant(), rs.getString("client_type")) else null }
                    }
                if (stored == null) {
                    connection.rollback()
                    return null
                }
                if (stored.usedAt != null) {
                    connection.prepareStatement("update refresh_token set revoked_at=now() where family_id=? and revoked_at is null").use {
                        it.setObject(1, stored.familyId)
                        it.executeUpdate()
                    }
                    connection.commit()
                    return null
                }
                if (stored.revokedAt != null || stored.expiresAt.isBefore(Instant.now()) || stored.clientType != clientType) {
                    connection.rollback()
                    return null
                }
                val user =
                    loadPrincipal(connection, stored.userId) ?: run {
                        connection.rollback()
                        return null
                    }
                connection.prepareStatement("update refresh_token set used_at=now() where id=?").use {
                    it.setObject(1, stored.id)
                    it.executeUpdate()
                }
                val session = issueSession(connection, user, clientType, deviceLabel, stored.familyId)
                connection.commit()
                session
            } catch (error: Exception) {
                connection.rollback()
                throw error
            }
        }

    fun logout(rawToken: String) {
        dataSource.connection.use { c ->
            c.autoCommit = false
            try {
                val userId =
                    c.prepareStatement("select user_id from refresh_token where token_hash=? for update").use { ps ->
                        ps.setBytes(1, sha256(rawToken))
                        ps.executeQuery().use { rs -> if (rs.next()) rs.getObject(1, UUID::class.java) else null }
                    }
                c.prepareStatement("update refresh_token set revoked_at=now() where token_hash=? and revoked_at is null").use {
                    it.setBytes(1, sha256(rawToken))
                    it.executeUpdate()
                }
                if (userId != null) appendAudit(c, userId, "LOGOUT", "app_user", userId.toString(), "{}")
                c.commit()
            } catch (e: Exception) {
                c.rollback()
                throw e
            }
        }
    }

    fun changePassword(
        principal: Principal,
        current: CharArray,
        next: CharArray,
    ) {
        validatePassword(next)
        dataSource.connection.use { c ->
            c.autoCommit = false
            try {
                val hash =
                    c.prepareStatement("select password_hash from app_user where id=? for update").use { ps ->
                        ps.setObject(1, principal.id)
                        ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else throw AuthFailure("INVALID_CREDENTIALS") }
                    }
                if (!argon.verify(hash, current)) throw AuthFailure("INVALID_CREDENTIALS")
                c.prepareStatement("update app_user set password_hash=?,must_change_password=false,updated_at=now(),version=version+1 where id=?").use { ps ->
                    ps.setString(1, argon.hash(3, 65536, 1, next))
                    ps.setObject(2, principal.id)
                    ps.executeUpdate()
                }
                c.prepareStatement("update refresh_token set revoked_at=now() where user_id=? and revoked_at is null").use {
                    it.setObject(1, principal.id)
                    it.executeUpdate()
                }
                appendAudit(c, principal.id, "PASSWORD_CHANGED", "app_user", principal.id.toString(), "{}")
                c.commit()
            } catch (e: Exception) {
                c.rollback()
                throw e
            }
        }
    }

    fun me(userId: UUID): UserView? = dataSource.connection.use { c -> loadUser(c, userId) }

    fun currentPrincipal(userId: UUID): Principal? = dataSource.connection.use { c -> loadPrincipal(c, userId) }

    fun listUsers(): List<UserView> =
        dataSource.connection.use { c ->
            c.prepareStatement("select id from app_user order by username").use { ps -> ps.executeQuery().use { rs -> buildList { while (rs.next()) loadUser(c, rs.getObject(1, UUID::class.java))?.let(::add) } } }
        }

    fun createUser(
        actorId: UUID,
        username: String,
        fullName: String,
        employeeId: String,
        role: Role,
        password: CharArray,
        siteIds: List<UUID>,
    ): UserView {
        require(username.isNotBlank() && fullName.isNotBlank() && employeeId.isNotBlank())
        require(siteIds.isNotEmpty()) { "At least one authorized site is required" }
        validatePassword(password)
        val id = UUID.randomUUID()
        dataSource.connection.use { c ->
            c.autoCommit = false
            try {
                ensureActiveSites(c, siteIds)
                c.prepareStatement("insert into app_user(id,username,full_name,employee_id,role,password_hash,must_change_password) values (?,?,?,?,?,?,true)").use { ps ->
                    ps.setObject(1, id)
                    ps.setString(2, username.trim())
                    ps.setString(3, fullName.trim())
                    ps.setString(4, employeeId.trim())
                    ps.setString(5, role.name)
                    ps.setString(6, argon.hash(3, 65536, 1, password))
                    ps.executeUpdate()
                }
                c.prepareStatement("insert into user_site_access(user_id,site_id) values (?,?)").use { ps ->
                    siteIds.distinct().forEach {
                        ps.setObject(1, id)
                        ps.setObject(2, it)
                        ps.addBatch()
                    }
                    ps.executeBatch()
                }
                appendAudit(c, actorId, "USER_CREATED", "app_user", id.toString(), "{\"role\":\"${role.name}\"}")
                c.commit()
            } catch (e: Exception) {
                c.rollback()
                if (e is SQLException && e.sqlState == "23505") throw AuthFailure("USER_ALREADY_EXISTS")
                throw e
            }
        }
        return me(id)!!
    }

    fun updateUser(
        actorId: UUID,
        id: UUID,
        expectedVersion: Long,
        fullName: String,
        employeeId: String,
        role: Role,
        siteIds: List<UUID>,
    ): UserView {
        require(fullName.isNotBlank() && employeeId.isNotBlank() && siteIds.isNotEmpty()) { "Name, employee ID and authorized sites are required" }
        dataSource.connection.use { c ->
            c.autoCommit = false
            try {
                ensureActiveSites(c, siteIds)
                c.prepareStatement("update app_user set full_name=?,employee_id=?,role=?,updated_at=now(),version=version+1 where id=? and version=?").use { ps ->
                    ps.setString(1, fullName.trim())
                    ps.setString(2, employeeId.trim())
                    ps.setString(3, role.name)
                    ps.setObject(4, id)
                    ps.setLong(5, expectedVersion)
                    if (ps.executeUpdate() == 0) throw AuthFailure("USER_VERSION_CONFLICT")
                }
                c.prepareStatement("delete from user_site_access where user_id=?").use {
                    it.setObject(1, id)
                    it.executeUpdate()
                }
                c.prepareStatement("insert into user_site_access(user_id,site_id) values (?,?)").use { ps ->
                    siteIds.distinct().forEach {
                        ps.setObject(1, id)
                        ps.setObject(2, it)
                        ps.addBatch()
                    }
                    ps.executeBatch()
                }
                appendAudit(c, actorId, "USER_UPDATED", "app_user", id.toString(), "{\"role\":\"${role.name}\"}")
                c.commit()
            } catch (e: Exception) {
                c.rollback()
                throw e
            }
        }
        return me(id) ?: throw AuthFailure("USER_NOT_FOUND")
    }

    fun setUserActive(
        actorId: UUID,
        id: UUID,
        active: Boolean,
    ) {
        dataSource.connection.use { c ->
            c.autoCommit = false
            try {
                c.prepareStatement("update app_user set active=?,updated_at=now(),version=version+1 where id=?").use {
                    it.setBoolean(1, active)
                    it.setObject(2, id)
                    if (it.executeUpdate() == 0) throw AuthFailure("USER_NOT_FOUND")
                }
                if (!active) {
                    c.prepareStatement("update refresh_token set revoked_at=now() where user_id=? and revoked_at is null").use {
                        it.setObject(1, id)
                        it.executeUpdate()
                    }
                }
                appendAudit(c, actorId, if (active) "USER_ACTIVATED" else "USER_DEACTIVATED", "app_user", id.toString(), "{}")
                c.commit()
            } catch (e: Exception) {
                c.rollback()
                throw e
            }
        }
    }

    fun canAccess(
        userId: UUID,
        role: Role,
        siteId: UUID,
    ): Boolean {
        val broadRole = role in setOf(Role.MANAGER, Role.GENERAL_MANAGER, Role.ADMINISTRATOR)
        val sql =
            if (broadRole) {
                "select exists(select 1 from site s where s.id=? and s.state='ACTIVE' and (not exists(select 1 from user_site_access where user_id=?) or exists(select 1 from user_site_access where user_id=? and site_id=s.id)))"
            } else {
                "select exists(select 1 from site s join user_site_access usa on usa.site_id=s.id where s.id=? and s.state='ACTIVE' and usa.user_id=?)"
            }
        return dataSource.connection.use { c ->
            c.prepareStatement(sql).use { ps ->
                ps.setObject(1, siteId)
                ps.setObject(2, userId)
                if (broadRole) ps.setObject(3, userId)
                ps.executeQuery().use { rs -> rs.next() && rs.getBoolean(1) }
            }
        }
    }

    fun auditEvents(limit: Int = 100): List<Map<String, String?>> =
        dataSource.connection.use { c ->
            c.prepareStatement("select actor_id,action,entity_type,entity_id,occurred_at,metadata,encode(previous_hash,'hex') previous_hash,encode(event_hash,'hex') event_hash from audit_event order by occurred_at desc,id desc limit ?").use { ps ->
                ps.setInt(1, limit.coerceIn(1, 500))
                ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) add(mapOf("actorId" to rs.getString("actor_id"), "action" to rs.getString("action"), "entityType" to rs.getString("entity_type"), "entityId" to rs.getString("entity_id"), "occurredAt" to rs.getString("occurred_at"), "metadata" to rs.getString("metadata"), "previousHash" to rs.getString("previous_hash"), "eventHash" to rs.getString("event_hash")))
                    }
                }
            }
        }

    fun bootstrapAdmin(
        username: String,
        fullName: String,
        employeeId: String,
        password: CharArray,
    ) {
        validatePassword(password)
        dataSource.connection.use { c ->
            c.autoCommit = false
            try {
                c.createStatement().use { it.execute("select pg_advisory_xact_lock(72419831)") }
                c.prepareStatement("select id from app_user where role='ADMINISTRATOR' for update").use { ps -> ps.executeQuery().use { if (it.next()) throw AuthFailure("ADMIN_ALREADY_EXISTS") } }
                val id = UUID.randomUUID()
                c.prepareStatement("insert into app_user(id,username,full_name,employee_id,role,password_hash,must_change_password) values (?,?,?,?,'ADMINISTRATOR',?,true)").use { ps ->
                    ps.setObject(1, id)
                    ps.setString(2, username)
                    ps.setString(3, fullName)
                    ps.setString(4, employeeId)
                    ps.setString(5, argon.hash(3, 65536, 1, password))
                    ps.executeUpdate()
                }
                c.prepareStatement("insert into audit_event(actor_id,action,entity_type,entity_id,occurred_at,metadata,event_hash) values (?,?,'app_user',?,now(),?::jsonb,?)").use { ps ->
                    ps.setObject(1, id)
                    ps.setString(2, "BOOTSTRAP_ADMIN")
                    ps.setString(3, id.toString())
                    ps.setString(4, "{}")
                    ps.setBytes(5, sha256("BOOTSTRAP_ADMIN:$id"))
                    ps.executeUpdate()
                }
                c.commit()
            } catch (e: Exception) {
                c.rollback()
                throw e
            }
        }
    }

    private fun loadPrincipal(
        c: Connection,
        id: UUID,
    ): Principal? =
        c.prepareStatement("select username,role,must_change_password,active from app_user where id=?").use { ps ->
            ps.setObject(1, id)
            ps.executeQuery().use { rs -> if (rs.next() && rs.getBoolean("active")) Principal(id, rs.getString("username"), Role.valueOf(rs.getString("role")), rs.getBoolean("must_change_password")) else null }
        }

    private fun appendAudit(
        c: Connection,
        actor: UUID?,
        action: String,
        type: String,
        entityId: String,
        metadata: String,
    ) {
        c.prepareStatement("insert into audit_event(actor_id,action,entity_type,entity_id,occurred_at,metadata,event_hash) values (?,?,?,?,now(),?::jsonb,?)").use { ps ->
            ps.setObject(1, actor)
            ps.setString(2, action)
            ps.setString(3, type)
            ps.setString(4, entityId)
            ps.setString(5, metadata)
            ps.setBytes(6, sha256("pending"))
            ps.executeUpdate()
        }
    }

    private fun loadUser(
        c: Connection,
        id: UUID,
    ): UserView? =
        c.prepareStatement("select id,username,full_name,employee_id,role,active,version from app_user where id=?").use { ps ->
            ps.setObject(1, id)
            ps.executeQuery().use { rs ->
                if (!rs.next()) {
                    null
                } else {
                    val sites =
                        c.prepareStatement("select site_id from user_site_access where user_id=? order by site_id").use { s ->
                            s.setObject(1, id)
                            s.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getObject(1, UUID::class.java).toString()) } }
                        }
                    UserView(id.toString(), rs.getString("username"), rs.getString("full_name"), rs.getString("employee_id"), Role.valueOf(rs.getString("role")), rs.getBoolean("active"), sites, rs.getLong("version"))
                }
            }
        }

    private fun ensureActiveSites(
        c: Connection,
        siteIds: List<UUID>,
    ) {
        val expected = siteIds.distinct().size
        val active =
            c.prepareStatement("select count(*) from site where id=any(?) and state='ACTIVE'").use { ps ->
                ps.setArray(1, c.createArrayOf("uuid", siteIds.distinct().toTypedArray()))
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        require(active == expected) { "One or more site IDs are invalid or inactive" }
    }

    private fun issueSession(
        c: Connection,
        user: Principal,
        clientType: String,
        deviceLabel: String?,
        family: UUID?,
    ): SessionTokens {
        val opaque = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(48).also(java.security.SecureRandom()::nextBytes))
        val now = Instant.now()
        val lifetime = if (clientType == "ANDROID") 30L * 24 * 3600 else 8L * 3600
        val familyId = family ?: UUID.randomUUID()
        c.prepareStatement("insert into refresh_token(id,user_id,token_hash,family_id,issued_at,expires_at,device_label,client_type) values (?,?,?,?,?,?,?,?)").use { ps ->
            ps.setObject(1, UUID.randomUUID())
            ps.setObject(2, user.id)
            ps.setBytes(3, sha256(opaque))
            ps.setObject(4, familyId)
            ps.setTimestamp(5, java.sql.Timestamp.from(now))
            ps.setTimestamp(6, java.sql.Timestamp.from(now.plusSeconds(lifetime)))
            ps.setString(7, deviceLabel?.take(120))
            ps.setString(8, clientType)
            ps.executeUpdate()
        }
        return SessionTokens(signer.sign(user), opaque, 900, user.mustChangePassword)
    }

    private fun recordFailure(id: UUID) {
        dataSource.connection.use { c ->
            c.autoCommit = false
            try {
                c.prepareStatement("update app_user set failed_attempts=failed_attempts+1,locked_until=case when failed_attempts+1>=5 then now()+make_interval(secs=>least(3600,power(2,least(failed_attempts+1-5,10))::integer)) else null end where id=?").use {
                    it.setObject(1, id)
                    it.executeUpdate()
                }
                appendAudit(c, id, "LOGIN_FAILED", "app_user", id.toString(), "{}")
                c.commit()
            } catch (e: Exception) {
                c.rollback()
                throw e
            }
        }
    }

    private fun burnPasswordCheck(password: CharArray) {
        argon.verify(argon.hash(3, 65536, 1, "not-a-real-password".toCharArray()), password)
    }

    private fun validatePassword(password: CharArray) {
        require(password.size >= 10) { "Password must contain at least 10 characters" }
        val value = String(password).lowercase()
        require(value !in COMMON_PASSWORDS) { "Password is too common" }
    }

    private fun sha256(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))

    private data class UserRecord(
        val id: UUID,
        val username: String,
        val role: Role,
        val passwordHash: String,
        val active: Boolean,
        val mustChangePassword: Boolean,
        val lockedUntil: Instant?,
    )

    private data class RefreshRecord(
        val id: UUID,
        val userId: UUID,
        val familyId: UUID,
        val expiresAt: Instant,
        val usedAt: Instant?,
        val revokedAt: Instant?,
        val clientType: String,
    )

    companion object {
        private val COMMON_PASSWORDS = setOf("password123", "1234567890", "qwerty1234", "rutongo123", "password1234", "admin123456", "letmein1234", "welcome1234", "iloveyou123", "123456789a", "qwertyuiop", "changeme123", "administrator")
    }
}

class AuthFailure(
    val code: String,
) : RuntimeException(code)

object RolePermissions {
    private val grants =
        mapOf(
            Role.MECHANIC to setOf("EQUIPMENT_VIEW", "INSPECTION_SUBMIT", "STATUS_UPDATE", "BREAKDOWN_MANAGE", "MAINTENANCE_RECORD", "PART_REQUEST", "REPORT_VIEW_OWN_SITE"),
            Role.SUPERVISOR to setOf("EQUIPMENT_VIEW", "STATUS_UPDATE", "BREAKDOWN_MANAGE", "MAINTENANCE_RECORD", "MAINTENANCE_VERIFY", "PART_REQUEST", "PART_APPROVE", "REPORT_PREPARE", "REPORT_VIEW_OWN_SITE"),
            Role.MANAGER to setOf("EQUIPMENT_VIEW", "PART_APPROVE", "STATUS_OVERRIDE", "INSPECTION_REVIEW", "REPORT_APPROVE", "REPORT_VIEW_APPROVED", "COST_VIEW", "AUDIT_VIEW"),
            Role.GENERAL_MANAGER to setOf("EQUIPMENT_VIEW", "REPORT_VIEW_APPROVED", "COST_VIEW"),
            Role.ADMINISTRATOR to setOf("EQUIPMENT_VIEW", "USER_MANAGE", "CONFIG_MANAGE", "IMPORT_RUN", "CHECKLIST_MANAGE", "AUDIT_VIEW"),
        )

    fun allows(
        role: Role,
        permission: String,
    ) = permission in grants.getValue(role)
}
