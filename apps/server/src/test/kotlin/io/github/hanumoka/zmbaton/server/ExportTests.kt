package io.github.hanumoka.zmbaton.server

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.core.env.Environment
import org.springframework.jdbc.core.simple.JdbcClient
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The export bundle (FN-1404, G13.3): provenance, every collection, the source of each copy, no secrets. */
@Import(TestcontainersConfiguration::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ExportTests @Autowired constructor(
	env: Environment,
	private val json: ObjectMapper,
	private val jdbc: JdbcClient,
) {
	private val base = "http://localhost:" + env.getRequiredProperty("local.server.port")
	private val http = HttpClient.newHttpClient()

	private fun call(method: String, path: String, body: String? = null, token: String? = null): HttpResponse<String> {
		val builder = HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", "application/json")
		if (token != null) builder.header("Authorization", "Bearer $token")
		val request = builder
			.method(method, body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody())
			.build()
		return http.send(request, HttpResponse.BodyHandlers.ofString())
	}

	private fun obj(body: String): Map<*, *> = json.readValue(body, Map::class.java)

	/** A device, a session that shares nothing, a work item made here and one imported from Git. */
	private data class Fixture(val sessionId: String, val token: String, val productItem: String, val gitRef: String, val repo: String)

	private fun fixture(): Fixture {
		val device = obj(call("POST", "/api/devices", """{"name":"export-pc"}""").body())["device_id"] as String
		val repo = "git:" + UUID.randomUUID().toString().replace("-", "")
		val registered = obj(
			call(
				"POST", "/api/sessions",
				"""{"protocol_version":2,"device_id":"$device","executor":"claude_code","executor_session_id":"${UUID.randomUUID()}",
				   "repository":{"repo_key":"$repo","prefix":"","branch":"main"}}""",
			).body(),
		)
		val product = obj(call("POST", "/api/work-items", """{"title":"made here","responsible_user_id":"usr_owner"}""").body())["id"] as String
		val ref = "TSK-" + (100000..999999).random()
		val import = json.writeValueAsString(
			mapOf(
				"repo_key" to repo, "apply" to true, "commit" to "0123456789abcdef0123456789abcdef01234567",
				"files" to listOf(
					mapOf("path" to "registry/tasks.json", "content" to """{"tasks":[{"id":"$ref","title":"from git","state":"open"}]}"""),
				),
			),
		)
		assertEquals(200, call("POST", "/api/imports/zm-docs", import).statusCode())
		return Fixture(registered["session_id"] as String, registered["token"] as String, product, ref, repo)
	}

	private fun export(): Pair<HttpResponse<String>, Map<*, *>> {
		val response = call("GET", "/api/export")
		assertEquals(200, response.statusCode(), response.body())
		return response to obj(response.body())
	}

	@Suppress("UNCHECKED_CAST")
	private fun records(bundle: Map<*, *>, name: String): List<Map<String, Any?>> =
		((bundle["collections"] as Map<*, *>)[name] as Map<*, *>)["records"] as List<Map<String, Any?>>

	@Test
	fun `the bundle carries provenance, every collection and where each copy came from`() {
		val f = fixture()
		val (response, bundle) = export()
		assertContains(response.headers().firstValue("Content-Disposition").orElse(""), "attachment; filename=\"zm-baton-export-")
		assertEquals("zm-baton.export", bundle["format"])
		assertEquals(1, bundle["format_version"])

		val provenance = bundle["provenance"] as Map<*, *>
		OffsetDateTime.parse(provenance["exported_at"] as String)
		assertEquals("usr_owner", provenance["exported_by"])
		val server = provenance["server"] as Map<*, *>
		assertEquals("zm-baton-server", server["name"])
		assertTrue((server["version"] as String).isNotBlank())
		val applied = jdbc.sql("select version from flyway_schema_history where success and version is not null order by installed_rank desc limit 1")
			.query(String::class.java).single()
		assertEquals(applied, (provenance["database"] as Map<*, *>)["migration_version"])
		assertEquals(listOf("token_hash"), (provenance["withheld_fields"] as Map<*, *>)["agent_sessions"])

		val collections = bundle["collections"] as Map<*, *>
		for (name in listOf("projects", "work_items", "decisions", "sessions", "inbox_items", "notices", "audit_log")) {
			val c = collections[name] as Map<*, *>
			assertEquals((c["records"] as List<*>).size, c["count"], name)
		}

		val product = records(bundle, "work_items").single { it["id"] == f.productItem }
		assertEquals(mapOf("of_truth" to "zm-baton"), product["source"])
		val copy = records(bundle, "work_items").single { it["external_ref"] == f.gitRef }
		assertEquals(
			mapOf(
				"of_truth" to "git", "repo_key" to f.repo, "path" to "registry/tasks.json",
				"commit" to "0123456789abcdef0123456789abcdef01234567", "external_ref" to f.gitRef,
			),
			copy["source"],
		)
		val session = records(bundle, "sessions").single { it["id"] == f.sessionId }
		assertFalse("token_hash" in session)
		assertNotNull(session["token_expires_at"])

		// The export itself is audited, with the counts it carried.
		val audited = jdbc.sql("select details::text from audit_log where action = 'export.downloaded' order by at desc limit 1")
			.query(String::class.java).single()
		assertContains(audited, "\"work_items\"")
	}

	@Test
	fun `no session token, token hash or secret-looking field is exported`() {
		val f = fixture()
		val (response, bundle) = export()
		assertFalse(response.body().contains(f.token), "the token")
		assertFalse(response.body().contains(SessionTokens.hash(f.token)), "the token hash")
		val secretName = Regex("(?i)(token|secret|password|passwd|credential|private_key|api_key)")
		// When a token was made or ends: times, not secrets. The device token itself is withheld (V14).
		val allowed = setOf("token_expires_at", "token_issued_at")
		val collections = bundle["collections"] as Map<*, *>
		for ((name, value) in collections) {
			for (record in (value as Map<*, *>)["records"] as List<*>) {
				for (key in (record as Map<*, *>).keys) {
					val k = key as String
					assertTrue(k in allowed || !secretName.containsMatchIn(k), "$name.$k looks like a secret; withhold it or allow it here")
				}
			}
		}
	}

	@Test
	fun `every table is exported or listed as not exported`() {
		val tables = jdbc.sql("select table_name from information_schema.tables where table_schema = 'public' and table_type = 'BASE TABLE'")
			.query(String::class.java).list().toSet()
		val covered = ExportService.COLLECTIONS.map { it.second }.toSet() + ExportService.NOT_EXPORTED.keys
		assertEquals(emptySet(), tables - covered, "a new table must be added to the export or to NOT_EXPORTED")
		assertEquals(emptySet(), covered - tables, "the export names a table that does not exist")
	}

	@Test
	fun `device repositories and delegations are exported, remotes without credentials`() {
		val device = obj(call("POST", "/api/devices", """{"name":"export-pc"}""").body())["device_id"] as String
		val repo = "git:" + UUID.randomUUID().toString().replace("-", "")
		val added = call(
			"POST", "/api/devices/$device/repositories",
			"""{"repo_key":"$repo","name":"demo","remote":"https://me:s3cret-value@github.com/me/demo.git?token=abc#x"}""",
		)
		assertEquals(200, added.statusCode(), added.body())
		val work = obj(call("POST", "/api/work-items", """{"title":"to export","responsible_user_id":"usr_owner"}""").body())["id"] as String
		val version = ((obj(call("GET", "/api/work-items/$work").body())["item"] as Map<*, *>)["version"] as Number).toLong()
		val delegated = call(
			"POST", "/api/work-items/$work/delegations",
			"""{"executor":"claude_code","device_id":"$device","repo_key":"$repo","version":$version}""",
		)
		assertEquals(201, delegated.statusCode(), delegated.body())

		val (response, bundle) = export()
		assertFalse(response.body().contains("s3cret-value"), "the remote's password")
		assertFalse(response.body().contains("token=abc"), "the remote's query")
		val repository = records(bundle, "device_repositories").single { it["device_id"] == device }
		assertEquals("https://github.com/me/demo.git", repository["remote"])
		val delegation = records(bundle, "delegations").single { it["work_item_id"] == work }
		assertEquals(repo, (delegation["scope"] as Map<*, *>)["repo_key"])
		val run = records(bundle, "runs").single { it["delegation_id"] == delegation["id"] }
		assertTrue((run["instruction"] as String).contains("to export"))
	}

	@Test
	fun `the export is a person action`() {
		val f = fixture()
		assertEquals(403, call("GET", "/api/export", token = f.token).statusCode())
	}
}
