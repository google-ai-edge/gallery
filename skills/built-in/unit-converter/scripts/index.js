/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// Normalize unit strings to canonical keys
function normalizeUnit(unit) {
  const u = unit.trim().toLowerCase();
  const map = {
    // Length
    'km': 'km', 'kilometers': 'km', 'kilometre': 'km', 'kilometres': 'km', 'kilometer': 'km',
    'miles': 'miles', 'mile': 'miles', 'mi': 'miles',
    'm': 'm', 'meters': 'm', 'metre': 'm', 'metres': 'm', 'meter': 'm',
    'ft': 'ft', 'feet': 'ft', 'foot': 'ft',
    'in': 'in', 'inch': 'in', 'inches': 'in',
    'cm': 'cm', 'centimeters': 'cm', 'centimetres': 'cm', 'centimeter': 'cm',
    'yd': 'yd', 'yards': 'yd', 'yard': 'yd',
    // Weight
    'kg': 'kg', 'kilograms': 'kg', 'kilogram': 'kg', 'kilo': 'kg', 'kilos': 'kg',
    'lbs': 'lbs', 'lb': 'lbs', 'pounds': 'lbs', 'pound': 'lbs',
    'g': 'g', 'grams': 'g', 'gram': 'g',
    'oz': 'oz', 'ounces': 'oz', 'ounce': 'oz',
    't': 't', 'tonne': 't', 'tonnes': 't', 'metric ton': 't', 'metric tons': 't',
    // Temperature
    'c': 'C', 'celsius': 'C', '°c': 'C',
    'f': 'F', 'fahrenheit': 'F', '°f': 'F',
    'k': 'K', 'kelvin': 'K',
    // Speed
    'kmh': 'kmh', 'km/h': 'kmh', 'kph': 'kmh', 'kilometers per hour': 'kmh',
    'mph': 'mph', 'miles per hour': 'mph',
    'ms': 'ms', 'm/s': 'ms', 'meters per second': 'ms',
    'knots': 'knots', 'knot': 'knots', 'kn': 'knots',
    // Volume
    'l': 'l', 'liters': 'l', 'litres': 'l', 'liter': 'l', 'litre': 'l',
    'gal': 'gal', 'gallons': 'gal', 'gallon': 'gal', 'us gallon': 'gal', 'us gallons': 'gal',
    'ml': 'ml', 'milliliters': 'ml', 'millilitres': 'ml', 'milliliter': 'ml',
    'fl oz': 'floz', 'floz': 'floz', 'fluid ounce': 'floz', 'fluid ounces': 'floz',
    'cup': 'cup', 'cups': 'cup',
    // Area
    'm2': 'm2', 'sqm': 'm2', 'square meters': 'm2', 'square metres': 'm2', 'square meter': 'm2',
    'km2': 'km2', 'square km': 'km2', 'square kilometers': 'km2', 'square kilometres': 'km2',
    'acres': 'acres', 'acre': 'acres',
    'ha': 'ha', 'hectares': 'ha', 'hectare': 'ha',
    'ft2': 'ft2', 'sqft': 'ft2', 'square feet': 'ft2', 'square foot': 'ft2',
    'mi2': 'mi2', 'square miles': 'mi2', 'square mile': 'mi2',
  };
  return map[u] || null;
}

// Conversion factors: all relative to a base unit per category
// Base: km (length), kg (weight), C (temp handled separately), kmh (speed), l (volume), m2 (area)
const toBase = {
  // Length -> km
  'km': 1, 'miles': 1.60934, 'm': 0.001, 'ft': 0.0003048,
  'in': 0.0000254, 'cm': 0.00001, 'yd': 0.0009144,
  // Weight -> kg
  'kg': 1, 'lbs': 0.453592, 'g': 0.001, 'oz': 0.0283495, 't': 1000,
  // Speed -> kmh
  'kmh': 1, 'mph': 1.60934, 'ms': 3.6, 'knots': 1.852,
  // Volume -> liters
  'l': 1, 'gal': 3.78541, 'ml': 0.001, 'floz': 0.0295735, 'cup': 0.236588,
  // Area -> m2
  'm2': 1, 'km2': 1e6, 'acres': 4046.86, 'ha': 10000, 'ft2': 0.092903, 'mi2': 2.59e6,
};

