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

package com.itsaky.androidide.tools.themebuilder

import com.itsaky.androidide.projects.IProjectManager
import java.io.File
import kotlin.math.roundToInt

/**
 * Generates Material 3 light/dark color schemes from a seed color.
 * Uses a simplified HCT-inspired tonal palette (pure Java, no external deps required).
 * Export: colors.xml + themes.xml (Views) or Color.kt (Compose).
 */
class MaterialThemeBuilder {

  data class Palette(
    val seed: Int,
    val light: Map<String, Int>,
    val dark: Map<String, Int>
  )

  fun generate(seedArgb: Int): Palette {
    val light = tonalScheme(seedArgb, dark = false)
    val dark = tonalScheme(seedArgb, dark = true)
    return Palette(seedArgb, light, dark)
  }

  fun exportToProject(palette: Palette): String {
    val root = projectDir() ?: return "Nenhum projeto aberto."
    val isCompose = detectCompose(root)
    return if (isCompose) {
      exportCompose(root, palette)
    } else {
      exportViews(root, palette)
    }
  }

  private fun exportViews(root: File, palette: Palette): String {
    val values = File(root, "app/src/main/res/values").also { it.mkdirs() }
    val valuesNight = File(root, "app/src/main/res/values-night").also { it.mkdirs() }

    File(values, "colors_m3.xml").writeText(colorsXml(palette.light))
    File(valuesNight, "colors_m3.xml").writeText(colorsXml(palette.dark))
    File(values, "themes_m3.xml").writeText(themesXml(light = true))
    File(valuesNight, "themes_m3.xml").writeText(themesXml(light = false))
    return "Exportado: colors_m3.xml + themes_m3.xml (values / values-night)"
  }

  private fun exportCompose(root: File, palette: Palette): String {
    val dir = findKotlinPackageDir(root) ?: File(root, "app/src/main/java").also { it.mkdirs() }
    val file = File(dir, "ColorM3.kt")
    file.writeText(composeColorKt(palette))
    return "Exportado: ${file.absolutePath}"
  }

  private fun colorsXml(map: Map<String, Int>): String {
    val body = map.entries.joinToString("\n") { (k, v) ->
      """  <color name="$k">#${"%08X".format(v)}</color>"""
    }
    return """<?xml version="1.0" encoding="utf-8"?>
<resources>
$body
</resources>
"""
  }

  private fun themesXml(light: Boolean): String {
    val parent = if (light) "Theme.Material3.Light.NoActionBar" else "Theme.Material3.Dark.NoActionBar"
    return """<?xml version="1.0" encoding="utf-8"?>
<resources>
  <style name="Theme.App.Material3" parent="$parent">
    <item name="colorPrimary">@color/md_theme_primary</item>
    <item name="colorOnPrimary">@color/md_theme_onPrimary</item>
    <item name="colorPrimaryContainer">@color/md_theme_primaryContainer</item>
    <item name="colorOnPrimaryContainer">@color/md_theme_onPrimaryContainer</item>
    <item name="colorSecondary">@color/md_theme_secondary</item>
    <item name="colorOnSecondary">@color/md_theme_onSecondary</item>
    <item name="colorSurface">@color/md_theme_surface</item>
    <item name="colorOnSurface">@color/md_theme_onSurface</item>
    <item name="android:statusBarColor">@color/md_theme_primary</item>
  </style>
</resources>
"""
  }

  private fun composeColorKt(palette: Palette): String {
    fun entries(map: Map<String, Int>) = map.entries.joinToString("\n") { (k, v) ->
      """  val $k = Color(0x${"%08X".format(v)})"""
    }
    return """
package com.example.ui.theme

import androidx.compose.ui.graphics.Color

object LightColors {
${entries(palette.light)}
}

object DarkColors {
${entries(palette.dark)}
}
""".trimIndent()
  }

  private fun tonalScheme(seed: Int, dark: Boolean): Map<String, Int> {
    val r = (seed shr 16) and 0xFF
    val g = (seed shr 8) and 0xFF
    val b = seed and 0xFF
    fun tone(f: Float): Int {
      val nr = (r * f).roundToInt().coerceIn(0, 255)
      val ng = (g * f).roundToInt().coerceIn(0, 255)
      val nb = (b * f).roundToInt().coerceIn(0, 255)
      return (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
    }
    return if (!dark) {
      mapOf(
        "md_theme_primary" to tone(0.85f),
        "md_theme_onPrimary" to 0xFFFFFFFF.toInt(),
        "md_theme_primaryContainer" to tone(0.35f),
        "md_theme_onPrimaryContainer" to tone(0.15f),
        "md_theme_secondary" to tone(0.55f),
        "md_theme_onSecondary" to 0xFFFFFFFF.toInt(),
        "md_theme_surface" to 0xFFFFFBFE.toInt(),
        "md_theme_onSurface" to 0xFF1C1B1F.toInt()
      )
    } else {
      mapOf(
        "md_theme_primary" to tone(0.55f),
        "md_theme_onPrimary" to tone(0.15f),
        "md_theme_primaryContainer" to tone(0.25f),
        "md_theme_onPrimaryContainer" to tone(0.90f),
        "md_theme_secondary" to tone(0.45f),
        "md_theme_onSecondary" to tone(0.15f),
        "md_theme_surface" to 0xFF1C1B1F.toInt(),
        "md_theme_onSurface" to 0xFFE6E1E5.toInt()
      )
    }
  }

  private fun detectCompose(root: File): Boolean {
    return root.walkTopDown()
      .filter { it.name.endsWith(".gradle") || it.name.endsWith(".gradle.kts") || it.name.endsWith(".toml") }
      .any { runCatching { it.readText().contains("androidx.compose") }.getOrDefault(false) }
  }

  private fun findKotlinPackageDir(root: File): File? {
    val java = File(root, "app/src/main/java")
    if (!java.exists()) return null
    return java.walkTopDown().firstOrNull { it.isDirectory && it.listFiles()?.any { f -> f.extension == "kt" } == true }
  }

  private fun projectDir(): File? {
    val path = IProjectManager.getInstance().projectDirPath ?: return null
    return File(path)
  }
}
