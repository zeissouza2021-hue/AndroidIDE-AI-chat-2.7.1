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

package com.itsaky.androidide.actions.build

import android.content.Context
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.itsaky.androidide.actions.ActionData
import com.itsaky.androidide.actions.EditorActivityAction
import com.itsaky.androidide.resources.R
import com.itsaky.androidide.tools.aab.AabBuilder
import com.itsaky.androidide.tools.signing.SigningConfigStore
import com.itsaky.androidide.utils.flashError
import com.itsaky.androidide.utils.flashSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Build > Generate App Bundle (.aab) with debug/release variants.
 */
class GenerateAabAction(context: Context, override val order: Int) : EditorActivityAction() {

  override val id: String = "ide.editor.build.generateAab"
  override var requiresUIThread: Boolean = true

  init {
    label = context.getString(R.string.menu_generate_aab)
    icon = ContextCompat.getDrawable(context, R.drawable.ic_android)
  }

  override fun prepare(data: ActionData) {
    super.prepare(data)
    visible = data.getActivity() != null
    enabled = visible
  }

  override suspend fun execAction(data: ActionData): Boolean {
    val activity = data.getActivity() ?: return false
    val variants = arrayOf("debug", "release")
    AlertDialog.Builder(activity)
      .setTitle(R.string.menu_generate_aab)
      .setItems(variants) { _, which ->
        val variant = if (which == 0) AabBuilder.Variant.DEBUG else AabBuilder.Variant.RELEASE
        actionScope.launch {
          val signing = SigningConfigStore.getInstance(activity).getSelected()
          val result = withContext(Dispatchers.IO) {
            AabBuilder().build(variant, signing)
          }
          withContext(Dispatchers.Main) {
            if (result.success) {
              activity.flashSuccess(result.message)
              // Offer APKs + install
              AlertDialog.Builder(activity)
                .setTitle("AAB pronto")
                .setMessage(result.message + "\n\nCom Play App Signing, a chave usada é a upload key.")
                .setPositiveButton("Gerar APKs") { _, _ ->
                  actionScope.launch {
                    val aab = result.aabFile ?: return@launch
                    val apks = java.io.File(aab.parentFile, "app.apks")
                    val r = withContext(Dispatchers.IO) {
                      AabBuilder().buildApksFromBundle(aab, apks, signing)
                    }
                    withContext(Dispatchers.Main) {
                      if (r.success) {
                        activity.flashSuccess(r.message)
                        AlertDialog.Builder(activity)
                          .setMessage("Instalar no dispositivo?")
                          .setPositiveButton("Instalar") { _, _ ->
                            actionScope.launch {
                              val ir = withContext(Dispatchers.IO) {
                                AabBuilder().installApks(apks)
                              }
                              withContext(Dispatchers.Main) {
                                if (ir.success) activity.flashSuccess(ir.message)
                                else activity.flashError(ir.message)
                              }
                            }
                          }
                          .setNegativeButton(android.R.string.cancel, null)
                          .show()
                      } else activity.flashError(r.message)
                    }
                  }
                }
                .setNegativeButton(android.R.string.ok, null)
                .show()
            } else {
              activity.flashError(result.message)
            }
          }
        }
      }
      .setNegativeButton(android.R.string.cancel, null)
      .show()
    return true
  }
}