function getCategory(unit) {
  const lengthUnits = ['km', 'miles', 'm', 'ft', 'in', 'cm', 'yd'];
  const weightUnits = ['kg', 'lbs', 'g', 'oz', 't'];
  const tempUnits = ['C', 'F', 'K'];
  const speedUnits = ['kmh', 'mph', 'ms', 'knots'];
  const volumeUnits = ['l', 'gal', 'ml', 'floz', 'cup'];
  const areaUnits = ['m2', 'km2', 'acres', 'ha', 'ft2', 'mi2'];

  if (lengthUnits.includes(unit)) return 'length';
  if (weightUnits.includes(unit)) return 'weight';
  if (tempUnits.includes(unit)) return 'temperature';
  if (speedUnits.includes(unit)) return 'speed';
  if (volumeUnits.includes(unit)) return 'volume';
  if (areaUnits.includes(unit)) return 'area';
  return null;
}

function convertTemperature(value, from, to) {
  let celsius;
  if (from === 'C') celsius = value;
  else if (from === 'F') celsius = (value - 32) * 5 / 9;
  else if (from === 'K') celsius = value - 273.15;

  if (to === 'C') return celsius;
  if (to === 'F') return celsius * 9 / 5 + 32;
  if (to === 'K') return celsius + 273.15;
}

function formatResult(value) {
  // Use up to 6 significant figures, strip trailing zeros
  if (Math.abs(value) >= 0.001 && Math.abs(value) < 1e10) {
    return parseFloat(value.toPrecision(6));
  }
  return parseFloat(value.toExponential(4));
}

function unitLabel(unit) {
  const labels = {
    'km': 'km', 'miles': 'miles', 'm': 'm', 'ft': 'ft', 'in': 'in', 'cm': 'cm', 'yd': 'yd',
    'kg': 'kg', 'lbs': 'lbs', 'g': 'g', 'oz': 'oz', 't': 'tonnes',
    'C': '°C', 'F': '°F', 'K': 'K',
    'kmh': 'km/h', 'mph': 'mph', 'ms': 'm/s', 'knots': 'knots',
    'l': 'liters', 'gal': 'gallons', 'ml': 'ml', 'floz': 'fl oz', 'cup': 'cups',
    'm2': 'm²', 'km2': 'km²', 'acres': 'acres', 'ha': 'ha', 'ft2': 'ft²', 'mi2': 'miles²',
  };
  return labels[unit] || unit;
}

window['ai_edge_gallery_get_result'] = async (dataStr) => {
  try {
    const input = JSON.parse(dataStr);
    const value = parseFloat(input.value);
    if (isNaN(value)) {
      return JSON.stringify({ error: 'Invalid value: must be a number.' });
    }

    const fromUnit = normalizeUnit(input.from);
    const toUnit = normalizeUnit(input.to);

    if (!fromUnit) {
      return JSON.stringify({ error: `Unknown unit: "${input.from}". Please check the unit name.` });
    }
    if (!toUnit) {
      return JSON.stringify({ error: `Unknown unit: "${input.to}". Please check the unit name.` });
    }

    const fromCat = getCategory(fromUnit);
    const toCat = getCategory(toUnit);

    if (fromCat !== toCat) {
      return JSON.stringify({
        error: `Cannot convert between ${fromCat} (${unitLabel(fromUnit)}) and ${toCat} (${unitLabel(toUnit)}). Units must be in the same category.`
      });
    }

    let result;
    if (fromCat === 'temperature') {
      result = convertTemperature(value, fromUnit, toUnit);
    } else {
      const baseValue = value * toBase[fromUnit];
      result = baseValue / toBase[toUnit];
    }

    const formatted = formatResult(result);

    return JSON.stringify({
      result: `${value} ${unitLabel(fromUnit)} = ${formatted} ${unitLabel(toUnit)}`,
      value: formatted,
      from_unit: unitLabel(fromUnit),
      to_unit: unitLabel(toUnit),
    });
  } catch (e) {
    return JSON.stringify({ error: `Conversion failed: ${e.message}` });
  }
};
