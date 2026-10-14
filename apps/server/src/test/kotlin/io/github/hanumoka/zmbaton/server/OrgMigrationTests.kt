package io.github.hanumoka.zmbaton.server

import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * V13 ties what is already there to the one-person organisation: on an empty database (the test
 * context's own) and on a database that holds stage-1 rows written before it (a schema of its own,
 * migrated to V11, filled, then migrated on). Row counts stay; every row reaches the organisation and
 * the owner.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
class OrgMigrationTests @Autowired constructor(private val jdbc: JdbcClient, private val dataSource: DataSource) {

	@Test
	fun `an empty database starts as the owner's one-person organisation`() {
		assertEquals("usr_owner", jdbc.sql("select owner_user_id from organizations where id = 'org_owner'").query(String::class.java).single())
		val roles = jdbc.sql("select array_to_string(roles, ',') from memberships where org_id = 'org_owner' and user_id = 'usr_owner'")
			.query(String::class.java).single()
		assertEquals("requester,assignee,reviewer,approver,operator,master_admin", roles)
		assertEquals("usr_owner", jdbc.sql("select user_id from managers where id = 'mgr_owner'").query(String::class.java).single())
	}

	@Test
	fun `rows written before V13 keep their counts and all reach the organisation and the owner`() {
		val hikari = dataSource as HikariDataSource
		val schema = "mig13_" + UUID.randomUUID().toString().replace("-", "").take(12)
		val url = hikari.jdbcUrl + (if ('?' in hikari.jdbcUrl) "&" else "?") + "currentSchema=$schema"
		val ds = DriverManagerDataSource(url, hikari.username, hikari.password)
		jdbc.sql("create schema $schema").update()
		try {
			flyway(ds, schema, "11")
			val db = JdbcClient.create(ds)
			fill(db)
			val before = counts(db, schema)
			flyway(ds, schema, null)
			val after = counts(db, schema)
			// Only users grows: the person a row named but who was never a user (usr_ghost below).
			for ((table, n) in before) assertEquals(if (table == "users") n + 1 else n, after[table], "rows of $table")
			assertTrue(after.getValue("work_items") >= 3 && after.getValue("agent_sessions") >= 2)

			fun one(sql: String): Long = db.sql(sql).query(Long::class.javaObjectType).single()
			assertEquals(0L, one("select count(*) from work_items where org_id <> 'org_owner' or visibility <> 'org'"))
			assertEquals(0L, one("select count(*) from work_items w where not exists (select 1 from users u where u.id = w.responsible_user_id)"))
			assertEquals(0L, one("select count(*) from devices where org_id <> 'org_owner'"))
			assertEquals(0L, one("select count(*) from agent_sessions where org_id <> 'org_owner' or manager_id is null"))
			assertEquals(0L, one("select count(*) from agent_sessions where user_id = 'usr_owner' and manager_id <> 'mgr_owner'"))
			assertEquals(0L, one("select count(*) from decisions where owner_user_id <> 'usr_owner' or visibility <> 'org'"))
			assertEquals(0L, one("select count(*) from documents where visibility <> 'org'"))
			assertEquals(1L, one("select count(*) from agent_sessions where current_work_visibility = 'personal'"), "the unshared summary stays its person's")
			assertEquals("usr_owner", db.sql("select owner_user_id from organizations where id = 'org_owner'").query(String::class.java).single())
			assertEquals(6L, one("select cardinality(roles) from memberships where org_id = 'org_owner' and user_id = 'usr_owner'"))
			// A person a row named but who was never a user stays as an inactive member with no role.
			assertEquals("inactive", db.sql("select status from users where id = 'usr_ghost'").query(String::class.java).single())
			assertEquals(0L, one("select cardinality(roles) from memberships where user_id = 'usr_ghost'"))
			assertEquals(1L, one("select count(*) from managers where id = 'mgr_ghost'"))
			// The organisation's repositories: the device's name, and the imported one as zm-docs.
			assertEquals("demo", db.sql("select name from repositories where repo_key = 'git:devicerepo'").query(String::class.java).single())
			assertEquals("zm-docs", db.sql("select name from repositories where repo_key = 'git:zmdocs'").query(String::class.java).single())
		} finally {
			jdbc.sql("drop schema $schema cascade").update()
		}
	}

	private fun flyway(ds: DataSource, schema: String, target: String?) {
		val config = Flyway.configure().dataSource(ds).schemas(schema).defaultSchema(schema).locations("classpath:db/migration")
		if (target != null) config.target(MigrationVersion.fromVersion(target))
		config.load().migrate()
	}

	/** Every table's row count, but Flyway's own. */
	private fun counts(db: JdbcClient, schema: String): Map<String, Long> =
		db.sql("select table_name from information_schema.tables where table_schema = :s and table_type = 'BASE TABLE' and table_name <> 'flyway_schema_history'")
			.param("s", schema).query(String::class.java).list().filterNotNull().associateWith { t ->
				db.sql("select count(*) from $t").query(Long::class.javaObjectType).single()
			}

