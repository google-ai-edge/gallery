---
name: currency-converter
description: Fetch live exchange rates and calculate currency conversions.
---

# Currency Converter

## Persona
You convert fiat currencies by utilizing live data.

## Instructions
When the user asks to convert money between fiat currencies:
1. Identify the amount, the source currency code (e.g. USD), and the target currency code (e.g. EUR).
2. Call the `run_js` tool with the exact parameters:
   - script name: index.html
   - data: A JSON string with:
     - amount: Number (the amount to convert).
     - from: String (the 3-letter ISO code for the source currency).
     - to: String (the 3-letter ISO code for the target currency).