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

package com.itsaky.androidide.tools.apkanalyzer

import java.io.File
import java.util.zip.ZipFile

/**
 * Local APK/AAB analyzer: structure, sizes, dex summary, decoded manifest summary, resources summary.
 * Comparison between two APKs. 100% local, no root, no internet.
 */
class ApkAnalyzer {

  data class EntryInfo(val path: String, val size: Long, val compressedSize: Long, val isDirectory: Boolean)
  data class DexSummary(val dexCount: Int, val totalUncompressed: Long)
  data class ManifestSummary(
    val packageName: String?,
    val versionCode: String?,
    val versionName: String?,
    val permissions: List<String>,
    val activities: List<String>,
    val services: List<String>
  )
  data class Report(
    val file: File,
    val totalSize: Long,
    val entries: List<EntryInfo>,
    val dex: DexSummary,
    val manifest: ManifestSummary?,
    val resourceTypes: Map<String, Int>
  )
  data class DiffItem(val path: String, val sizeA: Long, val sizeB: Long, val delta: Long)

  fun analyze(file: File): Report {
    require(file.exists()) { "Arquivo não encontrado: ${file.absolutePath}" }
    val entries = mutableListOf<EntryInfo>()
    var dexCount = 0
    var dexBytes = 0L
    val resourceTypes = mutableMapOf<String, Int>()
    var manifestBytes: ByteArray? = null

    ZipFile(file).use { zip ->
      val zipEntries = zip.entries()
      while (zipEntries.hasMoreElements()) {
        val e = zipEntries.nextElement()
        entries.add(
          EntryInfo(
            path = e.name,
            size = e.size,
            compressedSize = e.compressedSize,
            isDirectory = e.isDirectory
          )
        )
        when {
          e.name.matches(Regex("classes\\d*\\.dex")) -> {
            dexCount++
            dexBytes += e.size
          }
          e.name == "AndroidManifest.xml" -> {
            zip.getInputStream(e).use { manifestBytes = it.readBytes() }
          }
          e.name.startsWith("res/") -> {
            val type = e.name.removePrefix("res/").substringBefore('/')
            if (type.isNotBlank()) {
              resourceTypes[type] = (resourceTypes[type] ?: 0) + 1
            }
          }
        }
      }
    }

    entries.sortBy { it.path }
    val manifest = manifestBytes?.let { parseBinaryManifestLite(it) }

    return Report(
      file = file,
      totalSize = file.length(),
      entries = entries,
      dex = DexSummary(dexCount, dexBytes),
      manifest = manifest,
      resourceTypes = resourceTypes
    )
  }

  fun compare(a: File, b: File): List<DiffItem> {
    val reportA = analyze(a)
    val reportB = analyze(b)
    val mapA = reportA.entries.associate { it.path to it.size }
    val mapB = reportB.entries.associate { it.path to it.size }
    val allPaths = (mapA.keys + mapB.keys).sorted()
    return allPaths.map { path ->
      val sa = mapA[path] ?: 0L
      val sb = mapB[path] ?: 0L
      DiffItem(path, sa, sb, sb - sa)
    }.filter { it.delta != 0L || it.sizeA == 0L || it.sizeB == 0L }
  }

  /**
   * Lightweight binary XML peek for package / version / permissions.
   * Full AXML decode is complex; this extracts printable ASCII strings commonly present.
   */
  private fun parseBinaryManifestLite(data: ByteArray): ManifestSummary {
    val text = extractAsciiStrings(data)
    val packageName = text.find { it.contains('.') && it.matches(Regex("[a-zA-Z][a-zA-Z0-9_.]+")) && !it.startsWith("android.") }
    val permissions = text.filter { it.startsWith("android.permission.") || it.contains(".permission.") }.distinct()
    val activities = text.filter { it.contains("Activity") && it.contains('.') }.distinct()
    val services = text.filter { it.contains("Service") && it.contains('.') }.distinct()
    val versionName = text.find { it.matches(Regex("\\d+\\.\\d+[\\w.-]*")) }
    return ManifestSummary(
      packageName = packageName,
      versionCode = null,
      versionName = versionName,
      permissions = permissions,
      activities = activities,
      services = services
    )
  }

  private fun extractAsciiStrings(data: ByteArray, minLen: Int = 4): List<String> {
    val result = mutableListOf<String>()
    val sb = StringBuilder()
    for (b in data) {
      val c = b.toInt() and 0xFF
      if (c in 32..126) {
        sb.append(c.toChar())
      } else {
        if (sb.length >= minLen) result.add(sb.toString())
        sb.clear()
      }
    }
    if (sb.length >= minLen) result.add(sb.toString())
    return result
  }
}
