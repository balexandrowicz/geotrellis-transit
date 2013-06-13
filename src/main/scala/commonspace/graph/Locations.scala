package commonspace.graph

import scala.collection.mutable
import spire.syntax._

/**
 * TODO: Make this a R-Tree or better. Implement 'get closest vertex to this Location'
 */
class PackedLocations(val vertexCount:Int) extends Serializable {
  /**
   * 'locations' is an Array that contains latitude and longitude
   * coordinates, where the index of the latitude coordinate is 2 times
   * the ID of the vertex at that location.
   */
  val locations = Array.ofDim[Double](vertexCount*2)

  def getLocations() = {
    val result = mutable.ListBuffer[Location]()
    val limit = vertexCount*2
    cfor(0)(_ < limit, _ + 2) { i =>
      result += Location(locations(i),locations(i+1))
    }
    result
  }

  def setLocation(vertex:Int,lat:Double,long:Double) = {
    val i = vertex*2
    locations(i) = lat
    locations(i+1) = long
  }

  def getVertexAt(lat:Double,long:Double):Int = {
    var result = -1
    var i = 0
    val limit = vertexCount*2
    while(i < limit) {
      if(locations(i) == lat) {
        i += 1
        if(locations(i) == long) {
          result = i/2
          i = vertexCount*2 // break
        }
      } else {
        i += 2
      }
    }

    return result
  }

  def getLocation(vertex:Int) = {
    Location(locations(vertex*2),locations(vertex*2+1))
  }
}