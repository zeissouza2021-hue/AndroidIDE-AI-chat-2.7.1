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

package com.itsaky.androidide.ui.aichat

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.itsaky.androidide.activities.AiProvidersActivity
import com.itsaky.androidide.ai.data.AiProvider
import com.itsaky.androidide.ai.data.AiProviderRepository
import com.itsaky.androidide.ai.ui.AiChatViewModel
import com.itsaky.androidide.ai.ui.ChatUiMessage
import com.itsaky.androidide.resources.R
import com.itsaky.androidide.utils.resolveAttr

/**
 * Non-modal floating AI chat window inside the app.
 * Drag, resize, minimize, maximize, close. Full chat UI with provider selector,
 * Chat/Agent mode, attachments (gated by capabilities), cancel.
 */
class AiChatFloatingWindow private constructor(
  private val activity: Activity
) {

  private val prefs: SharedPreferences =
    activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
  private val repo = AiProviderRepository.getInstance(activity)
  private val viewModel = AiChatViewModel().also { it.init(repo) }
  private val mainHandler = Handler(Looper.getMainLooper())

  private var container: FrameLayout? = null
  private var windowView: View? = null
  private var messageList: ListView? = null
  private var statusTv: TextView? = null
  private var providerSpinner: Spinner? = null
  private var modeChatBtn: Button? = null
  private var modeAgentBtn: Button? = null
  private var inputEt: EditText? = null
  private var isMinimized = false
  private var isMaximized = false
  private var savedX = 0
  private var savedY = 0
  private var savedW = 0
  private var savedH = 0

  private val messageAdapter by lazy {
    object : ArrayAdapter<ChatUiMessage>(activity, 0, mutableListOf()) {
      override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val msg = getItem(position)!!
        val density = activity.resources.displayMetrics.density
        val tv = (convertView as? TextView) ?: TextView(activity).apply {
          setPadding((12 * density).toInt(), (8 * density).toInt(), (12 * density).toInt(), (8 * density).toInt())
          textSize = 13f
        }
        tv.text = when (msg.role) {
          ChatUiMessage.Role.USER -> "Você: ${msg.content}"
          ChatUiMessage.Role.ASSISTANT -> msg.content.ifEmpty { "…" }
          ChatUiMessage.Role.STATUS -> msg.content
          ChatUiMessage.Role.SYSTEM -> msg.content
        }
        tv.typeface = if (msg.role == ChatUiMessage.Role.USER) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        tv.setTextColor(
          when (msg.role) {
            ChatUiMessage.Role.SYSTEM, ChatUiMessage.Role.STATUS ->
              activity.resolveAttr(R.attr.colorOnSurfaceVariant)
            else -> activity.resolveAttr(R.attr.colorOnSurface)
          }
        )
        return tv
      }
    }
  }

  @SuppressLint("ClickableViewAccessibility")
  fun show() {
    if (container != null) {
      windowView?.visibility = View.VISIBLE
      refreshProviders()
      return
    }

    val decor = activity.window.decorView as ViewGroup
    val root = FrameLayout(activity).also { container = it }
    root.isClickable = false
    decor.addView(
      root,
      FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT
      )
    )

    val density = activity.resources.displayMetrics.density
    val defaultW = (340 * density).toInt()
    val defaultH = (480 * density).toInt()
    val x = prefs.getInt(KEY_X, (40 * density).toInt())
    val y = prefs.getInt(KEY_Y, (80 * density).toInt())
    val w = prefs.getInt(KEY_W, defaultW)
    val h = prefs.getInt(KEY_H, defaultH)

    val card = buildCard(density)
    windowView = card
    val lp = FrameLayout.LayoutParams(w, h).apply {
      leftMargin = x
      topMargin = y
      gravity = Gravity.TOP or Gravity.START
    }
    root.addView(card, lp)

    card.findViewWithTag<View>("titleBar")?.setOnTouchListener(dragListener(card, lp))
    card.findViewWithTag<View>("resizeHandle")?.setOnTouchListener(resizeListener(card, lp, density))

    observeViewModel()
    refreshProviders()
  }

  private fun observeViewModel() {
    viewModel.messages.observeForever { list ->
      mainHandler.post {
        messageAdapter.clear()
        messageAdapter.addAll(list)
        messageAdapter.notifyDataSetChanged()
        messageList?.setSelection(messageAdapter.count - 1)
      }
    }
    viewModel.status.observeForever { s ->
      mainHandler.post {
        statusTv?.text = s
        statusTv?.visibility = if (s.isNullOrBlank()) View.GONE else View.VISIBLE
      }
    }
    viewModel.isBusy.observeForever { busy ->
      mainHandler.post {
        inputEt?.isEnabled = !busy
      }
    }
  }

  fun refreshProviders() {
    val list = repo.getAll()
    val spinner = providerSpinner ?: return
    val names = if (list.isEmpty()) {
      listOf(activity.getString(R.string.ai_provider_empty))
    } else {
      list.map { it.name }
    }
    spinner.adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, names)
    val selected = repo.getSelected()
    if (selected != null) {
      val idx = list.indexOfFirst { it.id == selected.id }
      if (idx >= 0) spinner.setSelection(idx)
      viewModel.selectProvider(selected)
    }
    spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
      override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
        if (list.isNotEmpty() && position in list.indices) {
          viewModel.selectProvider(list[position])
        }
      }
      override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
    }
  }

  private fun buildCard(density: Float): View {
    val bg = GradientDrawable().apply {
      setColor(activity.resolveAttr(R.attr.colorSurface))
      cornerRadius = 12 * density
      setStroke((1 * density).toInt(), activity.resolveAttr(R.attr.colorOutline))
    }

    val root = LinearLayout(activity).apply {
      orientation = LinearLayout.VERTICAL
      background = bg
      elevation = 14 * density
      clipToOutline = true
      isClickable = true
    }

    val titleBar = LinearLayout(activity).apply {
      tag = "titleBar"
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
      setPadding((10 * density).toInt(), (6 * density).toInt(), (4 * density).toInt(), (6 * density).toInt())
      setBackgroundColor(activity.resolveAttr(R.attr.colorSurfaceVariant))
    }
    titleBar.addView(TextView(activity).apply {
      text = activity.getString(R.string.title_ai_chat)
      textSize = 15f
      setTypeface(null, Typeface.BOLD)
      setTextColor(activity.resolveAttr(R.attr.colorOnSurface))
      layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    })
    titleBar.addView(iconBtn(R.drawable.ic_chevron_down, activity.getString(R.string.ai_minimize)) { toggleMinimize() })
    titleBar.addView(iconBtn(R.drawable.ic_android, activity.getString(R.string.ai_maximize)) { toggleMaximize() })
    titleBar.addView(iconBtn(R.drawable.ic_close, activity.getString(R.string.ai_close)) { dismiss() })
    root.addView(titleBar, matchWrap())

    val topRow = LinearLayout(activity).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
      setPadding((8 * density).toInt(), (4 * density).toInt(), (8 * density).toInt(), (4 * density).toInt())
    }
    providerSpinner = Spinner(activity).also {
      topRow.addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }
    topRow.addView(Button(activity).apply {
      text = "⚙"
      setOnClickListener {
        activity.startActivity(Intent(activity, AiProvidersActivity::class.java))
      }
      layoutParams = LinearLayout.LayoutParams((40 * density).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
    })
    root.addView(topRow, matchWrap())

    val modeRow = LinearLayout(activity).apply {
      orientation = LinearLayout.HORIZONTAL
      setPadding((8 * density).toInt(), 0, (8 * density).toInt(), (4 * density).toInt())
    }
    modeChatBtn = Button(activity).apply {
      text = activity.getString(R.string.mode_chat)
      setOnClickListener { setMode(false) }
    }
    modeAgentBtn = Button(activity).apply {
      text = activity.getString(R.string.mode_agent)
      setOnClickListener { setMode(true) }
    }
    modeRow.addView(modeChatBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    modeRow.addView(modeAgentBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    root.addView(modeRow, matchWrap())
    setMode(true)

    statusTv = TextView(activity).apply {
      textSize = 12f
      setTextColor(activity.resolveAttr(R.attr.colorPrimary))
      setPadding((12 * density).toInt(), (2 * density).toInt(), (12 * density).toInt(), (2 * density).toInt())
      visibility = View.GONE
    }
    root.addView(statusTv, matchWrap())

    messageList = ListView(activity).apply {
      adapter = messageAdapter
      divider = null
      tag = "content"
    }
    root.addView(messageList, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

    val attachRow = HorizontalScrollView(activity).apply {
      val inner = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding((8 * density).toInt(), 0, (8 * density).toInt(), 0)
      }
      inner.addView(smallBtn(activity.getString(R.string.action_attach_file)) { onAttach(AiProvider.Capability.FILE) })
      inner.addView(smallBtn(activity.getString(R.string.action_attach_image)) { onAttach(AiProvider.Capability.IMAGE) })
      inner.addView(smallBtn(activity.getString(R.string.action_attach_video)) { onAttach(AiProvider.Capability.VIDEO) })
      inner.addView(smallBtn(activity.getString(R.string.ai_cancel)) { viewModel.cancel() })
      addView(inner)
    }
    root.addView(attachRow, matchWrap())

    val inputRow = LinearLayout(activity).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
      setPadding((8 * density).toInt(), (4 * density).toInt(), (8 * density).toInt(), (8 * density).toInt())
    }
    inputEt = EditText(activity).apply {
      hint = activity.getString(R.string.hint_ai_message)
      inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
      maxLines = 4
      layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    }
    inputRow.addView(inputEt)
    inputRow.addView(Button(activity).apply {
      text = activity.getString(R.string.action_send)
      setOnClickListener {
        val text = inputEt?.text?.toString().orEmpty()
        if (text.isNotBlank()) {
          inputEt?.text?.clear()
          viewModel.send(text) { path ->
            var confirmed = false
            val latch = java.util.concurrent.CountDownLatch(1)
            mainHandler.post {
              AlertDialog.Builder(activity)
                .setMessage(activity.getString(R.string.ai_confirm_delete_file, path))
                .setPositiveButton(android.R.string.ok) { _, _ ->
                  confirmed = true
                  latch.countDown()
                }
                .setNegativeButton(android.R.string.cancel) { _, _ -> latch.countDown() }
                .setOnCancelListener { latch.countDown() }
                .show()
            }
            latch.await()
            confirmed
          }
        }
      }
    })
    root.addView(inputRow, matchWrap())

    val resizeRow = LinearLayout(activity).apply {
      gravity = Gravity.END
      setPadding(0, 0, (4 * density).toInt(), (4 * density).toInt())
    }
    resizeRow.addView(View(activity).apply {
      tag = "resizeHandle"
      setBackgroundColor(activity.resolveAttr(R.attr.colorOutline))
      layoutParams = LinearLayout.LayoutParams((18 * density).toInt(), (18 * density).toInt())
    })
    root.addView(resizeRow, matchWrap())

    return root
  }

  private fun setMode(agent: Boolean) {
    viewModel.agentMode = agent
    modeAgentBtn?.alpha = if (agent) 1f else 0.5f
    modeChatBtn?.alpha = if (agent) 0.5f else 1f
  }

  private fun onAttach(capability: AiProvider.Capability) {
    val provider = viewModel.selectedProvider.value ?: repo.getSelected()
    if (provider == null || !provider.supports(capability)) {
      return
    }
    Toast.makeText(activity, "Seletor de ${capability.name.lowercase()}…", Toast.LENGTH_SHORT).show()
  }

  private fun iconBtn(drawableRes: Int, desc: String, onClick: () -> Unit): ImageButton {
    val density = activity.resources.displayMetrics.density
    return ImageButton(activity).apply {
      contentDescription = desc
      setBackgroundColor(Color.TRANSPARENT)
      setImageDrawable(ContextCompat.getDrawable(activity, drawableRes))
      setPadding((8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt())
      setOnClickListener { onClick() }
      layoutParams = LinearLayout.LayoutParams((36 * density).toInt(), (36 * density).toInt())
    }
  }

  private fun smallBtn(label: String, onClick: () -> Unit): Button {
    return Button(activity).apply {
      text = label
      textSize = 11f
      setOnClickListener { onClick() }
    }
  }

  private fun matchWrap() = LinearLayout.LayoutParams(
    ViewGroup.LayoutParams.MATCH_PARENT,
    ViewGroup.LayoutParams.WRAP_CONTENT
  )

  @SuppressLint("ClickableViewAccessibility")
  private fun dragListener(card: View, lp: FrameLayout.LayoutParams): View.OnTouchListener {
    var dX = 0f
    var dY = 0f
    return View.OnTouchListener { _, event ->
      when (event.action) {
        MotionEvent.ACTION_DOWN -> {
          dX = event.rawX - lp.leftMargin
          dY = event.rawY - lp.topMargin
          true
        }
        MotionEvent.ACTION_MOVE -> {
          if (!isMaximized) {
            lp.leftMargin = (event.rawX - dX).toInt().coerceAtLeast(0)
            lp.topMargin = (event.rawY - dY).toInt().coerceAtLeast(0)
            card.layoutParams = lp
          }
          true
        }
        MotionEvent.ACTION_UP -> {
          persistBounds(lp)
          true
        }
        else -> false
      }
    }
  }

  @SuppressLint("ClickableViewAccessibility")
  private fun resizeListener(card: View, lp: FrameLayout.LayoutParams, density: Float): View.OnTouchListener {
    var startW = 0
    var startH = 0
    var startRawX = 0f
    var startRawY = 0f
    return View.OnTouchListener { _, event ->
      when (event.action) {
        MotionEvent.ACTION_DOWN -> {
          startW = lp.width
          startH = lp.height
          startRawX = event.rawX
          startRawY = event.rawY
          true
        }
        MotionEvent.ACTION_MOVE -> {
          if (!isMaximized && !isMinimized) {
            lp.width = (startW + (event.rawX - startRawX)).toInt().coerceAtLeast((200 * density).toInt())
            lp.height = (startH + (event.rawY - startRawY)).toInt().coerceAtLeast((220 * density).toInt())
            card.layoutParams = lp
          }
          true
        }
        MotionEvent.ACTION_UP -> {
          persistBounds(lp)
          true
        }
        else -> false
      }
    }
  }

  private fun toggleMinimize() {
    val card = windowView ?: return
    val lp = card.layoutParams as FrameLayout.LayoutParams
    if (!isMinimized) {
      savedH = lp.height
      lp.height = (48 * activity.resources.displayMetrics.density).toInt()
      isMinimized = true
      card.findViewWithTag<View>("content")?.visibility = View.GONE
    } else {
      lp.height = savedH
      isMinimized = false
      card.findViewWithTag<View>("content")?.visibility = View.VISIBLE
    }
    card.layoutParams = lp
  }

  private fun toggleMaximize() {
    val card = windowView ?: return
    val lp = card.layoutParams as FrameLayout.LayoutParams
    val decor = activity.window.decorView
    if (!isMaximized) {
      savedX = lp.leftMargin
      savedY = lp.topMargin
      savedW = lp.width
      savedH = lp.height
      lp.leftMargin = 0
      lp.topMargin = 0
      lp.width = decor.width
      lp.height = decor.height
      isMaximized = true
      isMinimized = false
      card.findViewWithTag<View>("content")?.visibility = View.VISIBLE
    } else {
      lp.leftMargin = savedX
      lp.topMargin = savedY
      lp.width = savedW
      lp.height = savedH
      isMaximized = false
    }
    card.layoutParams = lp
  }

  private fun persistBounds(lp: FrameLayout.LayoutParams) {
    if (isMaximized || isMinimized) return
    prefs.edit()
      .putInt(KEY_X, lp.leftMargin)
      .putInt(KEY_Y, lp.topMargin)
      .putInt(KEY_W, lp.width)
      .putInt(KEY_H, lp.height)
      .apply()
  }

  fun dismiss() {
    val root = container ?: return
    (root.parent as? ViewGroup)?.removeView(root)
    container = null
    windowView = null
    instance = null
  }

  companion object {
    private const val PREFS_NAME = "ai_chat_window"
    private const val KEY_X = "x"
    private const val KEY_Y = "y"
    private const val KEY_W = "w"
    private const val KEY_H = "h"

    @Volatile
    private var instance: AiChatFloatingWindow? = null

    fun show(activity: Activity) {
      val existing = instance
      if (existing != null && existing.activity === activity) {
        existing.show()
        existing.refreshProviders()
        return
      }
      existing?.dismiss()
      val win = AiChatFloatingWindow(activity)
      instance = win
      win.show()
    }
  }
}
