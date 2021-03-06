package geotrellis.transit.services

import geotrellis.transit._

import geotrellis._
import geotrellis.raster._

import spire.syntax._

object TravelTimeRaster {
  def apply(re: RasterExtent, llRe: RasterExtent, tti: SptInfo,ldelta:Double):Raster = {
    val ldelta2 = ldelta*ldelta
    val SptInfo(spt, Some(ReachableVertices(subindex, extent))) = tti

    val cols = re.cols
    val rows = re.rows
    val data = RasterData.emptyByType(TypeInt, cols, rows)

    cfor(0)(_ < cols, _ + 1) { col =>
      cfor(0)(_ < rows, _ + 1) { row =>
        val destLong = llRe.gridColToMap(col)
        val destLat = llRe.gridRowToMap(row)

        val e = Extent(destLong - ldelta, destLat - ldelta, destLong + ldelta, destLat + ldelta)
        val l = subindex.pointsInExtent(e)

        if (l.isEmpty) {
          data.set(col, row, NODATA)
        } else {
          var s = 0.0
          var c = 0
          var ws = 0.0
          val length = l.length
          cfor(0)(_ < length, _ + 1) { i =>
            val target = l(i).asInstanceOf[Int]
            val t = spt.travelTimeTo(target).toInt
            val loc = Main.context.graph.location(target)
            val dlat = (destLat - loc.lat)
            val dlong = (destLong - loc.long)
            val d = dlat * dlat + dlong * dlong
            if (d < ldelta2) {
              val w = 1 / d
              s += t * w
              ws += w
              c += 1
            }
          }
          if (c == 0) {
            data.set(col, row, NODATA)
          } else {
            val mean = s / ws
            data.set(col, row, mean.toInt)
          }
        }
      }
    }

    Raster(data, re)
  }
}
