package geotrellis.transit.services

import geotrellis.transit._
import geotrellis.network._
import geotrellis.network.graph._
import geotrellis.network.index.SpatialIndex

import javax.servlet.http.HttpServletRequest
import javax.ws.rs._
import javax.ws.rs.core.{Response, Context, MediaType, MultivaluedMap}
import geotrellis._
import geotrellis.admin._
import geotrellis.admin.Json._
import geotrellis.raster.op._
import geotrellis.statistics.op._
import geotrellis.rest._
import geotrellis.rest.op._
import geotrellis.raster._
import geotrellis.feature._
import geotrellis.feature.op.geometry.AsPolygonSet
import geotrellis.feature.rasterize.{Rasterizer, Callback}
import geotrellis.data.ColorRamps._
import geotrellis.transit.Logger

import scala.collection.JavaConversions._

import com.wordnik.swagger.annotations._
import com.wordnik.swagger.jaxrs._

import com.wordnik.swagger.sample.model.User
import com.wordnik.swagger.sample.data.UserData
import com.wordnik.swagger.sample.exception.NotFoundException

import javax.ws.rs.core.Response
import javax.ws.rs._
import com.wordnik.swagger.core.util.RestResourceUtil
import scala.collection.JavaConverters._

trait NearestVertex extends RestResourceUtil {
  @GET
  @Path("/{latitude}/{longitude}")
  @ApiOperation(value = "Get nearest OpenStreetMap node", notes = "Retrieve the closest OpenStreetMap node for use in later requests.")
  def createUser(
     @ApiParam(value = "Latitude of origin point", required = true, defaultValue="39.957572") @DefaultValue("39.957572") @PathParam("latitude") latitude: Double,
     @ApiParam(value = "Longitude of origin point", required = true, defaultValue="-75.161782") @DefaultValue("-75.161782") @PathParam("longitude") longitude: Double
   ) = {
    Response.ok.entity( Main.context.index.nearest(latitude,longitude).toString ).build
  }
}

@Path("/nearest_vertex.json/")
@Api(value = "/nearest_vertex", description = "Operations about vertices")
@Produces(Array("application/json"))
class NearestVertexJSON extends NearestVertex
