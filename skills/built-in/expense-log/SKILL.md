---
name: expense-log
description: Tracks personal expenses on-device by category. Use when the user wants to log a purchase, see their spending summary, track a budget, or review recent expenses.
---

# Expense Log

Tracks your spending locally on your device. No internet, no account, no cloud.

## Examples

* "Log 89 kr for groceries"
* "I spent $12.50 on coffee"
* "Add expense: lunch 65 kr"
* "Show my expenses this week"
* "How much have I spent on food this month?"
* "Show my spending by category"
* "Delete the last expense"

## Categories (use closest match)
`food`, `transport`, `shopping`, `entertainment`, `health`, `home`, `coffee`, `restaurant`, `travel`, `other`

## Actions

### Log an expense
Call the `run_js` tool with:
- **script name**: `index.html`
- **data**: A JSON string with:
  - `action`: `"add"`
  - `amount`: Number — the amount spent
  - `category`: String — category (see list above)
  - `description`: String (Optional) — short note, e.g. `"Netto groceries"`
  - `currency`: String (Optional) — currency symbol, e.g. `"kr"`, `"$"`, `"€"`. Default: `""`
  - `date`: String (Optional) — `"today"` (default) or `"yesterday"` or `"YYYY-MM-DD"`

### View recent expenses
Call the `run_js` tool with:
- **script name**: `index.html`
- **data**: A JSON string with:
  - `action`: `"list"`
  - `days`: Number (Optional, default 7)

### View spending summary by category
Call the `run_js` tool with:
- **script name**: `index.html`
- **data**: A JSON string with:
  - `action`: `"summary"`
  - `days`: Number (Optional, default 30)

### Delete last expense
Call the `run_js` tool with:
- **script name**: `index.html`
- **data**: A JSON string with:
  - `action`: `"delete_last"`

### Wipe all data
Call the `run_js` tool with:
- **script name**: `index.html`
- **data**: A JSON string with:
  - `action`: `"wipe"`
