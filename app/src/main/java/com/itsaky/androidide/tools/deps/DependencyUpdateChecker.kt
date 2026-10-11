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

package com.itsaky.androidide.tools.deps

import com.itsaky.androidide.projects.IProjectManager
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * Reads dependencies from build.gradle / libs.versions.toml and checks Maven Central / Google Maven
 * for newer versions. Update applies only after user confirmation.
 */
class DependencyUpdateChecker {

  data class Dependency(
    val group: String,
    val name: String,
    val currentVersion: String,
    val sourceFile: File,
    val declarationLine: String
  )

  data class Update(
    val dependency: Dependency,
    val latestVersion: String
  )

  private val client = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(20, TimeUnit.SECONDS)
    .build()

  fun findDeclaredDependencies(): List<Dependency> {
    val root = projectDir() ?: return emptyList()
    val results = mutableListOf<Dependency>()
    val gradleFiles = root.walkTopDown()
      .filter { it.isFile && (it.name.endsWith(".gradle") || it.name.endsWith(".gradle.kts") || it.name == "libs.versions.toml") }
      .filter { !it.path.contains("/build/") }
      .toList()

    val coordPattern = Pattern.compile(
      """["']([a-zA-Z0-9._-]+):([a-zA-Z0-9._-]+):([0-9][a-zA-Z0-9._+-]*)["']"""
    )
    val tomlPattern = Pattern.compile(
      """([a-zA-Z0-9_-]+)\s*=\s*\{\s*module\s*=\s*"([^"]+):([^"]+)"\s*,\s*version\s*=\s*"([^"]+)"\s*\}"""
    )
    val versionRef = Pattern.compile(
      """version\s*=\s*"(\d[^"]*)""""
    )

    for (file in gradleFiles) {
      val lines = file.readLines()
      for (line in lines) {
        val m = coordPattern.matcher(line)
        if (m.find()) {
          results.add(
            Dependency(
              group = m.group(1),
              name = m.group(2),
              currentVersion = m.group(3),
              sourceFile = file,
              declarationLine = line.trim()
            )
          )
        }
        val tm = tomlPattern.matcher(line)
        if (tm.find()) {
          results.add(
            Dependency(
              group = tm.group(2),
              name = tm.group(3),
              currentVersion = tm.group(4),
              sourceFile = file,
              declarationLine = line.trim()
            )
          )
        }
      }
    }
    return results.distinctBy { "${it.group}:${it.name}:${it.currentVersion}" }
  }

  fun checkUpdates(deps: List<Dependency> = findDeclaredDependencies()): List<Update> {
    val updates = mutableListOf<Update>()
    for (dep in deps) {
      val latest = fetchLatestVersion(dep.group, dep.name) ?: continue
      if (isNewer(latest, dep.currentVersion)) {
        updates.add(Update(dep, latest))
      }
    }
    return updates
  }

  /** Apply update to the source file after user confirmation. */
  fun applyUpdate(update: Update): Boolean {
    val file = update.dependency.sourceFile
    if (!file.exists()) return false
    val old = "${update.dependency.group}:${update.dependency.name}:${update.dependency.currentVersion}"
    val new = "${update.dependency.group}:${update.dependency.name}:${update.latestVersion}"
    val text = file.readText()
    if (!text.contains(old)) {
      // try version-only replace in same line context
      val replaced = text.replace(update.dependency.currentVersion, update.latestVersion)
      if (replaced == text) return false
      file.writeText(replaced)
      return true
    }
    file.writeText(text.replace(old, new))
    return true
  }

  private fun fetchLatestVersion(group: String, name: String): String? {
    val groupPath = group.replace('.', '/')
    // Maven Central
    val urls = listOf(
      "https://repo1.maven.org/maven2/$groupPath/$name/maven-metadata.xml",
      "https://dl.google.com/dl/android/maven2/$groupPath/$name/maven-metadata.xml"
    )
    for (url in urls) {
      try {
        val req = Request.Builder().url(url).get().build()
        client.newCall(req).execute().use { resp ->
          if (!resp.isSuccessful) return@use
          val body = resp.body?.string().orEmpty()
          val release = Regex("<release>([^<]+)</release>").find(body)?.groupValues?.get(1)
          val latest = Regex("<latest>([^<]+)</latest>").find(body)?.groupValues?.get(1)
          return release ?: latest
        }
      } catch (_: Exception) {
        // try next
      }
    }
    return null
  }

  private fun isNewer(candidate: String, current: String): Boolean {
    fun parts(v: String) = v.split('.', '-', '+').mapNotNull { it.toIntOrNull() ?: 0 }
    val a = parts(candidate)
    val b = parts(current)
    val len = maxOf(a.size, b.size)
    for (i in 0 until len) {
      val ai = a.getOrElse(i) { 0 }
      val bi = b.getOrElse(i) { 0 }
      if (ai != bi) return ai > bi
    }
    return false
  }

  private fun projectDir(): File? {
    val path = IProjectManager.getInstance().projectDirPath ?: return null
    return File(path)
  }
}
