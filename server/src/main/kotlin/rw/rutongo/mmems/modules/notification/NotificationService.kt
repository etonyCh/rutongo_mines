@file:Suppress("TooManyFunctions", "NestedBlockDepth", "MagicNumber")

package rw.rutongo.mmems.modules.notification

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import rw.rutongo.mmems.identity.AuthFailure
import rw.rutongo.mmems.identity.Principal
import rw.rutongo.mmems.identity.RolePermissions
import rw.rutongo.mmems.shared.api.NotificationDto
import rw.rutongo.mmems.shared.api.NotificationListDto
import rw.rutongo.mmems.shared.api.NotificationRuleDto
import rw.rutongo.mmems.shared.api.NotificationRuleUpdateDto
import rw.rutongo.mmems.shared.api.PushDeviceDto
import rw.rutongo.mmems.shared.api.PushDeviceRegistrationDto
import rw.rutongo.mmems.shared.enums.NotificationChannel
import rw.rutongo.mmems.shared.enums.NotificationType
import rw.rutongo.mmems.shared.enums.Role
import java.sql.Connection
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

class NotificationService(
    private val dataSource: DataSource,
    private val now: () -> Instant = Instant::now,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun list(
        actor: Principal,
        since: Instant? = null,
        unreadOnly: Boolean = false,
        limit: Int = 100,
    ): NotificationListDto =
        dataSource.connection.use { c ->
            val items =
                c
                    .prepareStatement(
                        """
                        select id,type,dedupe_key,payload,created_at,read_at
                        from notification
                        where user_id=? and (?::timestamptz is null or created_at>=?)
                          and (not ? or read_at is null)
                        order by created_at desc,id desc limit ?
                        """.trimIndent(),
                    ).use { ps ->
                        ps.setObject(1, actor.id)
                        ps.setTimestamp(2, since?.let(java.sql.Timestamp::from))
                        ps.setTimestamp(3, since?.let(java.sql.Timestamp::from))
                        ps.setBoolean(4, unreadOnly)
                        ps.setInt(5, limit.coerceIn(1, 500))
                        ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toNotification()) } }
                    }
            NotificationListDto(items, unreadCount(c, actor.id), now().toString())
        }

    fun markRead(
        actor: Principal,
        notificationId: UUID,
    ): NotificationDto =
        dataSource.connection.use { c ->
            c.prepareStatement("update notification set read_at=coalesce(read_at,now()) where id=? and user_id=?").use { ps ->
                ps.setObject(1, notificationId)
                ps.setObject(2, actor.id)
                ps.executeUpdate()
            }
            c.prepareStatement("select id,type,dedupe_key,payload,created_at,read_at from notification where id=? and user_id=?").use { ps ->
                ps.setObject(1, notificationId)
                ps.setObject(2, actor.id)
                ps.executeQuery().use { rs ->
                    if (rs.next()) rs.toNotification() else throw AuthFailure("NOTIFICATION_NOT_FOUND")
                }
            }
        }

    fun markAllRead(actor: Principal): Int =
        dataSource.connection.use { c ->
            c.prepareStatement("update notification set read_at=now() where user_id=? and read_at is null").use { ps ->
                ps.setObject(1, actor.id)
                ps.executeUpdate()
            }
        }

    fun registerDevice(
        actor: Principal,
        input: PushDeviceRegistrationDto,
    ): PushDeviceDto {
        val token = input.token.trim()
        require(token.length in 8..4096) { "The push token is invalid." }
        return dataSource.connection.use { c ->
            c
                .prepareStatement(
                    """
                    insert into push_device(user_id,token,platform) values (?,?,?)
                    on conflict(token) do update set user_id=excluded.user_id,platform=excluded.platform,last_seen_at=now()
                    returning id,platform,created_at,last_seen_at
                    """.trimIndent(),
                ).use { ps ->
                    ps.setObject(1, actor.id)
                    ps.setString(2, token)
                    ps.setString(3, input.platform.name)
                    ps.executeQuery().use { rs ->
                        rs.next()
                        PushDeviceDto(
                            rs.getObject("id", UUID::class.java).toString(),
                            rw.rutongo.mmems.shared.enums.PushPlatform
                                .valueOf(rs.getString("platform")),
                            rs.getTimestamp("created_at").toInstant().toString(),
                            rs.getTimestamp("last_seen_at").toInstant().toString(),
                        )
                    }
                }
        }
    }

    fun eventsAfter(
        actor: Principal,
        cursorAt: Instant?,
        cursorId: UUID?,
    ): List<NotificationDto> {
        if (cursorAt == null || cursorId == null) return emptyList()
        return dataSource.connection.use { c ->
            c
                .prepareStatement(
                    """
                    select id,type,dedupe_key,payload,created_at,read_at
                    from notification
                    where user_id=? and (created_at>? or (created_at=? and id>?))
                    order by created_at,id limit 100
                    """.trimIndent(),
                ).use { ps ->
                    ps.setObject(1, actor.id)
                    ps.setTimestamp(2, java.sql.Timestamp.from(cursorAt))
                    ps.setTimestamp(3, java.sql.Timestamp.from(cursorAt))
                    ps.setObject(4, cursorId)
                    ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toNotification()) } }
                }
        }
    }

    fun rules(actor: Principal): List<NotificationRuleDto> {
        requireConfig(actor)
        return dataSource.connection.use { c ->
            c.prepareStatement("select type,roles,site_scoped,channel,enabled from notification_rule order by type").use { ps ->
                ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toRule()) } }
            }
        }
    }

    fun updateRule(
        actor: Principal,
        type: NotificationType,
        input: NotificationRuleUpdateDto,
    ): NotificationRuleDto {
        requireConfig(actor)
        require(input.roles.isNotEmpty()) { "At least one role is required." }
        return dataSource.connection.use { c ->
            c
                .prepareStatement("update notification_rule set roles=?::text[],site_scoped=?,channel=?,enabled=?,updated_at=now() where type=? returning type,roles,site_scoped,channel,enabled")
                .use { ps ->
                    ps.setString(1, input.roles.joinToString(",", "{", "}"))
                    ps.setBoolean(2, input.siteScoped)
                    ps.setString(3, input.channel.name)
                    ps.setBoolean(4, input.enabled)
                    ps.setString(5, type.name)
                    ps.executeQuery().use { rs ->
                        if (rs.next()) rs.toRule() else throw AuthFailure("NOTIFICATION_RULE_NOT_FOUND")
                    }
                }
        }
    }

    fun loadRule(
        c: Connection,
        type: NotificationType,
    ): NotificationRuleDto? =
        c.prepareStatement("select type,roles,site_scoped,channel,enabled from notification_rule where type=?").use { ps ->
            ps.setString(1, type.name)
            ps.executeQuery().use { rs -> if (rs.next()) rs.toRule() else null }
        }

    fun payload(raw: String): Map<String, String> =
        runCatching {
            val obj = json.parseToJsonElement(raw).jsonObject
            obj.entries.associate { (key, value) -> key to value.asString() }
        }.getOrDefault(emptyMap())

    private fun JsonElement.asString(): String =
        when (this) {
            is JsonPrimitive -> content ?: "null"
            else -> toString()
        }

    private fun unreadCount(
        c: Connection,
        userId: UUID,
    ): Int =
        c.prepareStatement("select count(*) from notification where user_id=? and read_at is null").use { ps ->
            ps.setObject(1, userId)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }

    private fun requireConfig(actor: Principal) {
        if (!RolePermissions.allows(actor.role, "CONFIG_MANAGE")) throw AuthFailure("FORBIDDEN")
    }

    private fun java.sql.ResultSet.toNotification(): NotificationDto =
        NotificationDto(
            id = getObject("id", UUID::class.java).toString(),
            type = NotificationType.valueOf(getString("type")),
            dedupeKey = getString("dedupe_key"),
            payload = payload(getString("payload")),
            createdAt = getTimestamp("created_at").toInstant().toString(),
            readAt = getTimestamp("read_at")?.toInstant()?.toString(),
        )

    private fun java.sql.ResultSet.toRule(): NotificationRuleDto =
        NotificationRuleDto(
            type = NotificationType.valueOf(getString("type")),
            roles = (getArray("roles").array as Array<*>).map { Role.valueOf(it.toString()) },
            siteScoped = getBoolean("site_scoped"),
            channel = NotificationChannel.valueOf(getString("channel")),
            enabled = getBoolean("enabled"),
        )
}
