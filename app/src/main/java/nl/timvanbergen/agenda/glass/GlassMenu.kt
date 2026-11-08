package nl.timvanbergen.agenda.glass

import android.content.res.Resources
import android.graphics.RectF
import android.graphics.RenderNode
import android.os.Build
import android.view.ViewGroup
import androidx.annotation.RequiresApi
import nl.timvanbergen.agenda.glass.motion.GlassJelly
import nl.timvanbergen.agenda.glass.overlay.GlassMenuOverlay
import nl.timvanbergen.agenda.glass.records.GlassMenuConfigRecord
import nl.timvanbergen.agenda.glass.records.GlassMenuItemRecord
import nl.timvanbergen.agenda.glass.records.GlassTriggerConfigRecord
import nl.timvanbergen.agenda.glass.render.GlassRenderer
import nl.timvanbergen.agenda.glass.render.GlassSurfaceState

/** Het glazen menu: groeit uit een plek op het scherm, bijvoorbeeld een knop in de webpagina. */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class GlassMenu(root: ViewGroup) {
  private val anchor = GlassMenuAnchor(root.resources)
  private val overlay = GlassMenuOverlay(root.context, root).also { it.anchor = anchor }

  init {
    root.addView(overlay, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
  }

  /** [from] is de plek van de knop in vensterpixels. */
  fun show(from: RectF, items: List<GlassMenuItemRecord>, dark: Boolean, onSelect: (String) -> Unit) {
    anchor.placement.set(from)
    anchor.items = items
    anchor.dark = dark
    anchor.onSelect = onSelect
    overlay.open(anchor)
  }
}

/** Waar het menu vandaan komt. De knop zelf staat in de pagina, dus hier valt niets te tekenen. */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal class GlassMenuAnchor(resources: Resources) {
  private val density = resources.displayMetrics.density
  private var glassRenderer: Any? = null
  private var contentNode: Any? = null

  val placement = RectF()
  var items: List<GlassMenuItemRecord> = emptyList()
  var dark = false
  var onSelect: (String) -> Unit = {}

  val glass = false
  val followsPress = false
  val menuWidth: Float? = null
  val alignment = "auto"
  val config = GlassMenuConfigRecord()
  val triggerConfig = GlassTriggerConfigRecord()
  val jelly = GlassJelly()
  val width get() = placement.width().toInt()
  val height get() = placement.height().toInt()

  fun isDark() = dark

  fun notifySelect(item: GlassMenuItemRecord, index: Int) = onSelect(item.id)

  internal fun applyLight(state: GlassSurfaceState, origin: RectF, opacity: Float) {
    val glow = jelly.glowValue * opacity * triggerConfig.shine
    state.pressX = origin.left + jelly.lightPositionX
    state.pressY = origin.top + jelly.lightPositionY
    state.pressRadius = LightRadius * density
    state.poolGain = LightPool * glow
    state.pressLift = LightLift * glow
    state.rimLift = LightRimLift * glow
  }

  @RequiresApi(Build.VERSION_CODES.TIRAMISU)
  fun recordContent(): RenderNode {
    val node = contentNode as? RenderNode ?: RenderNode("GlassMenu.trigger").also { contentNode = it }
    node.setPosition(0, 0, width, height)
    node.beginRecording(width, height)
    node.endRecording()
    return node
  }

  @RequiresApi(Build.VERSION_CODES.TIRAMISU)
  fun renderer(pixelDensity: Float): GlassRenderer =
    glassRenderer as? GlassRenderer ?: GlassRenderer(pixelDensity, pixelDensity * FieldStep).also { glassRenderer = it }

  private companion object {
    const val FieldStep = 4f
    const val LightRadius = 38f
    const val LightLift = .055f
    const val LightPool = .07f
    const val LightRimLift = .3f
  }
}
