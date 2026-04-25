# Basic First Aid for Babies Skill

A specialized AI Edge Gallery skill that turns your local model into a calm, general‑information pediatric first‑aid advisor for babies (approximately 0–12 months old), focused on everyday, non‑emergency situations and helping parents and caregivers recognize when to seek medical or emergency care.

## Overview
This skill transforms the local LLM into a supportive, safety‑focused guide for parents and caregivers of young babies. It assumes that real‑world medical and emergency services **are available** and should always be used when there is any concern about serious illness or injury.

The advisor focuses on:
- Simple, low‑risk first‑aid steps for minor issues.
- Helping caregivers watch for important warning signs.
- Encouraging timely contact with pediatricians, nurse lines, or emergency services whenever there is doubt.

The skill does **not** provide medication dosing, detailed CPR steps, diagnosis, or treatment plans. It always reminds users that real clinicians and emergency services take priority over any general guidance it provides.

## Installation

### Quick Install (Android)
1. Open the **Google AI Edge Gallery** app.
2. Select the **Agent Skills** option from the main menu.
3. Select the **Gemma‑4‑E2B‑it** model by pressing the **Try it** button (wait for everything to load).
4. Press the **Skills Icon** at the bottom of the GUI.
5. Press the **Plus (+)** icon and select **Load skill from URL**.
6. Enter the following URL:  
   `https://raw.githubusercontent.com/elearningshow/gallery/main/skills/basic-first-aid-for-babies/SKILL.md`
7. Select **Add**.

### Quick Install (iPhone / iOS)
1. Open the `SKILL.md` URL for this baby first‑aid skill in Safari (for example the raw GitHub link above).
2. Tap the **Share** icon and select **Save to Files**. Save it to your **Downloads** folder.
3. Open the **Google AI Edge Gallery** app.
4. Select the **Gemma‑4‑E2B‑it** model and press **Try it**.
5. Press the **Skills Icon** at the bottom of the GUI.
6. Press the **Plus (+)** icon and select **Load skill from Local**.
7. Navigate to your **Downloads** folder and select the `SKILL.md` file you just saved.

As of April 2026, this flow has been tested with similar skills and works on Android and iOS devices when Agent Skills are enabled.

### Manual Install (Advanced/Offline)
1. Create a folder named `basic-first-aid-for-babies` in your local AI Edge Gallery skills directory.
2. Save the `SKILL.md` and `README.md` files into that folder.
3. Open the **Google AI Edge Gallery** app.
4. Navigate to **Agent Skills** and tap the **Refresh** icon to activate.

### Manual Install (Android local folder)
1. Create a folder named `basic-first-aid-for-babies` in your local AI Edge Gallery skills directory on your Android device.
2. Save the `SKILL.md` and `README.md` files into that folder.
3. Open the **Google AI Edge Gallery** app.
4. Navigate to **Agent Skills** and tap the **Refresh** icon to activate.

### Troubleshooting
If your phone crashes after attempting to run the skill, restart your phone and turn off all other skills. If the skill does not appear, double‑check that the folder name and file names are correct and tap **Refresh** again in the **Agent Skills** view.

## Usage
Trigger this skill by describing your baby’s age, what happened, and what you are seeing. For example:
* “My 4‑month‑old baby rolled off a low couch and cried but is calm now. What should I watch for?”
* “My 8‑month‑old has a small red rash in the diaper area. How can I help it at home, and when should I call the doctor?”
* “My 2‑month‑old has a mild fever but is still feeding. What should I check, and when do I need to worry?”
* “My 10‑month‑old has had mild diarrhea since yesterday but is still drinking. What warning signs should I look for?”

The advisor will restate the situation in simple language, suggest a few safe steps you can take right now, list important red flags to watch for, and explain when to contact a doctor, nurse line, or emergency services.

In any situation that sounds potentially life‑threatening (for example unresponsive, not breathing normally, very floppy, blue or gray color, severe breathing difficulty, seizure, or a serious injury), the skill will remind you:

“I can only give general information. This could be an emergency. CALL your local emergency number (such as 911) or seek urgent in‑person medical help IMMEDIATELY.”

and encourage you to follow the instructions of emergency services and pediatric professionals.

---

Copyright 2026  
Licensed under the Apache License, Version 2.0 (the "License");  
you may not use this file except in compliance with the License.  
[http://www.apache.org/licenses/LICENSE-2.0](http://www.apache.org/licenses/LICENSE-2.0)