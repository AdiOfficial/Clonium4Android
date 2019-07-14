package com.pierbezuhoff.clonium.models

import android.graphics.*
import android.util.Log
import androidx.core.graphics.scaleMatrix
import androidx.core.graphics.times
import androidx.core.graphics.translationMatrix
import com.pierbezuhoff.clonium.domain.*
import kotlin.math.min

/** Draw [Game] state and animation on [Canvas] */
interface GamePresenter : AnimationAdvancer {
    fun setSize(width: Int, height: Int)
    fun draw(canvas: Canvas)
    fun highlight(poss: Set<Pos>, weak: Boolean = false)
    fun unhighlight() =
        highlight(emptySet())
    fun pos2point(pos: Pos): Point
    fun pointf2pos(point: PointF): Pos
}

// MAYBE: rotate rectangular board along with view
class SimpleGamePresenter(
    private var board: Board,
    private val bitmapLoader: BitmapLoader
) : Any()
    , AnimationAdvancer by PoolingAnimationAdvancer()
    , GamePresenter
{
    private var viewWidth: Int = 0
    private var viewHeight: Int = 0

    private val cellSize: Int
        get() = min(viewWidth / board.width, viewHeight / board.height)

    private val paint: Paint = Paint(
        Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG
    )

    private var weakHighlight: Boolean = false
    private var highlighted: Set<Pos> = emptySet()

    override fun setSize(width: Int, height: Int) {
        Log.i(TAG, "width = $width, height = $height")
        viewWidth = width
        viewHeight = height
    }

    override fun draw(canvas: Canvas) {
        canvas.drawBoard()
        canvas.drawAnimations()
    }

    private fun Canvas.drawBoard(board: Board = this@SimpleGamePresenter.board) {
        require(viewWidth > 0 && viewHeight > 0)
        drawColor(BACKGROUND_COLOR)
        for (pos in board.asPosSet())
            drawCell(pos)
        for (pos in highlighted)
            drawHighlight(pos)
        for ((pos, maybeChip) in board.asPosMap())
            maybeChip?.let {
                drawChip(pos, it)
            }
    }

    private fun Canvas.drawCell(pos: Pos) {
        val bitmap = bitmapLoader.loadCell()
        val rescaleMatrix = rescaleMatrix(bitmap.width, bitmap.height)
        val translateMatrix = pos2matrix(pos)
        drawBitmap(
            bitmap,
            translateMatrix * rescaleMatrix,
            paint
        )
    }

    private fun Canvas.drawHighlight(pos: Pos) {
        val bitmap = bitmapLoader.loadHighlight(weak = weakHighlight)
        val rescaleMatrix = rescaleMatrix(bitmap.width, bitmap.height)
        val translateMatrix = pos2matrix(pos)
        drawBitmap(
            bitmap,
            translateMatrix * rescaleMatrix,
            paint
        )
    }

    private fun Canvas.drawChip(pos: Pos, chip: Chip) {
        val bitmap = bitmapLoader.loadChip(chip)
        val rescaleMatrix = rescaleMatrix(bitmap.width, bitmap.height)
        val centeredScaleMatrix = centeredScaleMatrix(bitmap.width, bitmap.height, CHIP_CELL_RATIO)
        val translateMatrix = pos2matrix(pos)
        drawBitmap(
            bitmap,
            translateMatrix * rescaleMatrix * centeredScaleMatrix,
            paint
        )
    }

    private fun rescaleMatrix(
        width: Int, height: Int,
        targetWidth: Int = cellSize, targetHeight: Int = cellSize
    ): Matrix =
        scaleMatrix(targetWidth.toFloat() / width, targetHeight.toFloat() / height)

    private fun centeredScaleMatrix(
        width: Int, height: Int,
        scaleX: Float, scaleY: Float = scaleX): Matrix =
        Matrix().apply {
            postScale(
                scaleX, scaleY,
                width / 2f, height / 2f
            )
        }

    private fun pos2matrix(pos: Pos): Matrix =
        pos2point(pos).let { translationMatrix(it.x.toFloat(), it.y.toFloat()) }

    override fun highlight(poss: Set<Pos>, weak: Boolean) {
        highlighted = poss
        weakHighlight = weak
    }

    override fun pos2point(pos: Pos): Point =
        Point(pos.x * cellSize, pos.y * cellSize)

    override fun pointf2pos(point: PointF): Pos =
        Pos((point.x / cellSize).toInt(), (point.y / cellSize).toInt())

    companion object {
        private const val TAG = "GamePresenter"
        private const val BACKGROUND_COLOR: Int = Color.BLACK
        private const val CHIP_CELL_RATIO: Float = 0.9f
    }
}