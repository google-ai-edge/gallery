---
name: mood-music
description: A skill to suggest or play music based on the user's mood, including analyzing images or audio, by querying available genres and generating music via the Loudly API.
metadata:
  require-secret: true
  require-secret-description: you can get api key from https://www.loudly.com/developers/apps after registering an account
  homepage: https://github.com/google-ai-edge/gallery/tree/main/skills/featured/mood-music
---

# Mood Music

## Instructions

You MUST use a strict two-step process to generate music. This ensures you only request musical genres that are currently supported by the Loudly API.

### Step 1: Fetch Available Genres
Call the `run_js` tool with `get_genres.html` as the script name and an empty JSON payload (`{}`). 
- This will return a list of currently available genres and their descriptions. 
- You MUST review this list to understand the available musical palettes.

### Step 2: Analyze and Generate
Once you have the valid genres, map the user's request to the best fit and call `run_js` with `index.html` to generate the track.

**Handling Inputs:**
- **Text Inputs**: Translate abstract mood requests into a concrete, matching genre from the fetched list, along with an appropriate energy level.
- **Media Inputs (Images/Audio)**: If the user provides an image or audio clip, analyze the media to determine its underlying mood, atmosphere, or vibe. Translate this analysis strictly into one of the fetched genres.

**Generation Payload (for index.html):**
Your JSON payload for `index.html` MUST strictly use these text fields to represent the vibe:
- **genre**: String, Required. MUST be an exact match to a genre name retrieved in Step 1.
- **genre_blend**: String, Optional. A secondary genre to blend (also from Step 1).
- **duration**: Integer, Optional. Length in seconds (30-420). Default is 120.
- **energy**: String, Optional. Vibe ("low", "high", "original").
- **bpm**: Integer, Optional. Specific tempo in Beats Per Minute.

### Invocation Triggers
You should invoke this skill when the user:
- Asks for music for a specific mood.
- Asks for playlist ideas for a vibe.
- Uploads an image or audio clip and asks for music to match it.
