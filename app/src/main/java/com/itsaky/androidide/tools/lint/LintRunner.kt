/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.itsaky.androidide.tools.lint

import com.itsaky.androidide.projects.IProjectManager
import java.io.File

/**
 * Runs Android Lint (when the lint JAR is available on-device) and parses results.
 * Falls back to a lightweight static scan of common issues when the full lint tool is absent.
 */
class LintRunner(
  private val javaRunner: (jar: File, args: List<String>) -> Int = { _, _ -> -1 }
) {

  enum class Severity { ERROR, WARNING, INFO }

  data class Issue(
    val id: String,
    val severity: Severity,
    val message: String,
    val file: String?,
    val line: Int?
  )

  data class Report(
    val issues: List<Issue>,
    val source: String // "lint" or "fallback"
  )

  fun run(
    minSeverity: Severity = Severity.WARNING,
    categories: Set<String> = emptySet()
  ): Report {
    val root = projectDir() ?: return Report(emptyList(), "none")

    val lintJar = findLintJar()
    if (lintJar != null) {
      val outXml = File(root, "build/reports/lint-results.xml")
      outXml.parentFile?.mkdirs()
      val code = javaRunner(
        lintJar,
        listOf(
          "-Wall",
          "--xml", outXml.absolutePath,
          root.absolutePath
        )
      )
      if (code == 0 && outXml.exists()) {
        return Report(parseLintXml(outXml).filterBy(minSeverity, categories), "lint")
      }
    }

    // Fallback: lightweight scans
    return Report(fallbackScan(root).filterBy(minSeverity, categories), "fallback")
  }

  private fun List<Issue>.filterBy(min: Severity, categories: Set<String>): List<Issue> {
    val order = listOf(Severity.INFO, Severity.WARNING, Severity.ERROR)
    val minIdx = order.indexOf(min)
    return filter { order.indexOf(it.severity) >= minIdx }
      .filter { categories.isEmpty() || it.id in categories }
  }

  private fun parseLintXml(file: File): List<Issue> {
    val text = file.readText()
    val issues = mutableListOf<Issue>()
    val issueRegex = Regex(
      """<issue[^>]*id="([^"]+)"[^>]*severity="([^"]+)"[^>]*message="([^"]+)"[^>]*>.*?<location[^>]*file="([^"]+)"[^>]*(?:line="(\d+)")?""",
      setOf(RegexOption.DOT_MATCHES_ALL)
    )
    for (m in issueRegex.findAll(text)) {
      val sev = when (m.groupValues[2].lowercase()) {
        "error", "fatal" -> Severity.ERROR
        "warning" -> Severity.WARNING
        else -> Severity.INFO
      }
      issues.add(
        Issue(
          id = m.groupValues[1],
          severity = sev,
          message = m.groupValues[3]
            .replace("&quot;", "\"")
            .replace("&lt;", "<")
            .replace("&gt;", ">"),
          file = m.groupValues[4],
          line = m.groupValues[5].toIntOrNull()
        )
      )
    }
    return issues
  }

  private fun fallbackScan(root: File): List<Issue> {
    val issues = mutableListOf<Issue>()
    root.walkTopDown()
      .filter { it.isFile && (it.extension in setOf("java", "kt", "xml")) }
      .filter { !it.path.contains("/build/") }
      .forEach { file ->
        val lines = runCatching { file.readLines() }.getOrNull() ?: return@forEach
        lines.forEachIndexed { idx, line ->
          if (line.contains("Log.d(") || line.contains("System.out.println")) {
            issues.add(
              Issue("LogDebug", Severity.INFO, "Debug log left in code", file.absolutePath, idx + 1)
            )
          }
          if (line.contains("TODO(") || line.contains("// TODO")) {
            issues.add(
              Issue("Todo", Severity.INFO, "TODO comment", file.absolutePath, idx + 1)
            )
          }
          if (file.extension == "xml" && line.contains("hardcoded") ) {
            // placeholder
          }
          if (Regex("""["'][A-Z][a-z]+ [a-z]+""").containsMatchIn(line) && file.path.contains("/res/layout")) {
            issues.add(
              Issue("HardcodedText", Severity.WARNING, "Possible hardcoded text in layout", file.absolutePath, idx + 1)
            )
          }
        }
      }
    return issues
  }

  private fun findLintJar(): File? {
    val candidates = listOf(
      File("/data/data/com.itsaky.androidide/files/tools/lint.jar"),
      projectDir()?.let { File(it, "tools/lint.jar") }
    )
    return candidates.filterNotNull().firstOrNull { it.exists() }
  }

  private fun projectDir(): File? {
    val path = IProjectManager.getInstance().projectDirPath ?: return null
    return File(path)
  }
}
