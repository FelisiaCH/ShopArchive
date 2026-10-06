package xyz.felismp.shoparchive.app.ui

import xyz.felismp.shoparchive.shared.ErrorCode
import xyz.felismp.shoparchive.shared.ErrorReasons
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The server sends keys, the app owns the words: every key and every code needs a sentence in all three languages. */
class RefusalWordsTest {
    private fun keysOf(dir: String): Map<String, String> {
        val text = File("src/commonMain/composeResources/$dir/strings.xml").readText()
        return Regex("""<string name="([a-z0-9_]+)">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL).findAll(text)
            .associate { it.groupValues[1] to it.groupValues[2] }
    }

    private val languages = listOf("values", "values-th", "values-lo").associateWith(::keysOf)

    @Test
    fun everyErrorCodeHasASentenceInEveryLanguage() {
        for (code in ErrorCode.entries) {
            val key = codeWords(code).key
            for ((dir, strings) in languages) assertTrue(!strings[key].isNullOrBlank(), "$code -> $key is missing or blank in $dir")
        }
    }

    @Test
    fun everyReasonKeyTheServerKnowsHasWords() {
        assertEquals(ErrorReasons.all.toSet(), REASON_WORDS.keys, "the table and ErrorReasons.all list the same keys")
        for ((reason, resource) in REASON_WORDS) {
            for ((dir, strings) in languages) assertTrue(!strings[resource.key].isNullOrBlank(), "$reason -> ${resource.key} is missing or blank in $dir")
        }
    }

    @Test
    fun aReasonThisAppDoesNotKnowFallsBackToTheCodeSentence() {
        assertEquals(codeWords(ErrorCode.CONFLICT), refusalWords(ErrorCode.CONFLICT, "from.a.newer.server"))
        assertEquals(codeWords(ErrorCode.CONFLICT), refusalWords(ErrorCode.CONFLICT, null))
        assertEquals(REASON_WORDS.getValue(ErrorReasons.PIN_SEQUENCE), refusalWords(ErrorCode.INVALID_REQUEST, ErrorReasons.PIN_SEQUENCE))
    }

    @Test
    fun theThreeStringFilesHaveTheSameKeys() {
        val english = languages.getValue("values").keys
        for ((dir, strings) in languages) {
            assertEquals(english - strings.keys, emptySet(), "missing in $dir")
            assertEquals(strings.keys - english, emptySet(), "only in $dir")
        }
    }

    @Test
    fun theSameFormatArgumentsAppearInEveryLanguage() {
        val english = languages.getValue("values")
        for ((dir, strings) in languages) {
            for ((key, text) in strings) {
                val args = Regex("""%\d[$]s""").findAll(text).map { it.value }.sorted().toList()
                val expected = Regex("""%\d[$]s""").findAll(english.getValue(key)).map { it.value }.sorted().toList()
                assertEquals(expected, args, "$key in $dir")
            }
        }
    }
}
