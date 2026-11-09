package app.picacgmd.window

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** New Compose windows must explicitly inherit the live screenshot-protection setting. */
class SecureWindowGuardTest {
    @Test
    fun everyAppAndReaderWindowDeclaresSecurityProperties() {
        val root = generateSequence(File(".").canonicalFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        val sourceRoots = listOf("app/src/main/kotlin", "feature/reader/src/main/kotlin").map { File(root, it) }
        assertTrue(sourceRoots.all { it.isDirectory }, "App and reader source roots must exist")
        val violations = sourceRoots.flatMap { directory ->
            directory.walkTopDown().filter { it.isFile && it.extension == "kt" }.sortedBy { it.path }.flatMap { file ->
                missingProperties(file.readText()).map { call ->
                    "${file.relativeTo(root).invariantSeparatorsPath}:${call.line}: ${call.name} needs properties = or securePolicy ="
                }
            }.toList()
        }
        assertTrue(violations.isEmpty(), violations.joinToString("\n"))
    }

    @Test
    fun detectsAllSeparateWindowKinds() {
        val names = listOf("AlertDialog", "BasicAlertDialog", "Dialog", "ModalBottomSheet", "DropdownMenu", "Popup", "DatePickerDialog")
        val source = names.joinToString("\n") { "$it(onDismissRequest = {}) {}" }
        assertEquals(names, missingProperties(source).map { it.name })
        assertEquals((1..names.size).toList(), missingProperties(source).map { it.line })
        assertTrue(missingProperties(names.joinToString("\n") { "$it(properties = secureProperties()) {}" }).isEmpty())
        assertTrue(missingProperties("Popup(securePolicy = secureWindowPolicy()) {}").isEmpty())
    }

    @Test
    fun nestedWindowPropertiesDoNotProtectTheOuterWindow() {
        val source = """
            AlertDialog(
                onDismissRequest = {},
                text = { DropdownMenu(properties = securePopupProperties()) {} },
            )
        """.trimIndent()
        assertEquals(listOf(WindowCall("AlertDialog", 1)), missingProperties(source))
    }

    @Test
    fun ignoresDefinitionsImportsCommentsStringsAndExposedMenus() {
        val tripleQuote = "\"\"\""
        val source = """
            import androidx.compose.ui.window.Dialog
            fun Dialog(content: () -> Unit) {}
            fun <T> Popup(value: T) {}
            fun Scope.DropdownMenu(content: () -> Unit) {}
            // AlertDialog()
            /* BasicAlertDialog() /* Popup() */ */
            val text = "ModalBottomSheet(\")\")"
            val raw = ${tripleQuote}DatePickerDialog()${tripleQuote}
            val paren = '('
            ExposedDropdownMenu(expanded = true, onDismissRequest = {}) {}
        """.trimIndent()
        assertTrue(missingProperties(source).isEmpty())
    }

    @Test
    fun matchesArgumentsAcrossNestedCallsAndQuotedParentheses() {
        val source = """
            Dialog(
                properties = secureDialogProperties(dismissOnBackPress = isEnabled()),
                onDismissRequest = { notify("(") /* ) */ },
            ) {}
            Popup(onDismissRequest = { notify(")") }) {}
        """.trimIndent()
        assertEquals(listOf(WindowCall("Popup", 5)), missingProperties(source))
    }

    @Test
    fun permitsInheritanceOnlyAtExplicitlyAnnotatedCallSites() {
        val source = """
            // secure-window: inherits
            Dialog(onDismissRequest = {}) {}
            Popup(onDismissRequest = {}) {} // secure-window: inherits
            Dialog(onDismissRequest = {}) {}
        """.trimIndent()
        assertEquals(listOf(WindowCall("Dialog", 4)), missingProperties(source))
    }

    private data class WindowCall(val name: String, val line: Int)

    private val windowCall = Regex("\\b(AlertDialog|BasicAlertDialog|Dialog|ModalBottomSheet|DropdownMenu|Popup|DatePickerDialog)\\s*\\(")
    private val definitionPrefix = Regex("\\bfun\\s+(?:<[^>]*>\\s*)?(?:[\\w.<>?]+\\.)?$")
    private val securityArgument = Regex("\\b(properties|securePolicy)\\s*=")

    private fun missingProperties(source: String): List<WindowCall> {
        val code = maskNonCode(source)
        return windowCall.findAll(code).mapNotNull { match ->
            val start = match.range.first
            val lineStart = code.lastIndexOf('\n', start - 1) + 1
            val prefix = code.substring(lineStart, start).trimStart()
            if (prefix.startsWith("import ") || definitionPrefix.containsMatchIn(code.substring(0, start))) return@mapNotNull null
            val open = match.range.last
            var close = open + 1
            var depth = 1
            while (close < code.length && depth > 0) {
                when (code[close]) {
                    '(' -> depth++
                    ')' -> depth--
                }
                close++
            }
            check(depth == 0) { "Unclosed ${match.groupValues[1]} argument list" }
            val arguments = code.substring(open + 1, close - 1)
            var nested = 0
            val topLevelArguments = arguments.map { char ->
                when (char) {
                    '(', '{', '[' -> { nested++; ' ' }
                    ')', '}', ']' -> { nested--; ' ' }
                    else -> if (nested == 0) char else ' '
                }
            }.joinToString("")
            val lineEnd = source.indexOf('\n', start).let { if (it < 0) source.length else it }
            val previousStart = if (lineStart == 0) 0 else source.lastIndexOf('\n', lineStart - 2) + 1
            // Pass-through wrappers may annotate the call line or the immediately preceding line.
            val inherits = source.substring(previousStart, lineStart).trim() == "// secure-window: inherits" ||
                source.substring(lineStart, lineEnd).substringAfter("//", "").trim() == "secure-window: inherits"
            if (securityArgument.containsMatchIn(topLevelArguments) || inherits) null
            else WindowCall(match.groupValues[1], code.take(start).count { it == '\n' } + 1)
        }.toList()
    }

    /** Keep offsets and line numbers while hiding literals and nested Kotlin comments. */
    private fun maskNonCode(source: String): String {
        val code = source.toCharArray()
        var index = 0
        while (index < source.length) {
            val start = index
            when {
                source.startsWith("//", index) -> {
                    index = source.indexOf('\n', index).let { if (it < 0) source.length else it }
                }
                source.startsWith("/*", index) -> {
                    index += 2
                    var depth = 1
                    while (index < source.length && depth > 0) {
                        when {
                            source.startsWith("/*", index) -> { depth++; index += 2 }
                            source.startsWith("*/", index) -> { depth--; index += 2 }
                            else -> index++
                        }
                    }
                }
                source.startsWith("\"\"\"", index) -> {
                    val end = source.indexOf("\"\"\"", index + 3)
                    index = if (end < 0) source.length else end + 3
                }
                source[index] == '"' || source[index] == '\'' -> {
                    val quote = source[index++]
                    while (index < source.length) {
                        when (source[index++]) {
                            '\\' -> if (index < source.length) index++
                            quote -> break
                        }
                    }
                }
                else -> { index++; continue }
            }
            for (position in start until index) if (code[position] != '\n') code[position] = ' '
        }
        return code.concatToString()
    }
}
