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

package com.itsaky.androidide.actions.ai

import android.content.Context
import androidx.core.content.ContextCompat
import com.itsaky.androidide.actions.ActionData
import com.itsaky.androidide.actions.EditorActivityAction
import com.itsaky.androidide.resources.R
import com.itsaky.androidide.ui.aichat.AiChatFloatingWindow

/**
 * Toolbar / menu action that opens the floating AI Chat window.
 * Designed to feel native to AndroidIDE (same action pattern as Find, Run, etc.).
 *
 * @author AndroidIDE AI Chat
 */
class AiChatAction : EditorActivityAction() {

  override var requiresUIThread: Boolean = true
  override var order: Int = 0

  constructor(context: Context, order: Int) : this() {
    this.label = context.getString(R.string.menu_ai_chat)
    this.icon = ContextCompat.getDrawable(context, R.drawable.ic_ai_chat)
    this.order = order
  }

  override val id: String = "ide.editor.ai.chat"

  override fun prepare(data: ActionData) {
    super.prepare(data)
    val activity = data.getActivity()
    if (activity == null) {
      visible = false
      enabled = false
      return
    }
    visible = true
    enabled = true
  }

  override suspend fun execAction(data: ActionData): Boolean {
    val activity = data.getActivity() ?: return false
    AiChatFloatingWindow.show(activity)
    return true
  }
}
