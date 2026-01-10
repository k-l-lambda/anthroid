# Anthroid micro-demos (≤30s each) — screen-recording shot list

Use a **series of short phone screen recordings** (each ~15–30s) instead of a single long demo.

Theme split:
- **Agents Boss Assistant**: you supervise many long-running agents on servers/workstations; Anthroid is your pocket “status + intervention” console.
- **Mobile Agentic**: the agent uses phone-native tools to finish real tasks on-device.

General rules:
- Show UI clearly (avoid fast cuts).
- Use real-ish content (non-sensitive).
- Keep text overlays minimal: 3–6 words max.
- End each clip with a tangible output (a summary, a notification, a copied snippet, a launched app).

---

## Clip A (≈20s) — “Boss mode: daily brief”

**Goal:** show Anthroid as your personal assistant that summarizes multiple remote agents.

- 0–3s: Open Anthroid → Chat tab. Overlay text: “Daily brief”.
- 3–15s: Prompt: “Summarize agent statuses: build, data, deploy. 3 bullets each + next action.”
- 15–20s: Show assistant response with structured brief.

Notes:
- If you have a real integration later, replace the prompt with a tool call that fetches a dashboard.

## Clip B (≈25s) — “Boss mode: unblock quickly”

**Goal:** show rapid decision + interruption from phone.

- 0–5s: Open conversation history → pick “deploy-agent”. Overlay: “Agent stuck”.
- 5–15s: Prompt: “What’s blocking? Give 2 options. Then draft a message: ‘retry with X’.”
- 15–25s: Copy drafted message to clipboard → paste into a chat app (short cut) OR send back into Anthroid thread.

## Clip C (≈20s) — “Boss mode: notify me when done”

**Goal:** show “pocket supervisor” behavior.

- 0–8s: Prompt: “When task finishes or errors, notify me with 1-line summary.”
- 8–20s: Trigger `show_notification` (demo) with a completion-style message.

---

## Clip D (≈20s) — “Mobile agentic: QR setup”

**Goal:** kill tedious typing.

- 0–5s: Settings → API Configuration.
- 5–15s: QR scan → fields auto-filled. Overlay: “No re-typing”.
- 15–20s: Return to chat.

## Clip E (≈25s) — “Mobile agentic: camera → checklist”

**Goal:** show visual context leading to actionable steps.

- 0–10s: Tap camera → attach photo/screenshot.
- 10–20s: Prompt: “What am I looking at? 5-step check.”
- 20–25s: Assistant returns numbered checklist.

## Clip F (≈20s) — “Mobile agentic: terminal + summary”

**Goal:** real execution + immediate brief.

- 0–12s: Switch to terminal → run 1–2 short commands (`ls`, `rg`, `python --version`).
- 12–20s: Back to chat → “Summarize output + next step.”

## Clip G (≈20s) — “Mobile agentic: phone-use tools”

**Goal:** finish a task on-device.

- 0–10s: Prompt: “Copy this snippet to clipboard and open the docs link.”
- 10–20s: Show clipboard action + browser opening (URL).

## Clip H (≈15s) — “Mobile agentic: overlay + stop”

**Goal:** visible, interruptible agent actions.

- 0–8s: Trigger an action that shows overlay banner. Overlay: “Always stoppable”.
- 8–15s: Tap Stop.

---

## Suggested export & publishing

- Export each clip as its own file: `anthroid-demo-A-boss-brief.mp4`, …
- Pin the two best clips on the README/Release page: one **Boss** + one **Mobile**.

