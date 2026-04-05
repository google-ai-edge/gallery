---
name: unit-converter
description: Converts between common units such as length, weight, temperature, speed, and volume. Use when the user wants to convert km to miles, kg to lbs, Celsius to Fahrenheit, etc.
---

# Unit Converter

This skill converts values between common everyday units. All calculations happen on-device with no internet required.

## Supported Categories

- **Length**: km, miles, meters, feet, inches, cm, yards
- **Weight**: kg, lbs, grams, ounces, tonnes
- **Temperature**: Celsius (°C), Fahrenheit (°F), Kelvin (K)
- **Speed**: km/h, mph, m/s, knots
- **Volume**: liters, gallons (US), milliliters, fluid ounces, cups
- **Area**: m², km², acres, hectares, ft², miles²

## Examples

* "Convert 100 km to miles"
* "How many pounds is 70 kg?"
* "What is 37°C in Fahrenheit?"
* "Convert 5 gallons to liters"
* "How fast is 90 km/h in mph?"

## Instructions

Call the `run_js` tool with the following exact parameters:

- **script name**: `index.html`
- **data**: A JSON string with the following fields:
  - `value`: Number — the value to convert
  - `from`: String — the source unit (e.g. "km", "kg", "C", "mph")
  - `to`: String — the target unit (e.g. "miles", "lbs", "F", "kmh")

### Unit aliases (accept any of these)

| Unit | Accepted strings |
|------|-----------------|
| Kilometers | `km`, `kilometers`, `kilometre`, `kilometres` |
| Miles | `miles`, `mile`, `mi` |
| Meters | `m`, `meters`, `metre`, `metres` |
| Feet | `ft`, `feet`, `foot` |
| Inches | `in`, `inch`, `inches` |
| Centimeters | `cm`, `centimeters`, `centimetres` |
| Yards | `yd`, `yards`, `yard` |
| Kilograms | `kg`, `kilograms`, `kilogram`, `kilo` |
| Pounds | `lbs`, `lb`, `pounds`, `pound` |
| Grams | `g`, `grams`, `gram` |
| Ounces | `oz`, `ounces`, `ounce` |
| Tonnes | `t`, `tonne`, `tonnes`, `metric ton` |
| Celsius | `c`, `celsius`, `°c` |
| Fahrenheit | `f`, `fahrenheit`, `°f` |
| Kelvin | `k`, `kelvin` |
| km/h | `kmh`, `km/h`, `kph` |
| mph | `mph`, `miles per hour` |
| m/s | `ms`, `m/s`, `meters per second` |
| Knots | `knots`, `knot`, `kn` |
| Liters | `l`, `liters`, `litres`, `liter`, `litre` |
| Gallons (US) | `gal`, `gallons`, `gallon`, `us gallon` |
| Milliliters | `ml`, `milliliters`, `millilitres` |
| Fluid ounces | `fl oz`, `floz`, `fluid ounce` |
| Cups | `cup`, `cups` |
| m² | `m2`, `sqm`, `square meters`, `square metres` |
| km² | `km2`, `square km`, `square kilometers` |
| Acres | `acres`, `acre` |
| Hectares | `ha`, `hectares`, `hectare` |
| ft² | `ft2`, `sqft`, `square feet`, `square foot` |
| miles² | `mi2`, `square miles`, `square mile` |
