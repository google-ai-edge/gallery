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

window['ai_edge_gallery_get_result'] = async (dataStr) => {
  try {
    // Points the app directly to your local UI!
    const fullUrl = `ui.html?v=${Date.now()}`;

    return JSON.stringify({
      webview: {url: fullUrl},
      result:
          'Success. Tell the user to tap the preview card to play the piano.'
    });

  } catch (e) {
    console.error(e);
    return JSON.stringify({error: `Failed to load piano: ${e.message}`});
  }
};