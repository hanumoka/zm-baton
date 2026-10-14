package io.github.hanumoka.zmbaton.server

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Risk path patterns, the sample draw and the human review state (contract v1 "사람에게 보낼 것을 고른다"). Pure. */
class RiskPathsTests {

	private fun yes(pattern: String, vararg paths: String) = paths.forEach { assertTrue(RiskPaths.matches(pattern, it), "$pattern should catch $it") }

	private fun no(pattern: String, vararg paths: String) = paths.forEach { assertFalse(RiskPaths.matches(pattern, it), "$pattern should not catch $it") }

	@Test
	fun `a name without a slash means that file at the repository root only`() {
		yes("AGENTS.md", "AGENTS.md")
		no("AGENTS.md", "docs/AGENTS.md", "AGENTS.md.bak", "XAGENTS.md", "agents.md")
	}

	@Test
	fun `a leading double star is any number of folders, none included`() {
		yes("**/AGENTS.md", "AGENTS.md", "apps/AGENTS.md", "a/b/c/AGENTS.md")
		no("**/AGENTS.md", "apps/AGENTS.md.txt", "apps/XAGENTS.md")
		yes("**/.gitignore", ".gitignore", "apps/server/.gitignore")
		yes("**/db/migration/**", "apps/server/src/main/resources/db/migration/V10__x.sql", "db/migration/V1.sql")
		no("**/db/migration/**", "apps/db/migrations/V1.sql", "db/migration")
	}

	@Test
	fun `a trailing double star is everything under the folder, the folder name itself not`() {
		yes(".github/**", ".github/workflows/ci.yml", ".github/pull_request_template.md")
		no(".github/**", ".github", ".githubx/a", "docs/.github/a")
		yes("docs/governance/decisions/**", "docs/governance/decisions/DEC-0102-x.md")
		no("docs/governance/decisions/**", "docs/governance/README.md")
	}

	@Test
	fun `a single star and a question mark stay inside one name`() {
		yes("deploy/*.yaml", "deploy/compose.yaml")
		no("deploy/*.yaml", "deploy/sub/compose.yaml")
		yes("**/*.Dockerfile", "server.Dockerfile", "deploy/compose/server.Dockerfile")
		no("**/*.Dockerfile", "deploy/Dockerfile")
		yes("**/docker-compose*.yml", "docker-compose.yml", "a/docker-compose.dev.yml")
		yes("V?.sql", "V1.sql")
		no("V?.sql", "V10.sql", "V/.sql")
		yes("docs/**/x.md", "docs/x.md", "docs/a/b/x.md")
	}

	@Test
	fun `case matters and dots are literal`() {
		no(".env", "xenv", ".ENV")
		yes("**/.env.*", ".env.local", "apps/.env.production")
		no("**/.env.*", ".env", "apps/x.env")
		no("LICENSE", "license")
	}

	@Test
	fun `the starting list catches policy, permission, security, CI, deployment and migration paths`() {
		val risky = listOf(
			"AGENTS.md", "apps/web/CLAUDE.md", ".claude/settings.json", ".github/workflows/ci.yml", "deploy/compose/compose.yaml",
			"apps/server/src/main/resources/db/migration/V10__human_review.sql", "apps/agent/.gitignore", "docs/governance/decisions/DEC-0102.md",
			"scripts/doctor.py", "registry/integrations.json", "src/auth/login.ts",
		)
		val plain = listOf("README.md", "apps/web/src/App.tsx", "docs/stage1/handoff.md", "registry/projects.json", "apps/server/build.gradle.kts")
		val caught = RiskPaths.match(RiskPaths.ORG_DEFAULT, risky + plain).flatMap { it.files }.toSet()
		assertEquals(risky.toSet(), caught)
		assertTrue(RiskPaths.ORG_DEFAULT.all { RiskPaths.problem(it) == null })
	}

	@Test
	fun `match lists each pattern that caught something with its files, in the list's order`() {
		val m = RiskPaths.match(listOf("docs/**", "AGENTS.md", "nothing/**"), listOf("docs/b.md", "AGENTS.md", "docs/a.md", "x.txt"))
		assertEquals(listOf(RiskPaths.Match("docs/**", listOf("docs/a.md", "docs/b.md")), RiskPaths.Match("AGENTS.md", listOf("AGENTS.md"))), m)
	}

