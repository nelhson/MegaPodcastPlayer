package md.borisveriga.bpodcat.core.designsystem.shape

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.circle
import androidx.graphics.shapes.rectangle
import androidx.graphics.shapes.star
import androidx.graphics.shapes.toPath

/**
 * Adapts `androidx.graphics.shapes` polygons to Compose [Shape]s.
 *
 * This is the Expressive shape-morphing machinery that material3 1.4.0 keeps to itself:
 * `MaterialShapes` and its morph-aware button shapes are `internal` until 1.5.0. `graphics-shapes`
 * itself is stable and already on the classpath, so the design system drives it directly and gets
 * true polygon interpolation — a circle actually becoming a squircle, not a corner radius being
 * animated and hoping nobody looks closely.
 *
 * Every polygon in [BPodcatPolygons] is authored in a `-1..1` box centred on the origin, which is
 * what the scaling in [createOutlineFrom] assumes.
 */

/** A static polygon rendered as a Compose [Shape]. */
class RoundedPolygonShape(private val polygon: RoundedPolygon) : Shape {

    private val matrix = Matrix()

    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline = createOutlineFrom(polygon.toPath().asComposePath(), size, matrix)
}

/**
 * Two polygons interpolated at [progress].
 *
 * A new instance per frame is the intended usage: the [Morph] itself is the expensive part and is
 * cached by the caller (see `rememberMorph`), while the shape wrapper is a thin value.
 *
 * @param morph the pair of polygons to interpolate between.
 * @param progress `0f` renders the morph's start polygon, `1f` its end.
 */
class MorphShape(
    private val morph: Morph,
    private val progress: Float,
) : Shape {

    private val matrix = Matrix()

    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline = createOutlineFrom(morph.toPath(progress).asComposePath(), size, matrix)
}

/**
 * Maps a path authored in `-1..1` onto a box of [size].
 *
 * Scale then translate, in that order: the scale halves the coordinate space onto the box's
 * half-extents, and the translate shifts the origin from the centre to the top-left corner.
 */
private fun createOutlineFrom(path: Path, size: Size, matrix: Matrix): Outline {
    matrix.reset()
    matrix.scale(size.width / 2f, size.height / 2f)
    matrix.translate(1f, 1f)
    path.transform(matrix)
    return Outline.Generic(path)
}

/**
 * The shape vocabulary.
 *
 * Kept deliberately small. A design system with 35 shapes has no shape language; it has a palette
 * of novelties. These five each mean something in this app, and nothing else is added until it
 * does.
 */
object BPodcatPolygons {

    /** The resting state of anything round: the play button, a loading indicator's first frame. */
    val Circle: RoundedPolygon = RoundedPolygon.circle(numVertices = 12)

    /**
     * A rounded square with generous corners.
     *
     * The active/pressed counterpart to [Circle] — Expressive's signature move is a control that
     * squares off under the finger and springs back.
     */
    val Squircle: RoundedPolygon = RoundedPolygon.rectangle(
        width = 2f,
        height = 2f,
        rounding = CornerRounding(radius = 0.55f, smoothing = 0.6f),
    )

    /** A soft nine-lobed cookie, used as the ground for empty states and the loading indicator. */
    val Cookie: RoundedPolygon = RoundedPolygon.star(
        numVerticesPerRadius = 9,
        radius = 1f,
        innerRadius = 0.82f,
        rounding = CornerRounding(radius = 0.35f, smoothing = 1f),
        innerRounding = CornerRounding(radius = 0.35f, smoothing = 1f),
    )

    /** A four-lobed burst; the second frame of the loading morph. */
    val Clover: RoundedPolygon = RoundedPolygon.star(
        numVerticesPerRadius = 4,
        radius = 1f,
        innerRadius = 0.62f,
        rounding = CornerRounding(radius = 0.5f, smoothing = 1f),
        innerRounding = CornerRounding(radius = 0.5f, smoothing = 1f),
    )

    /** A soft heptagon, used to mask the artwork of the episode that is currently playing. */
    val Heptagon: RoundedPolygon = RoundedPolygon(
        numVertices = 7,
        radius = 1f,
        rounding = CornerRounding(radius = 0.4f, smoothing = 0.7f),
    )
}
