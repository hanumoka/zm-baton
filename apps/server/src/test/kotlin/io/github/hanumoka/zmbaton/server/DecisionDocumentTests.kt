package io.github.hanumoka.zmbaton.server

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Reading a zm-docs decision file, without a database. */
class DecisionDocumentTests {

	private fun doc(front: List<String>, body: List<String>) =
		DecisionDocument.parse((listOf("---") + front + listOf("---", "", "# DEC-0001: 제목") + body).joinToString("\r\n"))!!

	@Test
	fun `front matter takes inline lists, block lists and values with colons`() {
		val d = doc(
			listOf("id: DEC-0001", "title: 질문: 콜론 포함", "projects:", "  - PRJ-000", "  - PRJ-009", "supersedes: null"),
			listOf("## 결정", "한다."),
		)
		assertEquals("질문: 콜론 포함", d.front["title"])
		assertEquals(listOf("PRJ-000", "PRJ-009"), DecisionDocument.list(d.front["projects"]))
		assertEquals(listOf("PRJ-001", "PRJ-002"), DecisionDocument.list("[PRJ-001, \"PRJ-002\"]"))
		assertEquals(emptyList(), DecisionDocument.list("[]"))
		assertNull(DecisionDocument.ref(d.front["supersedes"]))
		assertEquals("DEC-0042", DecisionDocument.ref("DEC-0042"))
	}

	@Test
	fun `the decision is the section named so, or the first one ending in it`() {
		assertEquals("한다.", doc(listOf("id: DEC-0001"), listOf("## 맥락", "배경", "## 결정", "한다.")).choice())
		val older = doc(listOf("id: DEC-0001"), listOf("## 결정 주도 역할", "소유자", "## 판단 기준과 결정", "이렇게 정했다."))
		assertEquals("이렇게 정했다.", older.choice())
		assertNull(doc(listOf("id: DEC-0001"), listOf("## 맥락", "배경")).choice())
	}

	@Test
	fun `a file without front matter is not a decision`() {
		assertNull(DecisionDocument.parse("# 제목만 있다\n\n## 결정\n한다."))
		assertNull(DecisionDocument.parse("---\nid: DEC-0001\n# 닫는 줄이 없다"))
	}
}
