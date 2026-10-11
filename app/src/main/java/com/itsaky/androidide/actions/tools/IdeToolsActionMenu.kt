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

package com.itsaky.androidide.actions.tools

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.itsaky.androidide.actions.ActionData
import com.itsaky.androidide.actions.ActionItem
import com.itsaky.androidide.actions.ActionMenu
import com.itsaky.androidide.actions.EditorActivityAction
import com.itsaky.androidide.activities.tools.IdeToolsActivity
import com.itsaky.androidide.resources.R

/**
 * Menu entry that opens the IDE Tools hub (APK Analyzer, Asset Studio, AAB, etc.).
 */
class IdeToolsActionMenu(context: Context, override val order: Int) : EditorActivityAction(), ActionMenu {

  override val id: String = "ide.editor.tools.menu"
  override val children: MutableSet<ActionItem> = mutableSetOf()

  init {
    label = "Tools"
    icon = ContextCompat.getDrawable(context, R.drawable.ic_bash_commands)
  }

  override fun prepare(data: ActionData) {
    super.prepare(data)
    visible = true
    enabled = true
  }

  override suspend fun execAction(data: ActionData): Boolean {
    val activity = data.getActivity() ?: return false
    activity.startActivity(Intent(activity, IdeToolsActivity::class.java))
    return true
  }
}
