---
name: currency-converter
description: Converts between currencies using real-time exchange rates from the free Frankfurter API. Use when the user wants to convert money between currencies like DKK, EUR, USD, GBP, etc.
metadata:
  homepage: https://github.com/google-ai-edge/gallery/tree/main/skills/featured/currency-converter
---

# Currency Converter

Converts between 30+ currencies using live exchange rates from [Frankfurter](https://www.frankfurter.app) — free, no API key required.

## Examples

* "How much is 100 USD in EUR?"
* "Convert 500 DKK to GBP"
* "What is 1000 kr in dollars?"
* "How many Japanese yen is 50 euros?"
* "Convert 200 CHF to NOK"

## Supported currencies (ISO 4217 codes)
AUD, BGN, BRL, CAD, CHF, CNY, CZK, DKK, EUR, GBP, HKD, HUF, IDR, ILS, INR, ISK, JPY, KRW, MXN, MYR, NOK, NZD, PHP, PLN, RON, SEK, SGD, THB, TRY, USD, ZAR

## Instructions

Call the `run_js` tool with the following exact parameters:

- **script name**: `index.html`
- **data**: A JSON string with the following fields:
  - `amount`: Number — the amount to convert
  - `from`: String — source currency code (e.g. `"USD"`, `"DKK"`, `"EUR"`)
  - `to`: String — target currency code (e.g. `"EUR"`, `"GBP"`, `"JPY"`)

### Currency code hints
- "dollars" / "USD" → `"USD"`
- "euros" / "EUR" → `"EUR"`
- "kr" / "Danish krone" / "DKK" → `"DKK"`
- "pounds" / "GBP" → `"GBP"`
- "yen" / "JPY" → `"JPY"`
- "Swedish kr" / "SEK" → `"SEK"`
- "Norwegian kr" / "NOK" → `"NOK"`
