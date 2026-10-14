package io.github.hanumoka.zmbaton.server

import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * Risk path patterns (contract v1 "위험 목록을 판정한다" 1): globs from the repository root, with
 * the meaning Git pathspec `:(top,glob)` gives them, which is also how the review standard matches
 * its own path table. Pure, so every pattern's meaning is tested on its own.
 *
 * - `**` + `/` is zero or more folders; `/` + `**` at the end is everything under the folder.
 * - `*` is part of one name and never crosses `/`; `?` is one character of a name.
 * - A pattern without `/` (`AGENTS.md`) means that name at the repository root only.
 * - Case matters.
 */
object RiskPaths {

	const val PATTERN_MAX = 200
	const val PATTERNS_MAX = 200

	/**
	 * The organisation's starting list (작성자가 정한 시작값): policy, permission and security paths,
	 * CI, deployment and database migrations in any repository, plus the paths the owner chose for
	 * zm-docs (zm-docs-risk-paths.md, 2026-10-10), which stage 1 runs through zm-baton. An
	 * organisation replaces this list in its settings; a project replaces it with its own.
	 */
	val ORG_DEFAULT: List<String> = listOf(
		// Policy: the rules people and AI follow.
		"AGENTS.md", "CLAUDE.md", "**/AGENTS.md", "**/CLAUDE.md", "SECURITY.md", "LICENSE", "NOTICE",
		"docs/governance/decisions/**", "docs/standards/**",
		// Permission: AI tool settings and skills.
		".claude/**", ".codex/**", ".grok/**", ".agents/skills/**",
		// Security: checks, ignore rules, secrets files, authentication and permission code.
		"**/.gitignore", "**/.env", "**/.env.*", "**/auth/**", "**/security/**", "**/permission/**",
		"scripts/**", "registry/schemas/**", "registry/integrations.json",
		// CI and deployment.
		".github/**", ".gitlab-ci.yml", "Jenkinsfile", "deploy/**", "**/Dockerfile", "**/*.Dockerfile",
		"**/compose.yaml", "**/docker-compose*.yml",
		// Database structure.
		"**/db/migration/**", "**/migrations/**",
	)

	private val ABSOLUTE = Regex("^([A-Za-z]:|/|~)")
	private val SPACE = Regex("\\s")

	/** Why [pattern] cannot be a risk path pattern, or null when it can. */
	fun problem(pattern: String): String? = when {
		pattern.isEmpty() -> "빈 패턴"
		pattern.length > PATTERN_MAX -> "$PATTERN_MAX 자를 넘는다"
		SPACE.containsMatchIn(pattern) -> "공백이 있다"
		'\\' in pattern -> "역슬래시(\\) 대신 /를 쓴다"
		ABSOLUTE.containsMatchIn(pattern) -> "저장소 뿌리 기준 경로여야 한다. 절대 경로처럼 보인다"
		pattern.split('/').any { it == ".." || it == "." } -> ". 이나 .. 자리가 있다"
		pattern.endsWith("/") -> "/로 끝난다. 폴더 아래 전부는 폴더/** 로 쓴다"
		"//" in pattern -> "//가 있다"
		else -> null
	}

	/** The patterns trimmed, without blanks and repeats, in the order given; refused with the reason when one is bad. */
	fun clean(patterns: List<String>): List<String> {
		val out = patterns.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
		if (out.size > PATTERNS_MAX) throw BadRequestException("위험 경로 패턴은 ${PATTERNS_MAX}개까지다")
		out.firstNotNullOfOrNull { p -> problem(p)?.let { "$p: $it" } }?.let { throw BadRequestException("위험 경로 패턴이 맞지 않다. $it") }
		return out
	}

	/** The pattern as a regular expression over a whole path from the repository root. */
	fun regex(pattern: String): Regex {
		val b = StringBuilder("^")
		var i = 0
		while (i < pattern.length) {
			val c = pattern[i]
			when {
				pattern.startsWith("**/", i) -> {
					b.append("(?:.*/)?")
					i += 3
				}
				pattern.startsWith("/**", i) && i + 3 == pattern.length -> {
					b.append("/.*")
					i += 3
				}
				pattern.startsWith("**", i) -> {
					b.append(".*")
					i += 2
				}
				c == '*' -> {
					b.append("[^/]*")
					i++
				}
				c == '?' -> {
					b.append("[^/]")
					i++
				}
				else -> {
					b.append(Regex.escape(c.toString()))
					i++
				}
			}
		}
		return Regex(b.append('$').toString())
	}

