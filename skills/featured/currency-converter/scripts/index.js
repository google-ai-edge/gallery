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

const SUPPORTED = new Set([
  'AUD','BGN','BRL','CAD','CHF','CNY','CZK','DKK','EUR','GBP',
  'HKD','HUF','IDR','ILS','INR','ISK','JPY','KRW','MXN','MYR',
  'NOK','NZD','PHP','PLN','RON','SEK','SGD','THB','TRY','USD','ZAR',
]);

function fmtAmount(n, currency) {
  // Use locale formatting, trim excessive decimals for large numbers
  const decimals = n >= 100 ? 2 : n >= 1 ? 4 : 6;
  return new Intl.NumberFormat('en-US', {
    minimumFractionDigits: 2,
    maximumFractionDigits: decimals,
  }).format(n) + ' ' + currency;
}

window['ai_edge_gallery_get_result'] = async (dataStr) => {
  try {
    const input = JSON.parse(dataStr);
    const amount = parseFloat(input.amount);
    const from = (input.from || '').trim().toUpperCase();
    const to = (input.to || '').trim().toUpperCase();

    if (isNaN(amount) || amount <= 0) {
      return JSON.stringify({ error: 'amount must be a positive number.' });
    }
    if (!from) return JSON.stringify({ error: 'from currency is required (e.g. "USD", "EUR", "DKK").' });
    if (!to) return JSON.stringify({ error: 'to currency is required (e.g. "USD", "EUR", "DKK").' });

    if (!SUPPORTED.has(from)) {
      return JSON.stringify({ error: `Unsupported currency: "${from}". Supported: ${[...SUPPORTED].join(', ')}.` });
    }
    if (!SUPPORTED.has(to)) {
      return JSON.stringify({ error: `Unsupported currency: "${to}". Supported: ${[...SUPPORTED].join(', ')}.` });
    }

    if (from === to) {
      return JSON.stringify({
        result: `${fmtAmount(amount, from)} = ${fmtAmount(amount, to)} (same currency)`,
        converted_amount: amount,
        rate: 1,
        from, to, amount,
      });
    }

    // Frankfurter API: free, no key, real-time ECB rates
    const url = `https://api.frankfurter.app/latest?from=${from}&to=${to}`;
    const response = await fetch(url);

    if (!response.ok) {
      return JSON.stringify({ error: `Currency API error: HTTP ${response.status}. Please try again.` });
    }

    const data = await response.json();
    const rate = data.rates?.[to];

    if (!rate) {
      return JSON.stringify({ error: `Could not get exchange rate for ${from} → ${to}.` });
    }

    const converted = amount * rate;

    return JSON.stringify({
      result: `${fmtAmount(amount, from)} = ${fmtAmount(converted, to)} (rate: 1 ${from} = ${rate.toFixed(6)} ${to}, as of ${data.date})`,
      converted_amount: parseFloat(converted.toFixed(6)),
      rate: parseFloat(rate.toFixed(6)),
      rate_date: data.date,
      from,
      to,
      amount,
    });
  } catch (e) {
    if (e.name === 'TypeError' && e.message.includes('fetch')) {
      return JSON.stringify({ error: 'Could not reach the currency service. Please check your internet connection.' });
    }
    return JSON.stringify({ error: `Currency conversion failed: ${e.message}` });
  }
};
