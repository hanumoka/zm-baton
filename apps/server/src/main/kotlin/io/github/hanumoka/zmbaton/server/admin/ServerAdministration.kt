package io.github.hanumoka.zmbaton.server.admin

import com.fasterxml.jackson.annotation.JsonProperty
import io.github.hanumoka.zmbaton.server.BadRequestException
import io.github.hanumoka.zmbaton.server.ConflictException
import io.github.hanumoka.zmbaton.server.ForbiddenException
import io.github.hanumoka.zmbaton.server.SessionService
import io.github.hanumoka.zmbaton.server.auth.AuthSettings
import io.github.hanumoka.zmbaton.server.auth.Requester
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.OffsetDateTime

data class ServerAdministrationView(
	@JsonProperty("organization_id") val organizationId: String,
	@JsonProperty("organization_name") val organizationName: String,
	@JsonProperty("owner_user_id") val ownerUserId: String,
	@JsonProperty("administrator_user_id") val administratorUserId: String,
	@JsonProperty("administrator_name") val administratorName: String,
	@JsonProperty("initialized_at") val initializedAt: OffsetDateTime?,
	@JsonProperty("auth_mode") val authMode: String,
)

data class InitializeServerIn(
	@JsonProperty("organization_name") val organizationName: String,
	@JsonProperty("administrator_name") val administratorName: String,
)

data class TransferServerIn(@JsonProperty("user_id") val userId: String)

/**
 * Installation authority is deliberately not an organisation role. A server administrator still
 * passes the ordinary permission rules on every work/document/note endpoint. The singleton row
 * serializes initialization, administrator transfer and person status changes, in that order before
 * any user row lock. These proposed policies are reviewed before this preview is merged.
 */
@Service
class ServerAdministration(
	private val jdbc: JdbcClient,
	private val requester: Requester,
	private val settings: AuthSettings,
	private val sessions: SessionService,
) {
	fun isAdministrator(person: String): Boolean = jdbc.sql(
		"select count(*) from server_installation s join users u on u.id = s.administrator_user_id where s.id = 1 and u.id = :p and u.status = 'active'",
	).param("p", person).query(Long::class.javaObjectType).single() == 1L

	fun setupPending(): Boolean = jdbc.sql("select count(*) from server_installation where id = 1 and initialized_at is null")
		.query(Long::class.javaObjectType).single() == 1L

	/** Human identity only, even when a device/session belongs to an administrator. */
	fun requireAdministrator(authorization: String?): String {
		val person = requester.person(authorization, "서버 관리")
		if (!isAdministrator(person)) {
			sessions.audit("person", person, "permission.refused", "api", "server", mapOf("reason" to "server administrator required"))
			throw ForbiddenException("서버 관리자만 할 수 있다")
		}
		return person
	}

	fun view(): ServerAdministrationView = jdbc.sql(
		"""
		select s.org_id, o.name, o.owner_user_id, s.administrator_user_id, u.display_name, s.initialized_at
		  from server_installation s join organizations o on o.id = s.org_id
		  join users u on u.id = s.administrator_user_id where s.id = 1
		""".trimIndent(),
	).query { rs, _ ->
		ServerAdministrationView(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
			rs.getObject(6, OffsetDateTime::class.java), settings.mode.wire)
	}.single()

	/** OrgService calls this before changing status, so transfer cannot race with deactivation. */
	fun guardPersonStatus(person: String, nextStatus: String?) {
		val administrator = lockInstallation()
		if (nextStatus == "inactive" && person == administrator) {
			throw ConflictException("서버 관리자는 비활성화할 수 없다. 먼저 다른 구성원에게 서버 관리를 넘긴다")
		}
	}

	@Transactional
	fun initialize(person: String, body: InitializeServerIn): ServerAdministrationView {
		lockAndRequire(person)
		val orgName = name(body.organizationName)
		val adminName = name(body.administratorName)
		if (!setupPending()) throw ConflictException("최초 설정이 이미 끝났다. 조직 이름은 조직 화면에서 바꾼다")
		jdbc.sql("update organizations set name = :name where id = (select org_id from server_installation where id = 1)")
			.param("name", orgName).update()
		jdbc.sql("update users set display_name = :name where id = :p").param("name", adminName).param("p", person).update()
		jdbc.sql("update server_installation set initialized_at = now(), initialized_by = :p where id = 1").param("p", person).update()
		sessions.audit("person", person, "server.initialized", "org", view().organizationId,
			mapOf("organization_name" to orgName, "administrator_name" to adminName))
		return view()
	}

	/** The next administrator already has a working login. Organisation ownership/roles do not move. */
	@Transactional
	fun transfer(person: String, target: String): ServerAdministrationView {
		lockAndRequire(person)
		if (setupPending()) throw ConflictException("최초 설정을 먼저 끝낸다")
		if (target == person) throw ConflictException("이미 서버 관리자다")
		val active = jdbc.sql("select status from users where id = :p for update").param("p", target)
			.query(String::class.java).optional().orElse(null) == "active"
		val ready = jdbc.sql(
			"""
			select count(*) from memberships m join person_passwords p on p.user_id = m.user_id
			 where m.user_id = :p and m.org_id = (select org_id from server_installation where id = 1)
			""".trimIndent(),
		).param("p", target).query(Long::class.javaObjectType).single() == 1L
		if (!active || !ready) throw ConflictException("이 조직에서 비밀번호 설정을 마친 활성 구성원에게만 넘길 수 있다")
		jdbc.sql("update server_installation set administrator_user_id = :p where id = 1").param("p", target).update()
		sessions.audit("person", person, "server.administrator_transferred", "usr", target, mapOf("previous_administrator" to person))
		return view()
	}

	private fun lockInstallation(): String = jdbc.sql("select administrator_user_id from server_installation where id = 1 for update")
		.query(String::class.java).single()

	private fun lockAndRequire(person: String) {
		if (!settings.required) throw ConflictException("서버 관리를 설정하려면 로그인 모드(required)로 시작한다")
		val administrator = lockInstallation()
		if (administrator != person || !isAdministrator(person)) throw ForbiddenException("서버 관리자만 할 수 있다")
		val hasPassword = jdbc.sql("select count(*) from person_passwords where user_id = :p").param("p", person)
			.query(Long::class.javaObjectType).single() == 1L
		if (!hasPassword) throw ConflictException("관리자 비밀번호를 먼저 설정한다")
	}

	private fun name(value: String): String = value.trim().also {
		if (it.isEmpty() || it.length > 100) throw BadRequestException("이름은 1~100자로 입력한다")
	}
}

@RestController
@RequestMapping("/api/server")
class ServerAdministrationController(private val administration: ServerAdministration) {
	@GetMapping
	fun view(@RequestHeader("Authorization", required = false) authorization: String?): ServerAdministrationView {
		administration.requireAdministrator(authorization)
		return administration.view()
	}

	@PostMapping("/initialize")
	fun initialize(@RequestHeader("Authorization", required = false) authorization: String?, @RequestBody body: InitializeServerIn): ServerAdministrationView =
		administration.initialize(administration.requireAdministrator(authorization), body)

	@PutMapping("/administrator")
	fun transfer(@RequestHeader("Authorization", required = false) authorization: String?, @RequestBody body: TransferServerIn): ServerAdministrationView =
		administration.transfer(administration.requireAdministrator(authorization), body.userId)
}
