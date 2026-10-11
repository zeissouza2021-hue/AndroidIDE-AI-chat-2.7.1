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

package com.itsaky.androidide.tools.devicefiles

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Browse /data/data/<package>/ via `run-as` (no root).
 * Only works for debuggable apps. Clear warning when not debuggable.
 */
class DeviceFileExplorer(
  private val shell: (List<String>) -> String = ::defaultShell
) {

  data class Entry(val name: String, val isDir: Boolean, val size: Long = -1)

  data class ListResult(
    val ok: Boolean,
    val entries: List<Entry> = emptyList(),
    val error: String? = null,
    val notDebuggable: Boolean = false
  )

  fun list(packageName: String, relativePath: String = ""): ListResult {
    val path = "/data/data/$packageName/" + relativePath.trimStart('/')
    val output = shell(listOf("run-as", packageName, "ls", "-la", path))
    if (output.contains("not debuggable", ignoreCase = true) ||
      output.contains("Package") && output.contains("is not debuggable")
    ) {
      return ListResult(
        ok = false,
        notDebuggable = true,
        error = "O app '$packageName' não é debuggable. " +
          "Device File Explorer só funciona com apps debuggable (android:debuggable=true). Sem root."
      )
    }
    if (output.contains("run-as:") && output.contains("error")) {
      return ListResult(ok = false, error = output.trim())
    }
    val entries = parseLs(output)
    return ListResult(ok = true, entries = entries)
  }

  fun readText(packageName: String, relativePath: String): String {
    val path = "/data/data/$packageName/" + relativePath.trimStart('/')
    return shell(listOf("run-as", packageName, "cat", path))
  }

  fun delete(packageName: String, relativePath: String): Boolean {
    val path = "/data/data/$packageName/" + relativePath.trimStart('/')
    val out = shell(listOf("run-as", packageName, "rm", "-rf", path))
    return !out.contains("error", ignoreCase = true)
  }

  fun pull(packageName: String, relativePath: String, dest: File): Boolean {
    // run-as cat → write locally
    val content = readText(packageName, relativePath)
    if (content.startsWith("run-as:")) return false
    dest.parentFile?.mkdirs()
    dest.writeText(content)
    return true
  }

  private fun parseLs(output: String): List<Entry> {
    val result = mutableListOf<Entry>()
    for (line in output.lines()) {
      val parts = line.trim().split(Regex("\\s+"))
      if (parts.size < 6) continue
      if (parts[0].startsWith("total")) continue
      val name = parts.last()
      if (name == "." || name == "..") continue
      val isDir = parts[0].startsWith("d")
      val size = parts.getOrNull(4)?.toLongOrNull() ?: -1
      result.add(Entry(name, isDir, size))
    }
    return result.sortedWith(compareBy({ !it.isDir }, { it.name }))
  }

  companion object {
    private fun defaultShell(args: List<String>): String {
      return try {
        val pb = ProcessBuilder(args)
          .redirectErrorStream(true)
        val proc = pb.start()
        val text = BufferedReader(InputStreamReader(proc.inputStream)).readText()
        proc.waitFor()
        text
      } catch (e: Exception) {
        "error: ${e.message}"
      }
    }
  }
}
