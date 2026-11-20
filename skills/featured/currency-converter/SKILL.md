---
name: currency-converter
description: Convert amounts between different world currencies with real-time exchange rates and interactive calculator.
metadata:
  homepage: https://github.com/google-ai-edge/gallery/tree/main/skills/built-in/currency-converter
---

# Currency Converter

This skill converts monetary amounts between different world currencies using up-to-date exchange rates and provides an interactive converter view.

## Examples

* "Convert 100 USD to EUR"
* "How much is 50 GBP in Japanese Yen?"
* "What is 250 euros in US dollars?"
* "Convert 5000 JPY to CAD"
* "How many rupees is 20 dollars?"
* "Convert 75 AUD to CHF"
* "100 CAD to MXN"

## Instructions

Call the `run_js` tool using `index.html` and a JSON string for `data` with the following fields:

- **amount**: Required. Number. The numerical amount of money to convert (e.g., 100, 49.99).
- **from**: Required. String. The 3-letter source currency ISO code (e.g., "USD", "EUR", "GBP", "JPY", "CAD", "AUD", "INR", "CNY", "CHF"). If the user provided symbols or currency names (such as "$", "€", "£", "¥", "₹", "dollars", "euros", "yen", "pounds", "rupees"), convert them to standard uppercase 3-letter codes.
- **to**: Required. String. The 3-letter target currency ISO code (e.g., "EUR", "USD", "JPY", "GBP", "INR", "CAD", "AUD"). If omitted by the user, default to "USD".

**Guidelines for Response:**
- When the tool returns the conversion result, state the converted amount clearly along with the exchange rate and date.
- Present both the source amount and the converted amount formatted with appropriate symbols or currency codes.
- Do NOT make up exchange rates before calling the tool. Always call `run_js` to get the real rate.
