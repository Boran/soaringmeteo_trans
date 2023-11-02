package org.soaringmeteo

import squants.Temperature
import squants.space.{Length, Meters}
import squants.thermal.Celsius

import scala.collection.SortedMap
import scala.collection.immutable.ArraySeq

case class ConvectiveClouds(
  bottom: Length, // m AMSL
  top: Length // m AMSL
)

object ConvectiveClouds {

  // TODO copied from old soarWRF, we need to check that this is correct
  // Eventually, we might want to replace with Convective Rolls Index (see https://www.academia.edu/download/66056559/RomJPhys.66.803.pdf)
  def apply(
     surfaceTemperature: Temperature,
     surfaceDewPoint: Temperature,
     groundLevel: Length,
     boundaryLayerDepth: Length,
    airData: SortedMap[Length, AirData]
   ): Option[ConvectiveClouds] = {
    // Cumuli base height is computed via Hennig formula
    val convectiveCloudsBottom: Length =
      Meters(122.6 * (surfaceTemperature - surfaceDewPoint).toCelsiusScale) + groundLevel

    val boundaryLayerHeight = groundLevel + boundaryLayerDepth

    val maybeVariables =
      airData.view
        // Keep only the values above the boundary layer height
        .filter { case (elevation, _) => elevation > boundaryLayerHeight }
        .map { case (elevation, data) => (elevation, (data.temperature, data.dewPoint)) }
        // Find the highest data where the temperature spread is less than 3°C
        .takeWhile { case (_, (temperature, dewPoint)) => temperature - dewPoint < Celsius(3) }
        .lastOption

    val convectiveCloudsTop =
      maybeVariables match {
        case None => boundaryLayerHeight // fallback to boundary layer height
        case Some((elevation, (temperature, dewPoint))) =>
          val top = elevation - Meters(122.6 * (temperature - dewPoint).toCelsiusScale)
          if (top < boundaryLayerHeight) boundaryLayerHeight else top
      }

    if (convectiveCloudsBottom < boundaryLayerHeight)
      Some(ConvectiveClouds(convectiveCloudsBottom, convectiveCloudsTop))
    else
      None
  }

}
