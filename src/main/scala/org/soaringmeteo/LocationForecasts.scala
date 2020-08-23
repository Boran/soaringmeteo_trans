package org.soaringmeteo

import java.time.{LocalDate, OffsetDateTime}
import java.time.format.DateTimeFormatter

import io.circe.{Encoder, Json}
import org.soaringmeteo.GfsForecast.pressureLevels
import squants.motion.Pressure
import squants.space.Length

import scala.util.chaining._

/**
 * All the forecast data for one location and several days, with
 * high-level information extracted from the content of each GFS forecast
 * (e.g., thunderstorm risk per day)
 */
case class LocationForecasts(
  elevation: Length,
  dayForecasts: Seq[DayForecast]
)

case class DayForecast(
  date: LocalDate,
  hourForecasts: Seq[GfsForecast],
  thunderstormRisk: Int /* Between 0 and 4, for the entire day (not just the forecast period. */
)

object LocationForecasts {

  def apply(location: Point, forecasts: Seq[GfsForecast]): LocationForecasts = {
    val isRelevantAtLocation = isRelevant(location)
    // HACK We reuse the same data structure, `GfsForecast`, but we give a different meaning to
    // the `accumulatedRain` field: it does not contain anymore the accumulated rain, but only
    // the rain that fell during the forecast period.
    val forecastsWithRainPerPeriod: Seq[GfsForecast] =
      forecasts.foldLeft((List.newBuilder[GfsForecast], Option.empty[GfsForecast])) {
        case ((builder, maybePreviousForecast), forecast) =>
          maybePreviousForecast match {
            case None =>
              builder += forecast // First forecast (+3h) is special, it contains the accumulated
                                  // rain since the forecast initialization time, which is equivalent
                                  // to the rain that fell during the forecast period
            case Some(previousForecast) =>
              builder += forecast.copy(
                accumulatedRain = forecast.accumulatedRain - previousForecast.accumulatedRain,
                accumulatedConvectiveRain = forecast.accumulatedConvectiveRain - previousForecast.accumulatedConvectiveRain
              )
          }
          (builder, Some(forecast))
      }._1.result()
    LocationForecasts(
      elevation = forecasts.head.elevation,
      dayForecasts =
        forecastsWithRainPerPeriod
          .filter(forecast => isRelevantAtLocation(forecast.time)) // Keep only forecasts during the day, and around noon
          .groupBy(_.time.toLocalDate)
          .filter { case (_, forecasts) => forecasts.nonEmpty }
          .toSeq
          .sortBy(_._1)
          .map { case (forecastDate, hourForecasts) =>
            DayForecast(
              forecastDate,
              hourForecasts.sortBy(_.time),
              if (hourForecasts.sizeIs == 3) thunderstormRisk(hourForecasts(0), hourForecasts(1), hourForecasts(2))
              else {
                // TODO Print warning?
                0
              }

            )
          }
    )
  }

  def isRelevant(location: Point): OffsetDateTime => Boolean = {
    // Transform longitude so that it goes from 0 to 360 instead of 180 to -180
    val normalizedLongitude = 180 - location.longitude
    // Width of each zone, in degrees
    val zoneWidthDegrees = 360 / Settings.numberOfForecastsPerDay
    // Width of each zone, in hours
    val zoneWidthHours   = Settings.gfsForecastTimeResolution
    // Noon time offset is 12 around prime meridian, 0 on the other side of the
    // earth, and 6 on the east and 21 on the west.
    // For example, a point with a longitude of 7 (e.g., Bulle) will have a normalized
    // longitude of 173. If we divide this number of degrees by the width of a zone,
    // we get its zone number, 4. Finally, we multiply this zone number by the number of
    // hours of a zone, we get the noon time for this longitude, 12.
    val noonHour =
      ((normalizedLongitude + (zoneWidthDegrees / 2.0)) % 360).doubleValue.round.toInt / zoneWidthDegrees * zoneWidthHours

    val allHours = (0 until 24 by Settings.gfsForecastTimeResolution).to(Set)

    val relevantHours: Set[Int] =
      (1 to Settings.relevantForecastPeriodsPerDay).foldLeft((allHours, Set.empty[Int])) {
        case ((hs, rhs), _) =>
          val rh = hs.minBy(h => math.min(noonHour + 24 - h, math.abs(h - noonHour)))
          (hs - rh, rhs + rh)
      }._2

    time => {
      relevantHours.contains(time.getHour)
    }
  }

  // FIXME Generalize to an arbitrary number of forecasts for the day
  def thunderstormRisk(
    forecastMorning: GfsForecast,
    forecastNoon: GfsForecast,
    forecastAfternoon: GfsForecast
  ): Int = {
    // Blindly copied from src/makeGFSJs.pas. We seriously need to check the formulas again.
    val baseCAPE = (forecastNoon.cape.toGrays + forecastAfternoon.cape.toGrays) / 100
    val factorCAPE =
      if (baseCAPE >= 2) baseCAPE
      else baseCAPE - 4 * (2 - baseCAPE)

    val spreadAltiMorning   = spreadAlti(forecastMorning)
    val spreadAltiNoon      = spreadAlti(forecastNoon)
    val spreadAltiAfternoon = spreadAlti(forecastAfternoon)
    val factorSpread        = (spreadAltiMorning + spreadAltiNoon + spreadAltiAfternoon) / 3

    val factorSensibleHeat =
      (forecastMorning.sensibleHeatNetFlux.toWattsPerSquareMeter / 10).min(10)
      .pipe { rawValue =>
        if (rawValue >= 3) rawValue
        else rawValue - 3 * (3 - rawValue)
      }

    val factorConvection = (
      (
        forecastMorning.cloudCover.conv +
        forecastNoon.cloudCover.conv +
        forecastAfternoon.cloudCover.conv
      ) / 10 +
      // FIXME This looks wrong, we should not add accumulated rain over and over
      forecastMorning.accumulatedConvectiveRain.toMillimeters +
      forecastNoon.accumulatedConvectiveRain.toMillimeters +
      forecastAfternoon.accumulatedConvectiveRain.toMillimeters
    ) / 2

    val factorG = factorCAPE + factorConvection + factorSensibleHeat - factorSpread

    if (factorG < -3) 0
    else if (factorG < 3) 1
    else if (factorG < 8) 2
    else if (factorG < 18) 3
    else 4
  }