	/** Stage-1 rows as V11 left them: the owner's work, decisions, documents, a device, sessions and what they made. */
	private fun fill(db: JdbcClient) {
		val statements = listOf(
			"insert into devices (id, user_id, name) values ('dev_1', 'usr_owner', 'owner-pc')",
			"insert into device_repositories (device_id, repo_key, name) values ('dev_1', 'git:devicerepo', 'demo')",
			"""insert into agent_sessions (id, user_id, device_id, executor, executor_session_id, repo_key, current_work, share_current_work, token_hash, token_expires_at)
			   values ('ses_1', 'usr_owner', 'dev_1', 'claude_code', 'e1', 'git:devicerepo', '공유한 일', true, 'h1', now() + interval '1 hour'),
			          ('ses_2', 'usr_owner', 'dev_1', 'codex', 'e2', 'git:devicerepo', '나만 아는 일', false, 'h2', now() - interval '1 hour')""",
			"insert into session_events (event_id, session_id, local_seq, kind, payload) values ('evt_1', 'ses_1', 1, 'current_work', '{\"summary\":\"공유한 일\",\"share\":true}')",
			"insert into projects (id, org_id, external_ref, name) values ('prj_1', 'org_owner', 'PRJ-901', 'zm-baton')",
			"""insert into work_items (id, title, stage, responsible_user_id, executor_kind, executor_id, project_id, source_of_truth, git_ref, external_ref)
			   values ('wrk_1', '여기서 만든 작업', 'ready', 'usr_owner', 'human', 'usr_owner', 'prj_1', 'product', null, null),
			          ('wrk_2', '가져온 작업', 'in_progress', 'usr_owner', 'human', 'usr_owner', 'prj_1', 'git', '{"repo_key":"git:zmdocs","path":"registry/tasks.json"}', 'TSK-901'),
			          ('wrk_3', '이름만 남은 사람의 작업', 'draft', 'usr_ghost', null, null, null, 'product', null, null)""",
			"""insert into decisions (id, org_id, external_ref, question, status, source_of_truth, git_ref)
			   values ('dcs_1', 'org_owner', 'DEC-0901', '가져온 결정', 'accepted', 'git', '{"repo_key":"git:zmdocs","path":"docs/governance/decisions/DEC-0901-x.md"}')""",
			"""insert into decisions (id, org_id, number, question, status, source_of_truth, roles)
			   values ('dcs_2', 'org_owner', 9001, '여기서 만든 결정', 'proposed', 'product', '{"driver":"usr_owner","approver":"usr_owner"}')""",
			"insert into documents (id, org_id, project_id, title, source_of_truth, owner_user_id) values ('doc_1', 'org_owner', 'prj_1', '문서', 'product', 'usr_owner')",
			"insert into document_versions (id, doc_id, number, body_markdown, created_by) values ('dvr_1', 'doc_1', 1, '본문', 'usr_owner')",
			"""insert into inbox_items (id, user_id, bucket, kind, source_kind, source_id, summary, notify)
			   values ('inb_1', 'usr_owner', 2, 'session_waiting', 'ses', 'ses_1', '기다림', 'digest')""",
			"insert into notices (id, kind, evidence, dedupe_key, recipients) values ('ntc_1', 'work_conflict', '{}', 'k1', array['usr_owner'])",
			"""insert into delegations (id, work_item_id, granted_by_user_id, grantee_kind, grantee_id, device_id, account_id, scope, expires_at)
			   values ('dlg_1', 'wrk_1', 'usr_owner', 'manager', 'mgr_owner', 'dev_1', 'acc_owner_login', '{"executor":"claude_code","repo_key":"git:devicerepo"}', now())""",
			"insert into runs (id, work_item_id, state, end_reason, executor, delegation_id) values ('run_1', 'wrk_1', 'ended', 'submitted', 'claude_code', 'dlg_1')",
			"""insert into questions (id, org_id, work_item_id, origin, kind, body, asked_by_kind, asked_by_id, routed_to_kind, routed_to_id, routed_role, due_at)
			   values ('qst_1', 'org_owner', 'wrk_1', 'person', 'fact', '질문', 'person', 'usr_owner', 'person', 'usr_owner', 'work_owner', now())""",
			"""insert into approvals (id, target_kind, target_id, target_version, policy_ref, approver_kind, approver_id, decision)
			   values ('apv_1', 'decision', 'dcs_2', 0, 'stage1.owner_approves', 'human', 'usr_owner', 'rejected')""",
			"insert into audit_log (id, actor_kind, actor_id, on_behalf_of_user_id, action) values ('aud_1', 'person', 'usr_owner', 'usr_owner', 'test.before_v13')",
		)
		for (s in statements) db.sql(s.trimIndent()).update()
	}
}
