package geotrellis.transit.services

import geotrellis.transit._

import geotrellis.network._
import geotrellis.network.graph._
import geotrellis.network.index.SpatialIndex
import geotrellis._
import geotrellis.admin._
import geotrellis.admin.Json._
import geotrellis.raster.op._
import geotrellis.statistics.op._
import geotrellis.rest.op._
import geotrellis.rest._
import geotrellis.raster._
import geotrellis.feature._
import geotrellis.feature.op._
import geotrellis.data.ColorRamps

import javax.ws.rs._
import javax.ws.rs.core.Response

import geotrellis.transit.Logger
import scala.collection.JavaConversions._
import com.wordnik.swagger.annotations._
import com.wordnik.swagger.jaxrs._
import com.wordnik.swagger.sample.model.User
import com.wordnik.swagger.sample.data.UserData
import com.wordnik.swagger.sample.exception.NotFoundException
import com.wordnik.swagger.core.util.RestResourceUtil
import scala.collection.JavaConverters._
import javax.xml.bind.annotation._
import com.fasterxml.jackson.annotation.JsonIgnore
import org.codehaus.jackson.annotate.JsonIgnoreProperties

import scala.collection.JavaConverters._
import scala.collection.mutable
import spire.syntax._

case class TravelTimeInfo(spt: ShortestPathTree, vertices: Option[ReachableVertices])

import javax.xml.bind.annotation._
import scala.reflect.BeanProperty

@XmlRootElement(name = "person")
@XmlAccessorType(XmlAccessType.FIELD)
case class Token(@XmlElement(name = "tokenz") token: String)

//REFACTOR: spt.getTravelTimeInfo
object TravelTimeInfo {
  def apply(lat: Double, lng: Double, time: Time, duration: Duration, pathType:PathType, departing:Boolean): TravelTimeInfo = {
    val startVertex = Main.context.index.nearest(lat, lng)
    val spt =
      geotrellis.transit.Logger.timedCreate("Creating shortest path tree...",
        "Shortest Path Tree created.") { () =>
        if(departing) {
          ShortestPathTree.departure(startVertex, time, Main.context.graph, duration,pathType)
        } else {
          println(s"YES DOING ARRIVAL")
          ShortestPathTree.arrival(startVertex, time, Main.context.graph, duration,pathType)
        }
      }

    TravelTimeInfo(spt, ReachableVertices.fromSpt(spt))
  }

}

object TravelShed {
  val cache = mutable.Map[String, TravelTimeInfo]()
}

case class ReachableVertices(index: SpatialIndex[Int], extent: Extent)

object ReachableVertices {
  def fromSpt(spt: ShortestPathTree): Option[ReachableVertices] = {
    var xmin = Double.MaxValue
    var ymin = Double.MaxValue
    var xmax = Double.MinValue
    var ymax = Double.MinValue

    val subindex =
      geotrellis.transit.Logger.timedCreate("Creating subindex of reachable vertices...",
        "Subindex created.") { () =>
          val reachable = spt.reachableVertices.toList
          SpatialIndex(reachable) { v =>
            val l = Main.context.graph.location(v)
            if (xmin > l.long) {
              xmin = l.long
            }
            if (xmax < l.long) {
              xmax = l.long
            }
            if (ymin > l.lat) {
              ymin = l.lat
            }
            if (ymax < l.lat) {
              ymax = l.lat
            }
            (l.lat, l.long)
          }
        }
    if (xmin == Double.MaxValue)
      None
    else {
      val extent = Extent(xmin, ymin, xmax, ymax)
      Some(ReachableVertices(subindex, extent))
    }
  }
}

@Produces(Array("application/json"))
@Path("/travelshed")
@Api(value = "/travelshed", description = "Operations about vertices")
class TravelShed {
  val ldelta: Float = 0.0018f
  val ldelta2: Float = ldelta * ldelta

  def reproject(wmX: Double, wmY: Double) = {
    val rp = Reproject(Point(wmX, wmY, 0), Projections.WebMercator, Projections.LatLong)
      .asInstanceOf[Point[Int]]
      .geom
    (rp.getX, rp.getY)
  }

  def stringToColor(s: String) = {
    val ns =
      if (s.startsWith("0x")) {
        s.substring(2, s.length)
      } else { s }

    val (color, alpha) =
      if (ns.length == 8) {
        (ns.substring(0, ns.length - 2), ns.substring(ns.length - 2, ns.length))
      } else {
        (ns, "FF")
      }

    (Integer.parseInt(color, 16) << 8) + Integer.parseInt(alpha, 16)
  }

