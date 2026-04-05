---
name: loan-calculator
description: Calculates monthly payments, total cost, and interest for loans and mortgages. Use when the user asks about loan costs, mortgage payments, car loan calculations, or interest on a debt.
---

# Loan Calculator

Calculates loan payments and total cost. Works fully on-device with no internet required.

## Examples

* "What would my monthly payment be on a 500,000 kr loan at 4% over 20 years?"
* "Calculate a car loan: 150,000 kr, 5.5% interest, 5 years"
* "How much interest would I pay on a 200,000 loan at 3% over 10 years?"
* "What's the monthly payment for a 30-year mortgage of 2 million at 4.5%?"
* "I can afford 5,000 kr/month — how much can I borrow at 3.5% over 15 years?"

## Instructions

Call the `run_js` tool with the following exact parameters:

- **script name**: `index.html`
- **data**: A JSON string with the following fields:

  **Standard loan calculation** (monthly payment):
  - `principal`: Number — loan amount
  - `annual_rate`: Number — annual interest rate in percent (e.g. `4.5` for 4.5%)
  - `years`: Number — loan duration in years
  - `currency`: String (Optional) — currency symbol to display, e.g. `"kr"`, `"$"`, `"€"`. Default: `""`

  **Reverse calculation** (how much can I borrow?):
  - `monthly_payment`: Number — maximum affordable monthly payment
  - `annual_rate`: Number — annual interest rate in percent
  - `years`: Number — loan duration in years
  - `currency`: String (Optional)
