<!-- omit from toc -->
# AI Edge Gallery Agent Skills

<!-- omit from toc -->
## Table of contents

- [Introduction](#introduction)
- [How Skills Work](#how-skills-work)
- [Text-Only Skills: The Simplest Case](#text-only-skills-the-simplest-case)
  - [Folder Structure and Naming Convention](#folder-structure-and-naming-convention)
  - [The SKILL.md File](#the-skillmd-file)
- [JavaScript (JS) Skills](#javascript-js-skills)
  - [How JS Skills Work](#how-js-skills-work)
  - [Step-by-Step: Creating a Full JS Skill](#step-by-step-creating-a-full-js-skill)
  - [Returning an Image](#returning-an-image)
  - [Returning a Webview](#returning-a-webview)
  - [Passing Secrets](#passing-secrets)
- [Native Skills](#native-skills)
- [How to Add Skills in the Gallery App](#how-to-add-skills-in-the-gallery-app)
  - [Add from Community-Featured Skills](#add-from-community-featured-skills)
  - [Add from a URL](#add-from-a-url)
  - [Import from a Local File](#import-from-a-local-file)
- [Share Skills with Community](#share-skills-with-community)
- [Tips and Tricks](#tips-and-tricks)
  - [Link to Your Skill Homepage](#link-to-your-skill-homepage)
  - [Debug JS Skill In App](#debug-js-skill-in-app)
- [Skill Examples](#skill-examples)

## Introduction

An Agent Skill is a modular set of capabilities that extends the functional reach of a
Large Language Model (LLM) within the AI Edge Gallery app. By giving the LLM new
capabilities and domain-specific knowledge, skills reduce the need for
repetitive prompt instructions, and eliminate the barriers for LLMs to discover
and integrate new tools dynamically.

## How Skills Work

At a high level, each skill is defined by a `SKILL.md` file that contains
essential metadata and step-by-step instructions. When a user enters a prompt,
the LLM reviews the name and the descriptions of available skills appended to
its system prompt. If the user's request aligns with a skill, the LLM invokes it
automatically.

Unlike cloud-based LLMs that can spin up containers or access a terminal to run
Python scripts or CLI tools, on-device LLMs operate within a sandboxed mobile
environment. They cannot easily execute arbitrary system commands or local
scripts due to security and resource constraints.

To overcome this, AI Edge Gallery adapts by focusing on two primary execution
paths:

1. **JavaScript Skills**: Running logic inside a lightweight, hidden webview,
    which provides a cross-platform execution environment for custom logic.

1. **Native App Intents**: Leveraging the Android/iOS operating system's
    built-in capabilities (like sending email / text messages).

## Text-Only Skills: The Simplest Case

The simplest type of skill is a text-only skill, which provides the LLM with a
specific persona or scenario data without requiring external code.

### Folder Structure and Naming Convention

To create a skill, you must follow a standardized directory structure:

- **Directory Name**: Create a dedicated folder for your skill using
    **kebab-case** (e.g., `fitness-coach`).
- **SKILL.md**: This is the only required file for a text-only skill and must
    reside in the root of your skill folder.

```text
fitness-coach/
└── SKILL.md
```

### The SKILL.md File

The core of the skill is the `SKILL.md` file. It must contain a **frontmatter**
metadata section enclosed by `---` lines, followed by the **instructions** for
the LLM.

**Example `SKILL.md` for a Text-Only Skill:**

```markdown
---
name: fitness-coach
description: A cheerful, high-energy fitness coach that provides motivational workout routines.
---

# Cheerful Fitness Coach

## Persona
You are an incredibly enthusiastic and supportive fitness coach! Your goal is
to make exercise feel like a party. Always use upbeat language, plenty of
encouraging emojis, and focus on the "fun" of moving your body.

## Instructions
When the user asks for a workout:
1. Start with a high-energy greeting (e.g., "Ready to crush it?").
2. Provide a 15-minute high-intensity routine that is easy to follow.
3. End with a massive "virtual high-five" and a reminder of how awesome they are
   for showing up today! 🌟✨
```

The LLM uses the **Name** and **Description** in the metadata to determine if
the skill is relevant to a user's query. If triggered, the **Instructions** are
loaded into the model's context to guide its behavior.

## JavaScript (JS) Skills

Because Python is often unsuitable for on-device LLMs within mobile
applications, the AI Edge Gallery uses JavaScript-based scripts housed in HTML
files to execute custom logic.

### How JS Skills Work

JS skills execute logic by loading an HTML file into a hidden webview. The app
calls your skill's logic through a globally exposed asynchronous function named
`ai_edge_gallery_get_result` that must be attached to the `window` object.

### Step-by-Step: Creating a Full JS Skill

The directory structure for a JS skill is the same as for text-only skills, but
with an extra `scripts` directory to put your `index.html` and related
JavaScript files.

**Step 1: Create the directory structure**

Your folder name must be in kebab-case and match your skill name.

```text
my-js-skill/
├── SKILL.md
└── scripts/
    └── index.html
```

**Step 2: Write the `SKILL.md` file**

You must explicitly instruct the LLM to call the `run_js` tool and define the
exact JSON schema it should pass as data.

```markdown
---
name: my-js-skill
description: Calculate the hash of a given text.
---

# Calculate hash

## Instructions

Call the `run_js` tool with the following exact parameters:
- script name: index.html
- data: A JSON string with the following field:
  - text: String. The text to calculate hash for.
```

> [!TIP]
>
> If your main entry point is named `index.html`, the `script name` line in the
> instructions above is optional. The LLM will look for `index.html` within the
> `scripts/` directory by default if no other file is specified.

**Step 3: Create the `index.html` entry point**

Embed your JavaScript logic inside `scripts/index.html`. You must define an
asynchronous function `ai_edge_gallery_get_result` and expose it on `window`.
This function receives a single argument, `data`, which is a stringified JSON
string passed from the app containing the parameters from the LLM, as described
in the `SKILL.md` instructions. Inside this function, you must parse this
`data`, execute your logic, and return a **stringified JSON object**. This
returned object must contain either a `result` field on success or an `error`
field on failure.

```html
<!DOCTYPE html>
<html lang="en">
<head></head>

<body>
    <script>
        window['ai_edge_gallery_get_result'] = async (data) => {
            try {
                const jsonData = JSON.parse(data);
                const processedData = await yourImplementation(jsonData.text);

                return JSON.stringify({
                    result: processedData
                });
            } catch (e) {
                console.error(e);
                return JSON.stringify({
                    error: `Failed: ${e.message}`
                });
            }
        };

        async function yourImplementation(text) {
            return text + " processed!";
        }
    </script>
</body>

</html>
```

> [!TIP]
>
> Think of `index.html` as a **"headless" execution environment** that leverages
> the full power of the web ecosystem within a standard mobile webview. This
> setup allows you to move beyond basic scripts by making `fetch()` calls to
> third-party APIs, integrating external libraries via CDN or relative paths in
> the `<script>` tag, and utilizing advanced Web APIs like WebAssembly. For more
> complex projects, you can maintain a clean architecture by splitting your
> logic into separate `.js` files within the `scripts/` directory and importing
> them directly into your main `index.html` entry point.

### Returning an Image

To return an image to the chat, assign a base64 encoded string to the
`image.base64` field in your returned JSON.

**Example:**

```javascript
window['ai_edge_gallery_get_result'] = async (data) => {
    try {
        return JSON.stringify({
            result: "Image generated.",
            image: {
                base64: "imageBase64String"
            }
        });
    } catch (e) {
        return JSON.stringify({
            error: e.message
        });
    }
};
```

### Returning a Webview

You can return an inline webview that the app will render in the chat. You can
specify a `url` (either absolute or relative to an `assets` folder) and an
`aspectRatio` (which defaults to `1.333` if omitted).

**Example:**

```javascript
window['ai_edge_gallery_get_result'] = async (data) => {
    try {
        return JSON.stringify({
            result: "Here is the interactive view.",
            webview: {
                url: "webview.html",
                aspectRatio: 1.0
            }
        });
    } catch (e) {
        return JSON.stringify({
            error: e.message
        });
    }
};
```

Here is how files should be organized:

```text
my-interactive-skill/
├── SKILL.md
├── scripts/
│   └── index.html      <-- The hidden logic runner
└── assets/
    └── webview.html    <-- The HTML rendered in the chat UI
```

> [!TIP]
>
> You can pass dynamic data from your background logic (`index.html`) to your
> interactive UI (`webview.html`) by appending **URL query parameters** to the
> webview URL. In your script, construct the URL string to include key-value
> pairs, such as `webview.html?data=value`. Your interactive page can then
> retrieve this information using the `URLSearchParams` API to customize the
> user interface based on the LLM's output.

### Passing Secrets

If your JS script requires an API key or token, do not pass it through the LLM
prompt. Instead, the AI Edge Gallery app provides a secure mechanism: it will
display a native dialog to the user to input the required secret when the JS
skill is called, which is then passed directly to your script.

1. Add `require-secret: true` to your `SKILL.md` metadata.
1. (Optional) Add `require-secret-description: some description` to your
    `SKILL.md` metadata. This will be shown in the prompt dialog.
1. Add a second parameter to your JS entry function to receive the secret.

**Example `SKILL.md` snippet:**

```markdown
---
name: some-api-skill
description: Fetches secure data.
metadata:
  require-secret: true
  require-secret-description: Go to Github settings page to copy your token.
---
```

**Example `index.html` snippet:**

```javascript
window['ai_edge_gallery_get_result'] = async (data, secret) => {
    try {
        const jsonData = JSON.parse(data);
        // Use the secret variable to authenticate your API call
        const response = await fetch("https://api.example.com/data", {
            headers: {
                "Authorization": `Bearer ${secret}`
            }
        });
        const resultText = await response.text();

        return JSON.stringify({
            result: resultText
        });
    } catch (e) {
        return JSON.stringify({
            error: e.message
        });
    }
};
```

## Native Skills

Native skills map instructions to predefined tools in the Gallery app, such as
the `run_intent` tool. This allows the LLM to interact with the Android device
natively to perform actions like sending emails or text messages.

To use the `run_intent` tool, you must instruct the LLM to call it with two
exact parameters:

- `intent`: The native action to run.
- `parameters`: A JSON string containing the required parameter values for the
    intent.

**Example `SKILL.md` for Native Intents (Email and Text Message):**

```markdown
---
name: send-email
description: Send an email.
---

# Send email

## Instructions

Call the `run_intent` tool with the following exact parameters:

- intent: send_email
- parameters: A JSON string with the following fields:
  - extra_email: the email address to send the email to. String.
  - extra_subject: the subject of the email. String.
  - extra_text: the body of the email. String.
```

> [!IMPORTANT]
>
> While the app currently supports sending email and sending text out of the
> box, supporting additional native intent-based skills requires updating the
> app's source code. To add new capabilities, such as opening the camera,
> setting alarms, etc., you must define the logic within the app's codebase.
> Developers can refer to
> [IntentHandler.kt](https://github.com/google-ai-edge/gallery/tree/main/Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/agentchat/IntentHandler.kt)
> to see how existing intents are mapped and to learn how to register new custom
> intents for the LLM to invoke.

## How to Add Skills in the Gallery App

There are three ways to add a skill to the app:

### Add from Community-Featured Skills

We curated a list of skills contributed from our community. To try out a skill
from this list, follow the steps below:

**Steps:**

1. Enter the Agent Skills use case with your selected model, and navigate to the
   Skill Manager by tapping the "Skills" chip.

2. Tap the (+) button and select the **Add skill from featured list** option.

3. From there, simply tap a skill from the list to automatically add it to the
   system.

### Add from a URL

For easier sharing, you can host your skill on a web server, and add the skill
to the app by using the skill url.

**Steps:**

1. Enter the Agent Skills use case with your selected model, and navigate to the
   Skill Manager by tapping the "Skills" chip.

2. Tap the (+) button and select the **Load skill from URL** option.

3. Enter the skill url in the popup dialog. The url should be pointing to the
   **skill folder** itself.

   **Verify your URL**: Ensure the URL is correct by loading the `SKILL.md`
   file in your browser (e.g., `https://your/url/SKILL.md`). If the raw content
   of the file displays correctly, your URL is ready to use (excluding the
   `SKILL.md` suffix).

> [!IMPORTANT]
>
> To avoid webview loading failures, you must host your **JS skill** assets on
> a true web hosting service like GitHub Pages, Cloudflare, etc. Standard
> GitHub repository URLs and `raw.githubusercontent.com` serve files as
> text/plain, which lacks the proper MIME types required for execution. Always
> use the deployment URL provided by your web host.

> [!TIP]
>
> A tip if you want to use GitHub Pages to serve your skills:
> By default, GitHub Pages uses Jekyll to process files, which can automatically
> convert .md files into .html. Because the AI Edge Gallery app requires access
> to the raw SKILL.md file to parse instructions, you must disable this
> behavior:
>
> - Create an empty file named `.nojekyll` in the root of your repository.
> - Commit and push this file to your main branch.
>
> This ensures GitHub Pages serves your Markdown files as-is rather than
> attempting to render them as static webpages.

### Import from a Local File

You can load skills directly from your Android device's file system.

**Steps:**

1. Connect your Android device to your computer and push your entire skill
    folder (e.g., `my-js-skill/`) onto the device (e.g. to the `Download`
    folder).

    ```bash
    adb push my-js-skill/ /sdcard/Download/
    ```

2. Enter the Agent Skills use case with your selected model, and navigate to the
   Skill Manager by tapping the "Skills" chip.

3. Tap the (+) button and select the **Import local skill** option.

4. Use the Android file picker to select the directory containing your
    `SKILL.md` file. The app will copy the directory into its internal storage
    and make the skill available.

## Share Skills with Community

We've created a dedicated **GitHub Discussions** category for users to showcase
their skills. Follow these steps to share your custom skills with the global
AI Edge Gallery community:

1. Visit [Skills Discussion Category](https://github.com/google-ai-edge/gallery/discussions/categories/skills)

2. Click "New discussion" button.

3. Follow the instructions and fill in the form to share your skill.

## Tips and Tricks

### Link to Your Skill Homepage

You can make your skill name clickable within the Skill Manager UI by adding a
`homepage` field to the metadata in your `SKILL.md` file. This is a great way
to link users to your GitHub repository, documentation, or personal website.

**Example:**

```markdown
---
name: fitness-coach
description: A cheerful, high-energy fitness coach.
metadata:
  homepage: https://github.com/your-username/fitness-coach-skill
---
```

### Debug JS Skill In App

When running a JavaScript skill, you can expand the execution panel to inspect the
call details and the specific data passed to your script. This panel also provides
access to real-time console logs.

<img width="400" alt="debug_js_skill" src="https://github.com/user-attachments/assets/b5e12030-5132-4b60-aa66-93b3b7e5cb6e" />


## Skill Examples

- [**Kitchen adventure**](built-in/kitchen-adventure)
  <br>
  Act as a dungeon master for a text-based adventure set in a world where
  everyone is a sentient kitchen appliance
  <br>![text only](https://img.shields.io/badge/text%20only-777777)

- [**Calculate hash**](built-in/calculate-hash)
  <br>
  Calculate the hash of a given text.
  <br>![JS](https://img.shields.io/badge/JS-0a9396)

- [**Query Wikipedia**](built-in/query-wikipedia)
  <br>Query summary from Wikipedia for a given topic.
  <br>
  ![JS](https://img.shields.io/badge/JS-0a9396)
  ![API](https://img.shields.io/badge/API-2dc653)

- [**QR code**](built-in/qr-code)
  <br>
  Generate QR code for a given url.
  <br>![JS](https://img.shields.io/badge/JS-0a9396) ![Image](https://img.shields.io/badge/Image-0466c8)

- [**Interactive map**](built-in/interactive-map)
  <br>
  Show an interactive map view for the given location.
  <br>![JS](https://img.shields.io/badge/JS-0a9396) ![Webview](https://img.shields.io/badge/Webview-ee9b00)

- [**Mood tracker**](built-in/mood-tracker)
  <br>
  A simple mood tracking skill that stores and visualizes your daily mood and
  comments.
  <br>![JS](https://img.shields.io/badge/JS-0a9396) ![Webview](https://img.shields.io/badge/Webview-ee9b00)

- [**Virtual piano**](featured/virtual-piano)
  <br>Show a virtual piano to play music
  <br>
  ![JS](https://img.shields.io/badge/JS-0a9396)
  ![Webview](https://img.shields.io/badge/Webview-ee9b00)

- [**Text spinner**](built-in/text-spinner)
  <br>Spin the given text on my head.
  <br>
  ![JS](https://img.shields.io/badge/JS-0a9396)
  ![Webview](https://img.shields.io/badge/Webview-ee9b00)
  ![Camera](https://img.shields.io/badge/Camera-656d4a)

- [**Mood music**](featured/mood-music)
  <br>Suggest or play music based on the user's mood, including analyzing images or audio.
  <br>
  ![JS](https://img.shields.io/badge/JS-0a9396)
  ![API](https://img.shields.io/badge/API-2dc653)
  ![Webview](https://img.shields.io/badge/Webview-ee9b00)
  ![Secret](https://img.shields.io/badge/Secret-f72585)

- [**Restaurant roulette**](featured/restaurant-roulette)
  <br>Show a roulette wheel to allow user to randomly select a restaurant based on location and cuisine.
  <br>
  ![JS](https://img.shields.io/badge/JS-0a9396)
  ![API](https://img.shields.io/badge/API-2dc653)
  ![Webview](https://img.shields.io/badge/Webview-ee9b00)
  ![Secret](https://img.shields.io/badge/Secret-f72585)

- [**Send email**](built-in/send-email)
  <br>Send an email.
  <br>
  ![Native](https://img.shields.io/badge/Native-f9844a)

Check out more examples from our
[community-contributed skills](featured).
