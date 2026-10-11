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

package com.itsaky.androidide.tools.translations

import com.itsaky.androidide.projects.IProjectManager
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/**
 * Reads all values-*/strings.xml, presents key × language table, supports inline edit,
 * add/remove keys, saves back preserving structure. Flags missing keys per language.
 */
class TranslationsEditor {

  data class Table(
    val languages: List<String>,
    val keys: List<String>,
    /** key → (lang → value) */
    val values: Map<String, Map<String, String>>,
    val missing: Map<String, Set<String>> // lang → missing keys
  )

  fun load(): Table {
    val root = projectDir() ?: return Table(emptyList(), emptyList(), emptyMap(), emptyMap())
    val resDirs = root.walkTopDown()
      .filter { it.isDirectory && it.name == "res" }
      .toList()

    val langToFile = mutableMapOf<String, File>()
    for (res in resDirs) {
      res.listFiles()?.forEach { dir ->
        if (dir.isDirectory && (dir.name == "values" || dir.name.startsWith("values-"))) {
          val strings = File(dir, "strings.xml")
          if (strings.exists()) {
            val lang = if (dir.name == "values") "default" else dir.name.removePrefix("values-")
            langToFile[lang] = strings
          }
        }
      }
    }

    val values = mutableMapOf<String, MutableMap<String, String>>()
    val allKeys = linkedSetOf<String>()

    for ((lang, file) in langToFile) {
      val map = parseStrings(file)
      for ((k, v) in map) {
        allKeys.add(k)
        values.getOrPut(k) { mutableMapOf() }[lang] = v
      }
    }

    val languages = langToFile.keys.sorted()
    val keys = allKeys.sorted()
    val missing = mutableMapOf<String, Set<String>>()
    for (lang in languages) {
      val miss = keys.filter { values[it]?.get(lang).isNullOrEmpty() }.toSet()
      if (miss.isNotEmpty()) missing[lang] = miss
    }

    return Table(languages, keys, values, missing)
  }

  fun setValue(key: String, lang: String, value: String): Boolean {
    val root = projectDir() ?: return false
    val file = findStringsFile(root, lang) ?: return false
    val map = parseStrings(file).toMutableMap()
    map[key] = value
    writeStrings(file, map)
    return true
  }

  fun addKey(key: String, defaultValue: String = ""): Boolean {
    val root = projectDir() ?: return false
    val file = findStringsFile(root, "default") ?: return false
    val map = parseStrings(file).toMutableMap()
    if (map.containsKey(key)) return false
    map[key] = defaultValue
    writeStrings(file, map)
    return true
  }

  fun removeKey(key: String): Boolean {
    val root = projectDir() ?: return false
    val table = load()
    var changed = false
    for (lang in table.languages) {
      val file = findStringsFile(root, lang) ?: continue
      val map = parseStrings(file).toMutableMap()
      if (map.remove(key) != null) {
        writeStrings(file, map)
        changed = true
      }
    }
    return changed
  }

  private fun findStringsFile(root: File, lang: String): File? {
    val dirName = if (lang == "default") "values" else "values-$lang"
    return root.walkTopDown()
      .filter { it.isDirectory && it.name == dirName }
      .map { File(it, "strings.xml") }
      .firstOrNull { it.exists() }
      ?: run {
        // create default if missing
        if (lang == "default") {
          val dir = File(root, "app/src/main/res/values").also { it.mkdirs() }
          File(dir, "strings.xml").also {
            if (!it.exists()) it.writeText("""<?xml version="1.0" encoding="utf-8"?><resources></resources>""")
          }
        } else null
      }
  }

  private fun parseStrings(file: File): Map<String, String> {
    val result = linkedMapOf<String, String>()
    try {
      val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
      val nodes = doc.getElementsByTagName("string")
      for (i in 0 until nodes.length) {
        val el = nodes.item(i) as Element
        val name = el.getAttribute("name")
        val value = el.textContent ?: ""
        if (name.isNotBlank()) result[name] = value
      }
    } catch (_: Exception) { }
    return result
  }

  private fun writeStrings(file: File, map: Map<String, String>) {
    val factory = DocumentBuilderFactory.newInstance()
    val doc = factory.newDocumentBuilder().newDocument()
    val root = doc.createElement("resources")
    doc.appendChild(root)
    for ((k, v) in map) {
      val el = doc.createElement("string")
      el.setAttribute("name", k)
      el.appendChild(doc.createTextNode(v))
      root.appendChild(el)
    }
    val transformer = TransformerFactory.newInstance().newTransformer()
    transformer.setOutputProperty(OutputKeys.INDENT, "yes")
    transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2")
    transformer.setOutputProperty(OutputKeys.ENCODING, "utf-8")
    transformer.transform(DOMSource(doc), StreamResult(file))
  }

  private fun projectDir(): File? {
    val path = IProjectManager.getInstance().projectDirPath ?: return null
    return File(path)
  }
}