	fun matches(pattern: String, path: String): Boolean = regex(pattern).matches(path)

	/** One pattern and the files it caught. */
	data class Match(val pattern: String, val files: List<String>)

	/** The patterns that catch at least one of [files], in the list's order, each with its files. */
	fun match(patterns: List<String>, files: Collection<String>): List<Match> =
		patterns.mapNotNull { p ->
			val re = regex(p)
			files.filter { re.matches(it) }.sorted().takeIf { it.isNotEmpty() }?.let { Match(p, it) }
		}

	/**
	 * A pattern to put forward when a sample was rejected: the one file, else everything under the
	 * deepest folder all files share, else everything under the first file's top folder.
	 */
	fun suggest(files: List<String>): String? {
		if (files.isEmpty()) return null
		if (files.size == 1) return files.single()
		val folders = files.map { it.split('/').dropLast(1) }
		var common = folders.first()
		for (f in folders.drop(1)) common = common.zip(f).takeWhile { (a, b) -> a == b }.map { it.first }
		return when {
			common.isNotEmpty() -> common.joinToString("/") + "/**"
			folders.first().isNotEmpty() -> folders.first().first() + "/**"
			else -> files.first()
		}
	}
}

/**
 * Picking samples (contract v1 "표본을 고른다"). The draw is reproducible: SHA-256 of the rule's
 * version and the run id, read as a number in [0, 1). Anyone can recompute a past draw from the
 * run id, which is how the record shows the samples were drawn evenly (4: the draw and the rule's
 * version are kept). The run id is the server's random UUIDv7, so an executor cannot pick it.
 */
object Sampler {
	const val RULE = "sample.v1"
	/** Starting values (contract: one in ten, five a day per person). An organisation sets its own. */
	const val DEFAULT_RATE = 0.1
	const val DEFAULT_DAILY_CAP = 5

	fun seed(runId: String) = "$RULE|$runId"

	fun draw(runId: String): Double {
		val digest = MessageDigest.getInstance("SHA-256").digest(seed(runId).toByteArray(Charsets.UTF_8))
		// The top 53 bits make an exactly representable double in [0, 1).
		return (ByteBuffer.wrap(digest).long ushr 11).toDouble() / (1L shl 53).toDouble()
	}

	fun picked(draw: Double, rate: Double) = draw < rate
}

/**
 * The human review state of a result (contract v1 "사람 검토 상태와 자동 검사 상태를 따로 남긴다")
 * and what a rejection does to its work item ("반려하면 작업이 어떻게 되나"). Pure.
 */
object ReviewRules {
	/** The first state that fits, in the contract's order. [open] and [closed] are the reasons of requests not replaced. */
	fun humanState(openReasons: Collection<String>, closedCount: Int): String = when {
		openReasons.any { it != "sample" } -> "required"
		openReasons.isNotEmpty() -> "sampled"
		closedCount > 0 -> "reviewed"
		else -> "not_reviewed"
	}

	/** What rejecting a request does. */
	enum class RejectEffect {
		/** The work item goes back from result review to in progress; the reason is what to fix. */
		RETURN,
		/** A done work item's sample was rejected: a new work item fixes it. */
		FIX_ITEM,
		/** Recorded on the result; the work item stays where it is. */
		RECORD,
	}

	/**
	 * Only one case changes the stage: the work item is in result review and the request is about
	 * its newest run. A done work item's rejected sample gets a fixing work item. Anything else is
	 * recorded: the work item moved or was cancelled, a newer run exists, or the result came back
	 * already (a second rejection of the same result finds the work item in progress).
	 */
	fun rejectEffect(stage: String?, isNewestRun: Boolean, reason: String): RejectEffect = when {
		stage == "result_review" && isNewestRun -> RejectEffect.RETURN
		stage == "done" && reason == "sample" -> RejectEffect.FIX_ITEM
		else -> RejectEffect.RECORD
	}
}
