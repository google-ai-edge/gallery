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

window['ai_edge_gallery_get_result'] = async (dataStr, secret) => {
  try {
    const jsonData = JSON.parse(dataStr || '{}');
    const location = jsonData.location || 'Mountain View, CA';
    const cuisine = jsonData.cuisine || 'Sushi';

    // // 1. Generate the data
    // const prefixes = ["The", "Golden", "Epic", "Supreme", "Authentic",
    // "Local", "Happy", "Urban", "Secret", "Ninja"]; const suffixes = ["Spot",
    // "House", "Palace", "Kitchen", "Diner", "Grill", "Eats", "Bites", "Cafe",
    // "Express"];

    // let places = [];
    // for(let i = 0; i < 10; i++) {
    //   places.push(`${prefixes[i]} ${cuisine} ${suffixes[i]}`);
    // }

    const GEMINI_API_KEY = secret || 'YOUR_GEMINI_API_KEY';
    if (GEMINI_API_KEY === 'YOUR_GEMINI_API_KEY') {
      console.warn('GEMINI_API_KEY is missing. Calls to Gemini API will likely fail.');
    }

    // We simplified the prompt because we are using strict JSON mode now
    const prompt = `List 10 real, highly-rated ${cuisine} restaurants in ${
        location}. Within 15 miles location range`;

    const url =
        `https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=${
            GEMINI_API_KEY}`;

    let places = [];

    try {
      const response = await fetch(url, {
        method: 'POST',
        headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({
          contents: [{parts: [{text: prompt}]}],
          // THIS IS THE MAGIC FIX: Forces Gemini to return pure JSON
          generationConfig: {
            responseMimeType: 'application/json',
            // We tell it exactly what shape the JSON should be: an array of
            // strings
            responseSchema: {type: 'ARRAY', items: {type: 'STRING'}}
          }
        })
      });

      if (!response.ok) {
        const errorText = await response.text();
        throw new Error(`HTTP Error ${response.status}`);
      }

      const data = await response.json();

      if (data.candidates && data.candidates.length > 0) {
        const rawText = data.candidates[0].content.parts[0].text;
        places = JSON.parse(rawText);
        places.sort(() => 0.5 - Math.random());
      } else {
        throw new Error('Empty response from AI');
      }
    } catch (apiError) {
      console.warn('Gemini API failed.', apiError);
      // IF IT FAILS, THE WHEEL WILL NOW SHOW YOU THE EXACT ERROR MESSAGE!
      let errorMessage = apiError.message;
      if (errorMessage.length > 15)
        errorMessage = errorMessage.substring(0, 15);
      places = ['Error:', errorMessage, 'Check', 'Console'];
    }

    // 2. Compress the data
    const placeString = places.join('|');
    const compressedData = btoa(unescape(encodeURIComponent(placeString)));

    // 3. Build the REAL local URL
    const baseUrl = 'webview.html';
    const fullUrl = `${baseUrl}?c=${encodeURIComponent(cuisine)}&l=${
        encodeURIComponent(location)}&data=${compressedData}&v=${Date.now()}`;

    // 4. Return ONLY the webview. This guarantees the preview card appears and
    // stops the AI from overriding it!
    return JSON.stringify({
      webview: {url: fullUrl},
      result:
          'Here is the restaurant roulette wheel you requested! Tap the preview card to spin it and pick a winner!'
    });

  } catch (e) {
    console.error(e);
    return JSON.stringify({error: `Failed to load roulette: ${e.message}`});
  }
};