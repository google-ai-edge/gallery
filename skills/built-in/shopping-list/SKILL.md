---
name: shopping-list
description: Manages a persistent shopping list on-device. Use when the user wants to add items to a shopping list, check off items while shopping, view the list, or clear bought items.
---

# Shopping List

A persistent, on-device shopping list. No internet, no account needed.

## Examples

* "Add milk, eggs, and bread to my shopping list"
* "Show my shopping list"
* "I've bought the milk — check it off"
* "Remove coffee from the list"
* "Clear all checked items"
* "Wipe the whole shopping list"

## Actions

### Add item(s)
Call the `run_js` tool with:
- **script name**: `index.html`
- **data**: A JSON string with:
  - `action`: `"add"`
  - `items`: Array of strings — one or more item names, e.g. `["milk", "eggs", "bread"]`

### View list
Call the `run_js` tool with:
- **script name**: `index.html`
- **data**: A JSON string with:
  - `action`: `"list"`

### Check off item (mark as bought)
Call the `run_js` tool with:
- **script name**: `index.html`
- **data**: A JSON string with:
  - `action`: `"check"`
  - `item`: String — name of the item to check off (case-insensitive, partial match ok)

### Uncheck item
Call the `run_js` tool with:
- **script name**: `index.html`
- **data**: A JSON string with:
  - `action`: `"uncheck"`
  - `item`: String — item name to uncheck

### Remove item
Call the `run_js` tool with:
- **script name**: `index.html`
- **data**: A JSON string with:
  - `action`: `"remove"`
  - `item`: String — item name to remove entirely

### Clear bought items
Call the `run_js` tool with:
- **script name**: `index.html`
- **data**: A JSON string with:
  - `action`: `"clear_bought"`

### Wipe entire list
Call the `run_js` tool with:
- **script name**: `index.html`
- **data**: A JSON string with:
  - `action`: `"wipe"`

## Rules
- Always call `list` after modifications to show the updated list
- When the user says "I've bought X" or "I got the X" → call `check`
- When the user lists multiple items to add → pass all in the `items` array in a single call
