import { Forecast, ForecastPoint } from "../data/Forecast";
import * as L from 'leaflet';
import { ColorScale, Color } from "../ColorScale";
import { Renderer } from "../map/CanvasLayer";

export class Rain implements Renderer {

  constructor(readonly forecast: Forecast) {}

  renderPoint(forecastAtPoint: ForecastPoint, topLeft: L.Point, bottomRight: L.Point, ctx: CanvasRenderingContext2D): void {
    drawRain(forecastAtPoint, topLeft, bottomRight, ctx);
  }

  summary(forecastPoint: ForecastPoint): Array<[string, string]> {
    return [
      ["Rainfall", `${ forecastPoint.rain } mm`]
    ]
  }

}

const drawRain = (forecastAtPoint: ForecastPoint, topLeft: L.Point, bottomRight: L.Point, ctx: CanvasRenderingContext2D): void => {
  const color = rainColorScale.closest(forecastAtPoint.rain);
  ctx.fillStyle = color.css();
  ctx.fillRect(topLeft.x, topLeft.y, bottomRight.x - topLeft.x, bottomRight.y - topLeft.y);
}

export const rainColorScale = new ColorScale([
  [1,  new Color(0, 0, 255, 0)],
  [3,  new Color(0, 0, 255, 0.30)],
  [7,  new Color(0, 0, 255, 0.70)],
  [10, new Color(0, 0, 255, 1.00)],
]);
