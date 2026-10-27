package app.quinten.analysis.paged

import app.quinten.analysis.page.Box
import app.quinten.analysis.page.PageText
import app.quinten.model.ImageData

/**
 * Finds illustrations on a page image: connected regions of ink that are not covered by
 * recognised text lines. Used for scanned pages so pictures survive text reflow.
 */
object IllustrationDetector {
    fun find(img: ImageData.Raster, text: PageText, pageW: Float, pageH: Float, cellPx: Int = 8): List<Box> {
        val gw = (img.width + cellPx - 1) / cellPx
        val gh = (img.height + cellPx - 1) / cellPx
        if (gw < 4 || gh < 4) return emptyList()
        val ink = IntArray(gw * gh)
        for (y in 0 until img.height) {
            val row = y * img.width
            val gy = (y / cellPx) * gw
            for (x in 0 until img.width) {
                val c = img.argb[row + x]
                val lum = (((c shr 16) and 0xFF) * 3 + ((c shr 8) and 0xFF) * 6 + (c and 0xFF)) / 10
                if (lum < 150) ink[gy + x / cellPx]++
            }
        }
        val dark = BooleanArray(gw * gh) { ink[it] > cellPx * cellPx / 12 }
        // Clear text areas.
        val sx = img.width / pageW; val sy = img.height / pageH
        for (l in text.lines) {
            val pad = l.box.height * 0.35f
            val x0 = (((l.box.x0 - pad) * sx) / cellPx).toInt().coerceIn(0, gw - 1)
            val x1 = (((l.box.x1 + pad) * sx) / cellPx).toInt().coerceIn(0, gw - 1)
            val y0 = (((l.box.y0 - pad) * sy) / cellPx).toInt().coerceIn(0, gh - 1)
            val y1 = (((l.box.y1 + pad) * sy) / cellPx).toInt().coerceIn(0, gh - 1)
            for (y in y0..y1) for (x in x0..x1) dark[y * gw + x] = false
        }
        // Connected components (8-neighbourhood) via iterative flood fill.
        val label = IntArray(gw * gh)
        val boxes = ArrayList<IntArray>()
        val stack = IntArray(gw * gh)
        var next = 1
        for (start in 0 until gw * gh) {
            if (!dark[start] || label[start] != 0) continue
            var sp = 0
            stack[sp++] = start
            label[start] = next
            var minX = gw; var minY = gh; var maxX = 0; var maxY = 0; var count = 0
            while (sp > 0) {
                val c = stack[--sp]
                val cx = c % gw; val cy = c / gw
                count++
                if (cx < minX) minX = cx; if (cx > maxX) maxX = cx
                if (cy < minY) minY = cy; if (cy > maxY) maxY = cy
                for (dy in -1..1) for (dx in -1..1) {
                    val nx = cx + dx; val ny = cy + dy
                    if (nx < 0 || ny < 0 || nx >= gw || ny >= gh) continue
                    val n = ny * gw + nx
                    if (dark[n] && label[n] == 0) { label[n] = next; stack[sp++] = n }
                }
            }
            next++
            boxes += intArrayOf(minX, minY, maxX, maxY, count)
        }
        val out = ArrayList<Box>()
        for (b in boxes) {
            val w = b[2] - b[0] + 1; val h = b[3] - b[1] + 1
            if (w < gw * 0.08 || h < gh * 0.05) continue // specks, rules, stray marks
            if (w * h < gw * gh * 0.015) continue
            if (b[4] < w * h * 0.08) continue // sparse: probably noise/border
            out += Box(b[0] * cellPx / sx, b[1] * cellPx / sy, (b[2] + 1) * cellPx / sx, (b[3] + 1) * cellPx / sy)
        }
        // Merge overlapping boxes.
        var merged = true
        while (merged) {
            merged = false
            loop@ for (i in out.indices) for (j in i + 1 until out.size) {
                if (out[i].overlapsX(out[j]) && out[i].overlapsY(out[j])) {
                    out[i] = out[i].union(out[j]); out.removeAt(j); merged = true; break@loop
                }
            }
        }
        return out.sortedBy { it.y0 }
    }
}
