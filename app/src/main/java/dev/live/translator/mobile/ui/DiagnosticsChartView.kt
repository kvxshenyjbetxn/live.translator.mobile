package dev.live.translator.mobile.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import dev.live.translator.mobile.R
import dev.live.translator.mobile.diagnostics.ChartGraph

class DiagnosticsChartView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val density = resources.displayMetrics.density
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.line)
        style = Paint.Style.STROKE
        strokeWidth = density
        pathEffect = DashPathEffect(floatArrayOf(4 * density, 3 * density), 0f)
    }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * density; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val path = Path()
    private var graph = ChartGraph()
    private var bars = false
    private val inset = 2 * density
    private val vInset = 4 * density

    fun configure(color: Int, bars: Boolean) {
        line.color = color
        fill.color = color
        this.bars = bars
    }

    fun submit(graph: ChartGraph) {
        this.graph = graph
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val left = inset
        val width = width - 2 * inset
        val top = vInset
        val height = height - 2 * vInset
        if (width <= 0 || height <= 0) return
        for (i in 0..2) {
            val y = top + height * i / 2f
            path.rewind(); path.moveTo(left, y); path.lineTo(left + width, y)
            canvas.drawPath(path, grid)
        }
        if (bars) {
            val half = width * 0.04f
            for (p in graph.points) {
                val cx = left + p.x * width
                canvas.drawRect(cx - half, top + p.y * height, cx + half, top + height, fill)
            }
            return
        }
        for (segment in graph.lines) {
            if (segment.size == 1) continue
            path.rewind()
            segment.forEachIndexed { i, p ->
                val x = left + p.x * width
                val y = top + p.y * height
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            canvas.drawPath(path, line)
        }
        for (segment in graph.lines) if (segment.size == 1) segment[0].let { canvas.drawCircle(left + it.x * width, top + it.y * height, 1.5f * density, fill) }
    }
}
