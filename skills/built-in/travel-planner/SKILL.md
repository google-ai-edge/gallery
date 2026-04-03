---
name: travel-planner
description: Creates day-by-day travel itineraries for any destination and duration. Use when the user wants to plan a trip, get activity suggestions for a city, or organize a travel schedule.
---

# Travel Planner

Generates personalized day-by-day travel itineraries. No internet needed — uses built-in knowledge of destinations worldwide.

## Examples

* "Plan a 3-day trip to Rome"
* "I'm going to Tokyo for a week — what should I do?"
* "Give me a weekend itinerary for Copenhagen"
* "Plan a 5-day family trip to Barcelona with kids"
* "I have 2 days in New York — what are the must-sees?"
* "Plan a road trip through Tuscany for 4 days"

## Instructions

This is a text-only skill. Do NOT call any tools.

When the user requests a travel plan, respond directly with a structured itinerary:

### Format

**Trip overview** (1–2 sentences about the destination and vibe)

For each day:
- **Day N — [Theme or area focus]**
  - Morning: activity + brief tip
  - Afternoon: activity + brief tip
  - Evening: restaurant suggestion or activity

End with:
- **Practical tips** (2–3 bullet points: transport, best season, local customs, or budget hint)
- **Don't miss**: 1–2 hidden gems or lesser-known highlights

### Rules
- Tailor the pace to the duration — don't overload short trips
- Group activities by geographic proximity to minimize travel time within each day
- For family trips: include kid-friendly options, note what to skip with small children
- For solo trips: mention safety and social spots
- Keep descriptions concise — practical over poetic
- If the user gives a vague destination (e.g., "Italy"), ask which city or region before generating; otherwise generate immediately