	@Test
	fun `bad patterns are refused with the reason, blanks and repeats are dropped`() {
		for (bad in listOf("/etc/x", "C:/x", "~/x", "a\\b", "a b", "../x", "a/./b", "docs/", "a//b", "x".repeat(201))) {
			assertNotNull(RiskPaths.problem(bad), bad)
			assertFailsWith<BadRequestException> { RiskPaths.clean(listOf(bad)) }
		}
		assertEquals(listOf("a/**", "b.md"), RiskPaths.clean(listOf(" a/** ", "", "b.md", "a/**")))
		assertFailsWith<BadRequestException> { RiskPaths.clean((1..201).map { "f$it" }) }
	}

	@Test
	fun `a rejected sample suggests the one file, else the shared folder`() {
		assertEquals("src/a.kt", RiskPaths.suggest(listOf("src/a.kt")))
		assertEquals("src/api/**", RiskPaths.suggest(listOf("src/api/a.kt", "src/api/v1/b.kt")))
		assertEquals("src/**", RiskPaths.suggest(listOf("src/a/x.kt", "src/b/y.kt")))
		assertEquals("docs/**", RiskPaths.suggest(listOf("docs/a.md", "README.md")))
		assertEquals("README.md", RiskPaths.suggest(listOf("README.md", "LICENSE")))
		assertNull(RiskPaths.suggest(emptyList()))
	}

	@Test
	fun `the sample draw is the same for the same run and spreads evenly over many runs`() {
		val id = "run_" + Ids.uuidV7()
		assertEquals(Sampler.draw(id), Sampler.draw(id))
		val draws = (1..20_000).map { Sampler.draw("run_" + Ids.uuidV7()) }
		assertTrue(draws.all { it >= 0.0 && it < 1.0 })
		val picked = draws.count { Sampler.picked(it, Sampler.DEFAULT_RATE) }
		assertTrue(picked in 1700..2300, "one in ten of 20,000 should be about 2,000, was $picked")
		val buckets = draws.groupingBy { (it * 10).toInt() }.eachCount()
		assertTrue(buckets.values.all { it in 1700..2300 }, "tenths $buckets")
		assertFalse(Sampler.picked(0.0, 0.0))
		assertTrue(Sampler.picked(0.999, 1.0))
	}

	@Test
	fun `the human review state takes the first state that fits`() {
		assertEquals("required", ReviewRules.humanState(listOf("sample", "risk_path"), 1))
		assertEquals("sampled", ReviewRules.humanState(listOf("sample"), 3))
		assertEquals("reviewed", ReviewRules.humanState(emptyList(), 2))
		assertEquals("not_reviewed", ReviewRules.humanState(emptyList(), 0))
	}

	@Test
	fun `only a rejection of the newest result in result review moves the work item`() {
		assertEquals(ReviewRules.RejectEffect.RETURN, ReviewRules.rejectEffect("result_review", true, "risk_path"))
		assertEquals(ReviewRules.RejectEffect.RETURN, ReviewRules.rejectEffect("result_review", true, "sample"))
		assertEquals(ReviewRules.RejectEffect.RECORD, ReviewRules.rejectEffect("result_review", false, "risk_path"))
		assertEquals(ReviewRules.RejectEffect.RECORD, ReviewRules.rejectEffect("in_progress", true, "risk_path"))
		assertEquals(ReviewRules.RejectEffect.RECORD, ReviewRules.rejectEffect("plan_review", true, "risk_path"))
		assertEquals(ReviewRules.RejectEffect.RECORD, ReviewRules.rejectEffect("cancelled", true, "sample"))
		assertEquals(ReviewRules.RejectEffect.FIX_ITEM, ReviewRules.rejectEffect("done", true, "sample"))
		assertEquals(ReviewRules.RejectEffect.RECORD, ReviewRules.rejectEffect("done", true, "risk_path"))
	}
}