  /** Average “spread” value for altitudes above 3000 m */
  def spreadAlti(forecast: GfsForecast): Double = {
    spreadAndTds(forecast)
      .map { case (pressure, (spread, _)) => (pressure, spread) }
      .filter { case (pressure, _) => pressure.toPascals <= 70000 }
      .values
      .pipe(spreads => spreads.sum / spreads.size)
  }

  def spreadAndTds(forecast: GfsForecast): Map[Pressure, (Double, Double)] = {
    forecast.atPressure.map { case (pressure, variables) =>
      val temperature = variables.temperature.toCelsiusScale
      val exponentBase10 = 7.5 * temperature / (237.7 + temperature) // Computation of the main base 10 power exponent used below from the air temperature in °C
      val vaporPressSat = 6.11 * math.pow(10, exponentBase10) // Computation of the water vapor pressure at saturation
      // Computation of the water vapor pressure from relative humidity RH and water vapor pressure at saturation
      val vaporPress =
        if (variables.relativeHumidity > 1) (variables.relativeHumidity / 100) * vaporPressSat
        else 0.01 * vaporPressSat
      // Computation of dew point from the water vapor pressure. FIXME Why don’t we use log10 here?
      val td = (-430.22 + 237.7 * math.log(vaporPress) / (-math.log(vaporPress) + 19.08)).max(temperature)
      (pressure, (temperature - td, td))
    }
  }

  val jsonEncoder: Encoder[LocationForecasts] =
    Encoder.instance { locationForecasts =>
      Json.obj(
        "h" -> Json.fromInt(locationForecasts.elevation.toMeters.round.toInt),
        "d" -> Json.arr(
          locationForecasts.dayForecasts.map { dayForecast =>
            Json.obj(
              "th" -> Json.fromInt(dayForecast.thunderstormRisk),
              "h" -> Json.arr(
                dayForecast.hourForecasts.map { forecast =>
                  Json.obj(
                    "t" -> Json.fromString(forecast.time.format(DateTimeFormatter.ISO_DATE_TIME)),
                    "bl" -> Json.obj(
                      "h" -> Json.fromInt(forecast.boundaryLayerHeight.toMeters.round.toInt),
                      "u" -> Json.fromInt(forecast.boundaryLayerWind.u.toKilometersPerHour.round.toInt),
                      "v" -> Json.fromInt(forecast.boundaryLayerWind.v.toKilometersPerHour.round.toInt)
                    ),
                    "c" -> Json.obj(
                      "e" -> Json.fromBigDecimal(forecast.cloudCover.entire),
                      "l" -> Json.fromBigDecimal(forecast.cloudCover.low),
                      "m" -> Json.fromBigDecimal(forecast.cloudCover.middle),
                      "h" -> Json.fromBigDecimal(forecast.cloudCover.high),
                      "c" -> Json.fromBigDecimal(forecast.cloudCover.conv),
                      "b" -> Json.fromBigDecimal(forecast.cloudCover.boundary)
                    ),
                    "p" -> Json.obj(pressureLevels.map { pressure =>
                      val variables = forecast.atPressure(pressure)
                      (pressure.toPascals.round.toInt / 100).toString -> Json.obj(
                        "h" -> Json.fromInt(variables.geopotentialHeight.toMeters.round.toInt),
                        "t" -> Json.fromBigDecimal(variables.temperature.toCelsiusScale),
                        "rh" -> Json.fromBigDecimal(variables.relativeHumidity),
                        "u" -> Json.fromInt(variables.wind.u.toKilometersPerHour.round.toInt),
                        "v" -> Json.fromInt(variables.wind.v.toKilometersPerHour.round.toInt)
                      )
                    }: _*),
                    "s" -> Json.obj(
                      "h" -> Json.fromInt(forecast.elevation.toMeters.round.toInt),
                      "t" -> Json.fromBigDecimal(forecast.surfaceTemperature.toCelsiusScale),
                      "rh" -> Json.fromBigDecimal(forecast.surfaceRelativeHumidity),
                      "u" -> Json.fromInt(forecast.surfaceWind.u.toKilometersPerHour.round.toInt),
                      "v" -> Json.fromInt(forecast.surfaceWind.v.toKilometersPerHour.round.toInt)
                    ),
                    "iso" -> Json.fromInt(forecast.isothermZero.toMeters.round.toInt),
                    "r" -> Json.obj(
                      "t" -> Json.fromInt(forecast.accumulatedRain.toMillimeters.round.toInt),
                      "c" -> Json.fromInt(forecast.accumulatedConvectiveRain.toMillimeters.round.toInt)
                    ),
                    "mslet" -> Json.fromInt(forecast.mslet.toPascals.round.toInt / 100) // hPa
                    // TODO Irradiance, CIN, snow
                  )
                }: _*
              )
            )
          }: _*
        )
      )
    }

}