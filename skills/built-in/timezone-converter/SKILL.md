---
name: timezone-converter
description: Converts times between different time zones worldwide. Use when the user asks what time it is in another city or country, or wants to convert a specific time to another timezone.
---

# Timezone Converter

Converts times between any two time zones using the device's built-in timezone database. No internet required.

## Examples

* "What time is it in Tokyo right now?"
* "Convert 3pm Copenhagen time to New York time"
* "What is 09:00 London time in Sydney?"
* "If it's 14:30 in Berlin, what time is it in Los Angeles?"
* "What time is it in Dubai right now?"

## Instructions

Call the `run_js` tool with the following exact parameters:

- **script name**: `index.html`
- **data**: A JSON string with the following fields:
  - `time`: String (Optional) — the time to convert, e.g. `"15:30"` or `"3:30pm"`. If omitted, uses the current time.
  - `from_tz`: String (Optional) — the source timezone. E.g. `"Europe/Copenhagen"`, `"America/New_York"`, `"Asia/Tokyo"`. If omitted, uses the device's local timezone.
  - `to_tz`: String — the target timezone. E.g. `"America/Los_Angeles"`, `"Asia/Dubai"`, `"Australia/Sydney"`.

### Timezone name hints

Use IANA timezone names when possible. Common examples:

| City/Region | Timezone |
|-------------|----------|
| Copenhagen / Denmark | `Europe/Copenhagen` |
| London / UK | `Europe/London` |
| Paris / France | `Europe/Paris` |
| Berlin / Germany | `Europe/Berlin` |
| New York / US East | `America/New_York` |
| Los Angeles / US West | `America/Los_Angeles` |
| Chicago / US Central | `America/Chicago` |
| Tokyo / Japan | `Asia/Tokyo` |
| Shanghai / China | `Asia/Shanghai` |
| Dubai / UAE | `Asia/Dubai` |
| Sydney / Australia | `Australia/Sydney` |
| Mumbai / India | `Asia/Kolkata` |
| São Paulo / Brazil | `America/Sao_Paulo` |
| UTC | `UTC` |

If the user says a city name, map it to the appropriate IANA timezone string before calling the skill.