  def traveltimeRaster(re: RasterExtent, llRe: RasterExtent, tti: TravelTimeInfo): Raster = {
    val TravelTimeInfo(spt, Some(ReachableVertices(subindex, extent))) = tti

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


  @ApiOperation(
    value = "Generate a travelshed for a public transit trip.",
    notes = "Returns a token for subsequent requests.",
    responseClass = "commonspace.services.Token")
  @GET
  @Path("/request")
  @Produces(Array("application/json"))
  def getRequest(
    @ApiParam(value = "Latitude of origin point", required = true, defaultValue = "39.957572")@DefaultValue("39.957572")  
    @QueryParam("latitude") 
    latitude: Double,
    
    @ApiParam(value = "Longitude of origin point", required = true, defaultValue = "-75.161782")@DefaultValue("-75.161782")
    @QueryParam("longitude") 
    longitude: Double,
    
    @ApiParam(value = "Starting time of trip, in seconds from midnight", required = true, defaultValue = "0")
    @QueryParam("time") 
    timeString: String,
    
    @ApiParam(value="Maximum duration of trip, in seconds", required=true, defaultValue="1800")
    @QueryParam("duration") durationString: String,

    @ApiParam(value="Mode of transportation. One of: walk, bike, transit", required=true, defaultValue="transit")
    @QueryParam("mode") @DefaultValue("transit") mode:String,

    @ApiParam(value="Direction of travel. One of: departing,arriving", required=true, defaultValue="departing")
    @QueryParam("direction") @DefaultValue("departing") direction:String): Response = {
    println(s" LAT $latitude LONG $longitude TIME $timeString DURATION $durationString")
    val lat = latitude.toDouble
    val long = longitude.toDouble
    val time = Time(timeString.toInt)
    val duration = Duration(durationString.toInt)

    val pathType = 
      mode match {
        case "walk" => WalkPath
        case "bike" => BikePath
        case "transit" => TransitPath
        case _ =>
          return ERROR("Unknown mode. Choose from walk,bike, or transit")
      }

    val departing = direction != "arriving"
    println(s" ---------------------- DIRECTION $direction")
    val tti = TravelTimeInfo(lat, long, time, duration,pathType,departing)

    tti.vertices match {
      case Some(ReachableVertices(subindex, extent)) =>
        //      val token = s"$lat$long$time$duration"
        val token = "thisisthetoken"
        println(s"Saving request data for token $token")
        TravelShed.cache(token) = tti
      Response.ok.entity( Map( "token" -> token) ).build

      case None => OK.json(s"""{ "token": "" } """)
    }
  }

  def rasterToGeoJson(r: Op[Raster], tolerance: Double): Op[String] = {
    r
      .into(logic.RasterMapIfSet(_)(z => 1))
      .into(ToVector(_))
      .flatMap {
        l => logic.Collect(l.map(geometry.Simplify(_, tolerance)))
      }
      .map { list =>
        println(s"vector count: ${list.length}")
        val geoms = list
          .filter(_.data != 0)
          .map { _.geom.asInstanceOf[com.vividsolutions.jts.geom.Polygon] }

        val multiPolygonGeom = Feature.factory.createMultiPolygon(geoms.toArray)
        MultiPolygon(multiPolygonGeom, None)
      }
      .into(io.ToGeoJson(_))
  }

  @GET
  @Path("/wms")
  def wms(
    @DefaultValue("")@QueryParam("token") token: String,
    @DefaultValue("")@QueryParam("bbox") bbox: String,
    @DefaultValue("256")@QueryParam("cols") cols: String,
    @DefaultValue("256")@QueryParam("rows") rows: String,
    @DefaultValue("")@QueryParam("lat") latString: String,
    @DefaultValue("")@QueryParam("lng") longString: String,
    @DefaultValue("43200")@QueryParam("time") startTime: String,
    @DefaultValue("600")@QueryParam("duration") durationString: String,
    @DefaultValue("")@QueryParam("palette") palette: String,
    @DefaultValue("")@QueryParam("breaks") breaks: String,
    @DefaultValue("false")@QueryParam("datapng") dataPng: Boolean): Response = {

    val extentOp = string.ParseExtent(bbox)

    val llExtentOp = extentOp.map { ext =>
      val (ymin, xmin) = reproject(ext.xmin, ext.ymin)
      val (ymax, xmax) = reproject(ext.xmax, ext.ymax)
      Extent(xmin, ymin, xmax, ymax)
    }

    val colsOp = string.ParseInt(cols)
    val rowsOp = string.ParseInt(rows)

    val reOp = geotrellis.raster.op.extent.GetRasterExtent(extentOp, colsOp, rowsOp)
    val llReOp = geotrellis.raster.op.extent.GetRasterExtent(llExtentOp, colsOp, rowsOp)

    val tti = TravelShed.cache(token)
    val (spt, subindex, extent) = tti match {
      case TravelTimeInfo(spt, Some(ReachableVertices(subindex, extent))) => (spt, subindex, extent)
      case _ => throw new Exception("Invalid TravelTimeInfo in cache.")
    }

    val rOp =
      for (
        re <- reOp;
        llRe <- llReOp
      ) yield {
        geotrellis.transit.Logger.timedCreate(s"Creating travel time raster ($re.cols x $re.rows)...",
          "Travel time raster created.") { () =>

            val factor = 3
            val newRe = re.withResolution(re.cellwidth * factor, re.cellheight * factor)
            val newllRe = llRe.withResolution(llRe.cellwidth * factor, llRe.cellheight * factor)
            val cols = newRe.cols
            val rows = newRe.rows

            val e = Extent(extent.xmin - ldelta,
              extent.ymin - ldelta,
              extent.xmax + ldelta,
              extent.ymax + ldelta)

            llRe.extent.intersect(e) match {
              case Some(ie) => traveltimeRaster(newRe, newllRe, tti)
              case None => Raster.empty(newRe)
            }
          }
      }

    val colorMap: (Int => Int) =
      if (palette != "") {
        if (breaks == "") {
          return ERROR("Must provide breaks with palette")
        }
        val colors = palette.split(",").map(stringToColor).toArray
        val breakpoints = breaks.split(",").map(_.toInt).toArray

        val len = breakpoints.length
        if (len > colors.length) {
          return ERROR("Breaks must have less than or equal the number of colors in the palette.")
        }

        { z =>
          var i = 0
          while (i < len && z > breakpoints(i)) { i += 1 }
          if (i == len) {
            // Allow for the last color in the palette to be
            // for under or over the last break. 
            if (len < colors.length) {
              colors(i)
            } else {
              colors(i - 1)
            }
          } else {
            colors(i)
          }
        }

      } else {
        val colorRamp = ColorRamps.HeatmapBlueToYellowToRedSpectrum
        val palette = colorRamp.interpolate(13).colors

        { z =>
          val minutes = z / 60
          minutes match {
            case a if a < 3 => palette(0)
            case a if a < 5 => palette(1)
            case a if a < 8 => palette(3)
            case a if a < 10 => palette(4)
            case a if a < 15 => palette(5)
            case a if a < 20 => palette(6)
            case a if a < 25 => palette(7)
            case a if a < 30 => palette(8)
            case a if a < 40 => palette(9)
            case a if a < 50 => palette(10)
            case a if a < 60 => palette(11)
            case _ => palette(12)
          }
        }
      }

    val colorRasterOp =
      rOp.map(_.mapIfSet(colorMap))

    val dataRasterOp = rOp.map(r => r.map { z =>
      if (z == NODATA) 0 else {
        // encode seconds in RGBA color values: 0xRRGGBBAA.

        // If you disregard the alpha channel, 
        // you can think of this as encoding the value in base 255:
        // B = x * 1
        // G = x * 255
        // R = x * 255 * 255
        val b = (z % 255) << 8
        val g = (z / 255).toInt << 16
        val r = (z / (255 * 255)).toInt << 24

        // Alpha channel is always set to 255 to avoid the values getting garbed
        // by browser optimizations.
        r | g | b | 0xff
      }
    })

    val outputOp = if (dataPng) dataRasterOp else colorRasterOp

    val resampled =
      geotrellis.raster.op.transform.Resize(outputOp, colsOp, rowsOp)

    val png = io.RenderPngRgba(resampled)

    GeoTrellis.run(png) match {
      case process.Complete(img, h) =>
        OK.png(img)
      case process.Error(message, failure) =>
        ERROR(message, failure)
    }
  }

  @GET
  @Path("/json")
  def getGeoJson(
    @DefaultValue("39.957572") @QueryParam("latitude") latitude: String,
    @DefaultValue("-75.161782") @QueryParam("longitude") longitude: String,
    @QueryParam("time") @DefaultValue("360") timeString: String,
    @QueryParam("duration") @DefaultValue("360") durationString: String,
    @QueryParam("cols") @DefaultValue("500") cols: Int,
    @QueryParam("rows") @DefaultValue("500") rows: Int,
    @QueryParam("token") @DefaultValue("") token: String,
    @QueryParam("mode") @DefaultValue("transit") mode:String,
    @ApiParam(value="Direction of travel. One of: departing,arriving", required=true, defaultValue="departing")
    @QueryParam("direction") @DefaultValue("departing") direction:String,
    @QueryParam("tolerance") @DefaultValue("0.0001") tolerance:Double): Response = {
    val tti = if (token == "") {
      val lat = latitude.toDouble
      val long = longitude.toDouble
      val time = Time(timeString.toInt)
      val duration = Duration(durationString.toInt)
      val pathType =
        mode match {
          case "walk" => WalkPath
          case "bike" => BikePath
          case "transit" => TransitPath
          case _ =>
            return ERROR("Unknown mode. Choose from walk,bike, or transit")
        }

      val departing = direction != "arriving"

      TravelTimeInfo(lat, long, time, duration,pathType,departing)
    } else {
      TravelShed.cache(token)
    }

    val geojsonOp: Op[String] = tti.vertices match {
      case Some(ReachableVertices(subindex, extent)) =>
        val e = Extent(extent.xmin - ldelta,
          extent.ymin - ldelta,
          extent.xmax + ldelta,
          extent.ymax + ldelta)
        val re = RasterExtent(e, cols, rows)
        val raster = traveltimeRaster(re, re, tti)
        rasterToGeoJson(raster, tolerance)
      case None => """{ "error" : "There were no reachable vertices for the given point." } """
    }

    GeoTrellis.run(geojsonOp) match {
      case process.Complete(json, h) =>
        OK.json(json)
      case process.Error(message, failure) =>
        ERROR(message, failure)
    }
  }
}
