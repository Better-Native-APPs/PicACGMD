package app.picacgmd.architecture

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

internal class DaoBoundaryTest {
    @Test
    fun onlyTheApplicationAssemblyPointMayReferenceDatabaseDaos() {
        val sources = File("src/main/kotlin")
        assertTrue(sources.isDirectory, "app Kotlin source directory was not found")
        val qualifiedDao = Regex("""\bapp\.picacgmd\.core\.data\.db\.[A-Za-z_]\w*Dao\b""")
        val wildcardImport = Regex("""(?m)^\s*import\s+app\.picacgmd\.core\.data\.db\.\*\s*$""")
        val daoIdentifier = Regex("""\b[A-Z]\w*Dao\b""")
        val databaseDao = Regex("""\bdatabase\s*(?:\.|\?\.)\s*[A-Za-z_]\w*Dao\s*\(""")
        val violations = sources.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filterNot { it.relativeTo(sources).invariantSeparatorsPath == "app/picacgmd/PicaApplication.kt" }
            .flatMap { file ->
                val source = file.readText()
                val matches = qualifiedDao.findAll(source) + databaseDao.findAll(source) +
                    if (wildcardImport.containsMatchIn(source)) daoIdentifier.findAll(source) else emptySequence()
                matches.map { match ->
                    val line = source.take(match.range.first).count { it == '\n' } + 1
                    "${file.relativeTo(sources).invariantSeparatorsPath}:$line: ${match.value}"
                }
            }
            .sorted()
            .toList()

        assertTrue(violations.isEmpty(), "DAO access outside the application assembly point:\n${violations.joinToString("\n")}")
    }
}
