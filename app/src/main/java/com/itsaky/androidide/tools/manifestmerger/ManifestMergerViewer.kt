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

package com.itsaky.androidide.tools.manifestmerger

import com.itsaky.androidide.projects.IProjectManager
import org.w3c.dom.Element
import java.io.File
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Reads app AndroidManifest + dependency manifests (from AARs), shows merged view
 * with origin of each element/permission/component.
 */
class ManifestMergerViewer {

  data class NodeOrigin(
    val tag: String,
    val name: String?,
    val origin: String, // "project" or library name
    val detail: String = ""
  )

  data class Report(
    val nodes: List<NodeOrigin>,
    val mergedXmlPreview: String
  )

  fun analyze(): Report {
    val root = projectDir() ?: return Report(emptyList(), "Nenhum projeto aberto.")
    val nodes = mutableListOf<NodeOrigin>()
    val projectManifest = findProjectManifest(root)
    if (projectManifest != null) {
      nodes += parseManifest(projectManifest, "project")
    }

    // Scan AARs in gradle caches / local libs
    val aars = findAars(root)
    for (aar in aars) {
      val libName = aar.nameWithoutExtension
      extractManifestFromAar(aar)?.let { tmp ->
        nodes += parseManifest(tmp, libName)
        tmp.delete()
      }
    }

    // Prefer intermediates merged manifest if present
    val merged = findMergedManifest(root)
    val preview = merged?.readText()?.take(8000)
      ?: projectManifest?.readText()?.take(8000)
      ?: ""

    return Report(nodes, preview)
  }

  private fun parseManifest(file: File, origin: String): List<NodeOrigin> {
    val result = mutableListOf<NodeOrigin>()
    try {
      val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
      val manifest = doc.documentElement ?: return result

      fun walk(el: Element) {
        val tag = el.tagName
        val name = el.getAttribute("android:name").ifBlank {
          el.getAttribute("package").ifBlank { null }
        }
        when (tag) {
          "uses-permission", "permission" ->
            result.add(NodeOrigin(tag, name, origin, "permission"))
          "activity", "service", "receiver", "provider" ->
            result.add(NodeOrigin(tag, name, origin, "component"))
          "application", "manifest" ->
            result.add(NodeOrigin(tag, name, origin))
        }
        val children = el.childNodes
        for (i in 0 until children.length) {
          val n = children.item(i)
          if (n is Element) walk(n)
        }
      }
      walk(manifest)
    } catch (_: Exception) { }
    return result
  }

  private fun extractManifestFromAar(aar: File): File? {
    return try {
      ZipFile(aar).use { zip ->
        val entry = zip.getEntry("AndroidManifest.xml") ?: return null
        val tmp = File.createTempFile("aar_manifest_", ".xml")
        zip.getInputStream(entry).use { input ->
          tmp.outputStream().use { input.copyTo(it) }
        }
        tmp
      }
    } catch (_: Exception) {
      null
    }
  }

  private fun findProjectManifest(root: File): File? {
    val candidates = listOf(
      "app/src/main/AndroidManifest.xml",
      "src/main/AndroidManifest.xml"
    )
    return candidates.map { File(root, it) }.firstOrNull { it.exists() }
  }

  private fun findMergedManifest(root: File): File? {
    return root.walkTopDown()
      .filter { it.name == "AndroidManifest.xml" && it.path.contains("merged_manifest") }
      .firstOrNull()
  }

  private fun findAars(root: File): List<File> {
    val results = mutableListOf<File>()
    // local libs
    File(root, "app/libs").takeIf { it.exists() }?.walkTopDown()
      ?.filter { it.extension == "aar" }
      ?.forEach { results.add(it) }
    // limited scan of gradle caches near project
    File(root, ".gradle").takeIf { it.exists() }?.walkTopDown()
      ?.filter { it.extension == "aar" }
      ?.take(30)
      ?.forEach { results.add(it) }
    return results
  }

  private fun projectDir(): File? {
    val path = IProjectManager.getInstance().projectDirPath ?: return null
    return File(path)
  }
}
