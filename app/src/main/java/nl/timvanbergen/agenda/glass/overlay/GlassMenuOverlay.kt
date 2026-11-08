package nl.timvanbergen.agenda.glass.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderNode
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Choreographer
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.annotation.RequiresApi
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper
import nl.timvanbergen.agenda.glass.GlassMenuAnchor
import kotlin.math.min

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal class GlassMenuOverlay(
  context: Context,
  private val root: ViewGroup,
) : View(context), ViewTreeObserver.OnPreDrawListener, Choreographer.FrameCallback {
  private val density = resources.displayMetrics.density
  private val limits = Rect()
  private val location = IntArray(2)
  private val supportsGlass = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
  private val backdrop: RenderNode? = if (supportsGlass) RenderNode("GlassMenu.backdrop") else null
  private var session: GlassMenuSession? = null
  private var framePending = false
  private var lastFrameNanos = 0L
  private val accessibility = MenuAccessibility()
  var anchor: GlassMenuAnchor? = null
  private var backCallback: Any? = null
  private var animationScale = 1f
  private var startupFrames = 0
  private var warmed = false

  init {
    setWillNotDraw(false)
    isClickable = false
    importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    ViewCompat.setAccessibilityDelegate(this, accessibility)
  }

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    viewTreeObserver.addOnPreDrawListener(this)
  }

  override fun onDetachedFromWindow() {
    viewTreeObserver.removeOnPreDrawListener(this)
    finishSession()
    Choreographer.getInstance().removeFrameCallback(this)
    framePending = false
    super.onDetachedFromWindow()
  }

  override fun onPreDraw(): Boolean {
    // Menu dicht: niets opnemen, zodat de agenda er geen hinder van heeft.
    if (session != null && backdropChanged()) invalidate()
    return true
  }

  private fun backdropChanged(): Boolean {
    for (index in 0 until root.childCount) {
      val child = root.getChildAt(index)
      if (child !== this && child.isDirty) return true
    }
    return false
  }

  override fun onDraw(canvas: Canvas) {
    limits.set(0, 0, width, height)
    val active = session
    if (!supportsGlass || !canvas.isHardwareAccelerated) {
      active?.drawFallback(canvas)
      return
    }
    if (active == null) {
      warmShaders(canvas)
      return
    }
    drawGlass(canvas, active)
  }

  @RequiresApi(Build.VERSION_CODES.TIRAMISU)
  private fun warmShaders(canvas: Canvas) {
    if (warmed) return
    warmed = true
    anchor?.renderer(density)?.warmPipelines(canvas)
  }

  @RequiresApi(Build.VERSION_CODES.TIRAMISU)
  private fun drawGlass(canvas: Canvas, active: GlassMenuSession) {
    val node = backdrop ?: return
    node.setPosition(limits)
    val recording = node.beginRecording(width, height)
    recording.drawColor(if (active.anchor.isDark()) Color.BLACK else Color.WHITE)
    root.background?.draw(recording)
    for (index in 0 until root.childCount) {
      val child = root.getChildAt(index)
      if (child !== this && child.visibility == VISIBLE && child.id != android.R.id.statusBarBackground &&
        child.id != android.R.id.navigationBarBackground
      ) child.draw(recording)
    }
    node.endRecording()

    active.draw(canvas, node, limits)
  }

  fun anchorRect(anchor: GlassMenuAnchor, out: RectF) {
    getLocationInWindow(location)
    out.set(anchor.placement)
    out.offset(-location[0].toFloat(), -location[1].toFloat())
  }

  fun open(anchor: GlassMenuAnchor) {
    finishSession()
    session = GlassMenuSession(this, anchor, density, supportsGlass, null, width, height, insets())
    startupFrames = StartupFrames
    isFocusable = true
    isFocusableInTouchMode = true
    requestFocus()
    registerBack()
    importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    accessibility.invalidateRoot()
    requestFrame()
    invalidate()
  }

  private fun insets(): Rect {
    val window = rootWindowInsets ?: return Rect()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      val insets = window.getInsets(WindowInsets.Type.systemBars())
      return Rect(insets.left, insets.top, insets.right, insets.bottom)
    }
    @Suppress("DEPRECATION")
    return Rect(window.systemWindowInsetLeft, window.systemWindowInsetTop, window.systemWindowInsetRight, window.systemWindowInsetBottom)
  }

  fun requestClose() {
    val active = session ?: return
    if (!active.expanded) return
    active.close()
    releaseInput()
    requestFrame()
  }

  private fun releaseInput() {
    clearFocus()
    isFocusable = false
    isFocusableInTouchMode = false
    unregisterBack()
    importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
  }

  private fun finishSession() {
    val active = session ?: return
    active.release()
    session = null
    releaseInput()
    invalidate()
  }

  fun requestFrame() {
    if (framePending || !isAttachedToWindow) return
    framePending = true
    lastFrameNanos = 0L
    animationScale = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    Choreographer.getInstance().postFrameCallback(this)
  }

  override fun doFrame(frameTimeNanos: Long) {
    framePending = false
    val scale = animationScale
    val interval = 1f / (display?.refreshRate ?: DefaultRefreshRate).coerceAtLeast(MinimumRefreshRate)
    val elapsed = if (lastFrameNanos == 0L) interval / 2f else (frameTimeNanos - lastFrameNanos) / 1_000_000_000f
    val limit = interval * (if (startupFrames > 0) 1f else MaxSkippedFrames)
    startupFrames = (startupFrames - 1).coerceAtLeast(0)
    val seconds = if (scale == 0f) 10f else min(elapsed, limit) / scale
    lastFrameNanos = frameTimeNanos
    var running = false
    val active = session
    if (active != null) {
      val speed = active.anchor.config.speed
      if (active.tick(seconds * speed)) finishSession() else running = running || active.needsFrame
    }
    invalidate()
    if (running) {
      framePending = true
      Choreographer.getInstance().postFrameCallback(this)
    }
  }

  override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
    super.onSizeChanged(w, h, oldw, oldh)
    if (oldw != 0 && oldh != 0) finishSession()
  }

  override fun onTouchEvent(event: MotionEvent): Boolean {
    val active = session ?: return false
    if (!active.expanded) return false
    active.onTouch(event)
    requestFrame()
    return true
  }

  fun hapticTick() {
    val constant = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      HapticFeedbackConstants.SEGMENT_FREQUENT_TICK
    } else {
      HapticFeedbackConstants.CLOCK_TICK
    }
    performHapticFeedback(constant)
  }

  override fun dispatchKeyEvent(event: KeyEvent): Boolean {
    if (session?.expanded == true && event.keyCode == KeyEvent.KEYCODE_BACK) {
      if (event.action == KeyEvent.ACTION_UP) requestClose()
      return true
    }
    return super.dispatchKeyEvent(event)
  }

  private fun registerBack() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || backCallback != null) return
    val dispatcher = findOnBackInvokedDispatcher() ?: return
    val callback = OnBackInvokedCallback { requestClose() }
    dispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_OVERLAY, callback)
    backCallback = callback
  }

  private fun unregisterBack() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val callback = backCallback as? OnBackInvokedCallback ?: return
    findOnBackInvokedDispatcher()?.unregisterOnBackInvokedCallback(callback)
    backCallback = null
  }

  override fun dispatchHoverEvent(event: MotionEvent): Boolean =
    accessibility.dispatchHoverEvent(event) || super.dispatchHoverEvent(event)

  private inner class MenuAccessibility : ExploreByTouchHelper(this) {
    override fun getVirtualViewAt(x: Float, y: Float): Int =
      session?.rowIndexAt(x, y)?.takeIf { it >= 0 } ?: INVALID_ID

    override fun getVisibleVirtualViews(ids: MutableList<Int>) {
      session?.accessibleRows()?.forEach { ids.add(it.index) }
    }

    override fun onPopulateNodeForVirtualView(id: Int, node: AccessibilityNodeInfoCompat) {
      val row = session?.accessibleRows()?.firstOrNull { it.index == id }
      node.text = row?.item?.title ?: ""
      node.className = "android.widget.Button"
      node.isCheckable = row?.item?.checked != null
      node.isChecked = row?.item?.checked == true
      node.isEnabled = row?.selectable == true
      node.isClickable = row?.selectable == true
      if (row?.selectable == true) node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK)
      val bounds = session?.screenRect(row) ?: Rect(0, 0, 1, 1)
      @Suppress("DEPRECATION")
      node.setBoundsInParent(bounds)
    }

    override fun onPerformActionForVirtualView(id: Int, action: Int, arguments: Bundle?): Boolean {
      if (action != AccessibilityNodeInfoCompat.ACTION_CLICK) return false
      session?.select(id) ?: return false
      return true
    }
  }

  companion object {
    private const val DefaultRefreshRate = 60f
    private const val MinimumRefreshRate = 30f
    private const val MaxSkippedFrames = 2.5f
    private const val StartupFrames = 4

  }
}
