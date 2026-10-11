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

package com.itsaky.androidide.tools.assetstudio

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import com.itsaky.androidide.projects.IProjectManager
import java.io.File
import java.io.FileOutputStream

/**
 * Asset Studio: generate adaptive launcher icons from image / text,
 * write mipmap densities + anydpi-v26 XMLs into the open project's res/.
 * SVG → VectorDrawable import.
 */
class AssetStudio {

  data class IconResult(val success: Boolean, val message: String, val filesWritten: List<File> = emptyList())

  private val densities = mapOf(
    "mdpi" to 48,
    "hdpi" to 72,
    "xhdpi" to 96,
    "xxhdpi" to 144,
    "xxxhdpi" to 192
  )

  fun generateFromText(
    text: String,
    backgroundColor: Int = Color.parseColor("#3DDC84"),
    foregroundColor: Int = Color.WHITE
  ): IconResult {
    val root = projectRes() ?: return IconResult(false, "Projeto sem pasta res/.")
    val written = mutableListOf<File>()

    for ((density, size) in densities) {
      val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
      val canvas = Canvas(bmp)
      canvas.drawColor(backgroundColor)
      val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = foregroundColor
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
        textSize = size * 0.45f
      }
      val x = size / 2f
      val y = size / 2f - (paint.descent() + paint.ascent()) / 2
      canvas.drawText(text.take(2).uppercase(), x, y, paint)

      val dir = File(root, "mipmap-$density").also { it.mkdirs() }
      val file = File(dir, "ic_launcher.png")
      FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
      written.add(file)

      val round = File(dir, "ic_launcher_round.png")
      FileOutputStream(round).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
      written.add(round)
      bmp.recycle()
    }

    // Adaptive icon XML
    val anydpi = File(root, "mipmap-anydpi-v26").also { it.mkdirs() }
    val adaptive = File(anydpi, "ic_launcher.xml")
    adaptive.writeText(
      """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
  <background android:drawable="@color/ic_launcher_background"/>
  <foreground android:drawable="@mipmap/ic_launcher_foreground"/>
</adaptive-icon>
"""
    )
    written.add(adaptive)

    val values = File(root, "values").also { it.mkdirs() }
    val bgColor = File(values, "ic_launcher_background.xml")
    bgColor.writeText(
      """<?xml version="1.0" encoding="utf-8"?>
<resources>
  <color name="ic_launcher_background">#${"%06X".format(backgroundColor and 0xFFFFFF)}</color>
</resources>
"""
    )
    written.add(bgColor)

    return IconResult(true, "Ícones gerados em ${written.size} arquivos.", written)
  }

  fun generateFromBitmap(source: Bitmap, backgroundColor: Int = Color.WHITE): IconResult {
    val root = projectRes() ?: return IconResult(false, "Projeto sem pasta res/.")
    val written = mutableListOf<File>()
    for ((density, size) in densities) {
      val scaled = Bitmap.createScaledBitmap(source, size, size, true)
      val dir = File(root, "mipmap-$density").also { it.mkdirs() }
      val file = File(dir, "ic_launcher.png")
      FileOutputStream(file).use { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }
      written.add(file)
      if (scaled !== source) scaled.recycle()
    }
    return IconResult(true, "Ícones gerados a partir da imagem.", written)
  }

  /** Convert a simple SVG subset to VectorDrawable XML and save under res/drawable/. */
  fun importSvg(svgContent: String, name: String = "imported_vector"): IconResult {
    val root = projectRes() ?: return IconResult(false, "Projeto sem pasta res/.")
    val drawable = File(root, "drawable").also { it.mkdirs() }
    val out = File(drawable, "$name.xml")

    // Very simplified: extract path data if present
    val pathData = Regex("""pathData\s*=\s*["']([^"']+)["']""")
      .find(svgContent)?.groupValues?.get(1)
      ?: Regex("""d\s*=\s*["']([^"']+)["']""").find(svgContent)?.groupValues?.get(1)
      ?: return IconResult(false, "Não foi possível extrair path do SVG.")

    val width = Regex("""width\s*=\s*["'](\d+)""").find(svgContent)?.groupValues?.get(1) ?: "24"
    val height = Regex("""height\s*=\s*["'](\d+)""").find(svgContent)?.groupValues?.get(1) ?: "24"

    out.writeText(
      """<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
  android:width="${width}dp"
  android:height="${height}dp"
  android:viewportWidth="$width"
  android:viewportHeight="$height">
  <path
    android:fillColor="#FF000000"
    android:pathData="$pathData"/>
</vector>
"""
    )
    return IconResult(true, "VectorDrawable salvo: ${out.absolutePath}", listOf(out))
  }

  private fun projectRes(): File? {
    val path = IProjectManager.getInstance().projectDirPath ?: return null
    val candidates = listOf(
      File(path, "app/src/main/res"),
      File(path, "src/main/res")
    )
    return candidates.firstOrNull { it.exists() } ?: candidates.first().also { it.mkdirs() }
  }
}
