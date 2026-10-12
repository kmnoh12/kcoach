package dev.kyungmin.kcoach.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * App copy is written in 해요체, with noun-style labels and buttons. This scans every
 * Korean string literal in the apps and the core and fails on 반말 or 합쇼체 endings,
 * so the tone cannot drift back as screens change.
 */
class KoreanToneTest {
    private val roots = listOf("src/main/kotlin", "../phone/src/main/kotlin", "../wear/src/main/kotlin")

    private val literal = Regex(""""((?:[^"\\]|\\.)*[가-힣](?:[^"\\]|\\.)*)"""")

    private val formal = Regex("(습니다|합니다|입니다|십시오|하시겠습니까|ㅂ니다)")

    /** Casual endings. Each is a whole sentence ending, so nouns that happen to end in 지/해/어 are safe. */
    private val casual = Regex(
        "(했다|였다|있다|없다|된다|한다|이다|려나|같아|[았었했웠랐렸봤췄썼갔왔줬쳤졌켰꿨뒀]어|있어|없어|않아|아니야|이야|거야|할까|할래|해줘|해봐|하자|하네|이네|잖아|돼|줘|봐|" +
            "(수행|확인|허용|선택|입력|유지|시작|종료|추가|삭제|연결|실행|사용|설정|지원|준비|저장|적용|확정|표시|진행|중지)해|" +
            "올려|내려|맞춰|열어|잡아|바꿔|기다려|눌러|골라|알려|찾아|넣어)" +
            "\\s*[.?!]?\\s*$",
    )

    private fun sentences(text: String): List<String> =
        text.replace("\\n", "\n").split('\n', '.', '?', '!', '·', '→').map { it.trim() }.filter { it.any { c -> c in '가'..'힣' } }

    @Test
    fun `copy follows the house tone`() {
        val violations = mutableListOf<String>()
        roots.map(::File).filter { it.exists() }.forEach { root ->
            root.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
                file.readLines().forEachIndexed { index, line ->
                    if (line.trimStart().startsWith("//") || line.trimStart().startsWith("*")) return@forEachIndexed
                    literal.findAll(line).forEach { match ->
                        val text = match.groupValues[1]
                        val bad = formal.containsMatchIn(text) || sentences(text).any { casual.containsMatchIn(it) }
                        if (bad) violations += "${file.name}:${index + 1}  ${text.take(70)}"
                    }
                }
            }
        }
        assertTrue(violations.isEmpty(), "말투 규칙을 벗어난 문구 ${violations.size}개:\n" + violations.joinToString("\n"))
    }
}
