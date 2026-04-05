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

/**
 * Parses a time string like "15:30", "3:30pm", "9am" into { hours, minutes }.
 * Returns null if parsing fails.
 */
function parseTime(timeStr) {
  if (!timeStr) return null;
  const s = timeStr.trim().toLowerCase();

  // Match "3:30pm", "15:30", "9am", "9:00 am"
  const match = s.match(/^(\d{1,2})(?::(\d{2}))?\s*(am|pm)?$/);
  if (!match) return null;

  let hours = parseInt(match[1], 10);
  const minutes = parseInt(match[2] || '0', 10);
  const meridiem = match[3];

  if (meridiem === 'pm' && hours !== 12) hours += 12;
  if (meridiem === 'am' && hours === 12) hours = 0;

  if (hours < 0 || hours > 23 || minutes < 0 || minutes > 59) return null;
  return { hours, minutes };
}

/**
 * Validates an IANA timezone string using the Intl API.
 */
function isValidTimezone(tz) {
  try {
    Intl.DateTimeFormat(undefined, { timeZone: tz });
    return true;
  } catch {
    return false;
  }
}

/**
 * Formats a Date as a time string in the given timezone, e.g. "15:30 (Tuesday)".
 */
function formatInTimezone(date, tz) {
  const timePart = new Intl.DateTimeFormat('en-GB', {
    timeZone: tz,
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).format(date);

  const weekday = new Intl.DateTimeFormat('en-US', {
    timeZone: tz,
    weekday: 'long',
  }).format(date);

  const datePart = new Intl.DateTimeFormat('en-GB', {
    timeZone: tz,
    day: 'numeric',
    month: 'long',
    year: 'numeric',
  }).format(date);

  return { time: timePart, weekday, date: datePart };
}

/**
 * Gets the UTC offset string for a timezone, e.g. "UTC+2".
 */
function getUtcOffset(date, tz) {
  const formatter = new Intl.DateTimeFormat('en-US', {
    timeZone: tz,
    timeZoneName: 'short',
  });
  const parts = formatter.formatToParts(date);
  const tzName = parts.find(p => p.type === 'timeZoneName');
  return tzName ? tzName.value : tz;
}

window['ai_edge_gallery_get_result'] = async (dataStr) => {
  try {
    const input = JSON.parse(dataStr);

    // Determine target timezone
    const toTz = input.to_tz;
    if (!toTz) {
      return JSON.stringify({ error: 'Missing required field: to_tz (target timezone).' });
    }
    if (!isValidTimezone(toTz)) {
      return JSON.stringify({ error: `Unknown timezone: "${toTz}". Use IANA timezone names like "Europe/Copenhagen" or "America/New_York".` });
    }

    // Determine source timezone (default: device local)
    const fromTz = input.from_tz || Intl.DateTimeFormat().resolvedOptions().timeZone;
    if (!isValidTimezone(fromTz)) {
      return JSON.stringify({ error: `Unknown timezone: "${fromTz}". Use IANA timezone names like "Europe/Copenhagen" or "America/New_York".` });
    }

    let sourceDate;

    if (input.time) {
      // Parse the given time and create a Date object representing that moment in fromTz
      const parsed = parseTime(input.time);
      if (!parsed) {
        return JSON.stringify({ error: `Could not parse time: "${input.time}". Use formats like "15:30", "3:30pm", or "9am".` });
      }

      // Build a date string in the source timezone at the given H:M
      // We use today's date in the source timezone as the base
      const todayInFrom = new Intl.DateTimeFormat('en-CA', {
        timeZone: fromTz,
        year: 'numeric',
        month: '2-digit',
        day: '2-digit',
      }).format(new Date());

      // todayInFrom is YYYY-MM-DD; combine with H:M and treat as that TZ
      const isoLike = `${todayInFrom}T${String(parsed.hours).padStart(2, '0')}:${String(parsed.minutes).padStart(2, '0')}:00`;

      // Convert local time-in-fromTz to UTC Date:
      // Use a trick: format a reference date in fromTz and find the offset
      const refDate = new Date(isoLike + 'Z'); // Interpret as UTC first
      const refLocalStr = new Intl.DateTimeFormat('en-CA', {
        timeZone: fromTz,
        year: 'numeric', month: '2-digit', day: '2-digit',
        hour: '2-digit', minute: '2-digit', second: '2-digit',
        hour12: false,
      }).format(refDate).replace(/,/, '');

      // Extract H:M from the formatted string to find the offset
      // The offset = (parsed time) - (what UTC moment maps to in fromTz)
      const refParts = refLocalStr.match(/(\d+)[\/-](\d+)[\/-](\d+)[,\s]+(\d+):(\d+):(\d+)/);
      if (refParts) {
        const refHours = parseInt(refParts[4], 10);
        const refMinutes = parseInt(refParts[5], 10);
        const offsetMinutes = (parsed.hours * 60 + parsed.minutes) - (refHours * 60 + refMinutes);
        sourceDate = new Date(refDate.getTime() + offsetMinutes * 60 * 1000);
      } else {
        sourceDate = refDate;
      }
    } else {
      // No time given — use current moment
      sourceDate = new Date();
    }

    const fromFormatted = formatInTimezone(sourceDate, fromTz);
    const toFormatted = formatInTimezone(sourceDate, toTz);
    const fromOffset = getUtcOffset(sourceDate, fromTz);
    const toOffset = getUtcOffset(sourceDate, toTz);

    const inputLabel = input.time ? `${fromFormatted.time} in ${fromTz}` : `Now in ${fromTz}`;

    return JSON.stringify({
      result: `${inputLabel} (${fromOffset}) → ${toFormatted.time} in ${toTz} (${toOffset})`,
      from: {
        timezone: fromTz,
        time: fromFormatted.time,
        weekday: fromFormatted.weekday,
        date: fromFormatted.date,
        utc_offset: fromOffset,
      },
      to: {
        timezone: toTz,
        time: toFormatted.time,
        weekday: toFormatted.weekday,
        date: toFormatted.date,
        utc_offset: toOffset,
      },
    });
  } catch (e) {
    return JSON.stringify({ error: `Timezone conversion failed: ${e.message}` });
  }
};
