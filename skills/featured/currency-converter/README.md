# Currency Converter Skill

An Agent Skill for the Google AI Edge Gallery app that provides real-time and offline currency conversion capabilities, coupled with an interactive in-chat conversion card.

## Features

- **Live Market Rates**: Automatically queries reliable, public currency exchange APIs ([ExchangeRate-API open endpoint](https://open.er-api.com/) and [Frankfurter ECB API](https://api.frankfurter.app/)) without requiring an API key.
- **Offline Fallback Matrix**: Includes pre-calibrated baseline rates for 35+ major world currencies so conversions work seamlessly on mobile devices even in airplane mode or with poor network connectivity.
- **Natural Language Parsing**: Maps common symbols (`$`, `€`, `£`, `¥`, `₹`, etc.) and vernacular currency names (`dollars`, `euros`, `yen`, `pounds`, `rupees`, `pesos`, `yuan`, `won`) to standard ISO 4217 currency codes.
- **Interactive In-Chat UI**: Renders a Material 3 inspired webview card inside the chat with live amount modification, quick-amount chips (`1`, `10`, `50`, `100`, `500`, `1000`), a quick swap button (`⇄`), and currency pickers.

## Directory Structure

```text
currency-converter/
├── SKILL.md                 # Metadata and LLM instructions
├── README.md                # Documentation and setup instructions
├── scripts/
│   └── index.html           # Headless JavaScript logic runner for run_js tool
└── assets/
    └── webview.html         # Interactive webview card rendered in the chat UI
```

## How It Works

1. **User Prompt**: The user asks a question such as:
   - *"Convert 100 USD to EUR"*
   - *"How much is 50 GBP in Japanese Yen?"*
   - *"What is 250 euros in US dollars?"*
2. **Tool Invocation**: The on-device LLM inspects `SKILL.md` and executes the `run_js` tool targeting `index.html` with parameters:
   ```json
   {
     "amount": 100,
     "from": "USD",
     "to": "EUR"
   }
   ```
3. **Execution**: `index.html` runs within the headless webview, fetches the exchange rate, computes the converted value, and returns a JSON payload containing:
   - `result`: Human-readable text summary for the LLM to complete its turn.
   - `webview`: Link to `webview.html` with query parameters (`amount`, `from`, `to`, `result`, `rate`, `date`, `source`) and aspect ratio `1.15`.
4. **Chat Presentation**: The LLM outputs its response and the AI Edge Gallery app renders the interactive card directly below the message.

## Supported Currencies

Supports 160+ fiat currencies via live API, and includes embedded offline support for:
`USD`, `EUR`, `GBP`, `JPY`, `CAD`, `AUD`, `CHF`, `CNY`, `INR`, `BRL`, `MXN`, `KRW`, `SGD`, `NZD`, `HKD`, `SEK`, `NOK`, `DKK`, `PLN`, `ZAR`, `AED`, `SAR`, `THB`, `IDR`, `TRY`, `PHP`, `MYR`, `TWD`, `ILS`, `CZK`, `HUF`, `CLP`, `COP`, `EGP`, `VND`.

## Loading into AI Edge Gallery

### Option 1: Built-in with App Build
Copy this folder into `Android/src/app/src/main/assets/skills/currency-converter/` before compiling the app with `./gradlew assembleDebug`.

### Option 2: Import from Local Device Storage
1. Push the skill folder to your Android device via ADB:
   ```bash
   adb push skills/built-in/currency-converter/ /sdcard/Download/currency-converter/
   ```
2. Open AI Edge Gallery, tap the **Skills** chip.
3. Tap **(+)** -> **Import local skill** -> select the `currency-converter` directory.

### Option 3: Host via GitHub Pages / Web Host
1. Host the folder on GitHub Pages or any static web host.
2. Ensure `.nojekyll` is present in your repo root.
3. In the app, tap **(+)** -> **Load skill from URL** and enter the skill URL.
