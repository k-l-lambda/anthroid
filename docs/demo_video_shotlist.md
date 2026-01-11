# Anthroid micro-demos (≤30s each) — screen-recording shot list

Use a **series of short phone screen recordings** (each ~15–30s) instead of a single long demo.

Theme split:
- **On-device Agentic**: the agent uses phone-native tools to finish real tasks on-device.
- **Developer Tools**: terminal, code analysis, file operations.
- **Daily Assistant**: notifications, reminders, quick actions.

General rules:
- Show UI clearly (avoid fast cuts).
- Use real-ish content (non-sensitive).
- Keep text overlays minimal: 3–6 words max.
- End each clip with a tangible output (a summary, a notification, a copied snippet, a launched app).

---

## Clip A (≈20s) — "Screen automation: cross-app task"

**Goal:** show AI controlling other apps — Anthroid's core differentiator.

- 0–5s: Prompt: "打开淘宝，搜索手机壳". Overlay: "Cross-app automation".
- 5–15s: Overlay banner shows tool calls (launch_app → click_element → input_text).
- 15–20s: Taobao search results displayed. Return to Anthroid with completion message.

Notes:
- Use a real app (Taobao/WeChat/Settings).
- Overlay banner must be clearly visible.
- Demonstrates: launch_app, get_screen_elements, click_element, input_text.

## Clip B (≈20s) — "Screen automation: always stoppable"

**Goal:** show visible, interruptible agent actions.

- 0–5s: Prompt: "打开设置，找到WLAN选项".
- 5–12s: Switch to Settings app. Overlay shows "🔧 click_element". Overlay: "Always stoppable".
- 12–18s: Tap STOP button on overlay to interrupt.
- 18–20s: Return to Anthroid, show cancellation message.

## Clip C (≈25s) — "Remote agent: tmux supervisor"

**Goal:** show Anthroid as pocket supervisor for remote worker agents via tmux.

- 0–5s: Prompt: "连接工作站，查看所有agent的运行状态". Overlay: "Remote agents".
- 5–12s: Claude SSHs into workstation, runs `tmux ls` to list sessions (build-agent, deploy-agent, etc.).
- 12–20s: Claude uses `tmux capture-pane` to read agent output, summarizes status.
- 20–25s: Returns structured brief: "build-agent: compiling... deploy-agent: waiting for approval".

Notes:
- Requires pre-configured SSH key access to remote workstation.
- Remote machine should have tmux sessions with Claude CLI agents running.
- Demonstrates: Bash (ssh + tmux commands), cross-machine agent coordination.

## Clip D (≈20s) — "Remote agent: send command"

**Goal:** show intervention/control of remote agents from phone.

- 0–5s: Prompt: "告诉 deploy-agent 继续部署". Overlay: "Remote control".
- 5–15s: Claude runs `tmux send-keys -t deploy-agent "y" Enter` to send confirmation.
- 15–20s: Shows agent received input and continues. Return summary to Anthroid.

Notes:
- Can also demonstrate reading agent's ask_user_question and responding.

## Clip E (≈20s) — "Notification & reminder"

**Goal:** show agent sending real Android notifications.

- 0–5s: Prompt: "提醒我下午3点开会".
- 5–15s: Claude calls show_notification tool.
- 15–20s: Android notification appears at top. Overlay: "Native notifications".

---

## Clip F (≈20s) — "QR code setup"

**Goal:** kill tedious API key typing.

- 0–5s: Camera icon → QR scan mode.
- 5–12s: Scan QR code → config auto-filled. Overlay: "No re-typing".
- 12–20s: Return to chat, ready to use.

Notes:
- Prepare a QR code with API config beforehand.

## Clip G (≈25s) — "Camera input: visual analysis"

**Goal:** show visual context leading to actionable analysis.

- 0–8s: Tap camera → take photo of a real object (circuit board/document/error screenshot).
- 8–18s: Attach photo, prompt: "这是什么？给出5步检查清单".
- 18–25s: Claude returns numbered checklist with Markdown rendering.

## Clip H (≈20s) — "Voice input + Quick send"

**Goal:** show hands-free input and frequent command shortcuts.

- 0–6s: Long-press mic button, speak "继续".
- 6–10s: Voice transcribed to input field (with 🎤 prefix). Overlay: "Offline voice".
- 10–15s: Tap input field, Quick Send chips appear above keyboard.
- 15–20s: Tap a chip to send instantly.

Notes:
- Build up quick send candidates beforehand (send same message 5+ times).

---

## Clip I (≈25s) — "Terminal + Agent collaboration"

**Goal:** show seamless terminal and chat integration.

- 0–8s: In Chat, prompt: "检查当前目录的文件，统计代码行数".
- 8–18s: Show Claude calling Bash/Glob tools (tool cards visible).
- 18–25s: Claude returns structured summary with file counts.

Notes:
- Alternative: switch to Terminal tab, run commands, then ask Chat to summarize.

## Clip J (≈20s) — "Phone tools: clipboard + app launch"

**Goal:** quick actions using phone-native tools.

- 0–6s: Prompt: "复制这段代码到剪贴板，然后打开微信".
- 6–14s: Show tool calls: clipboard → launch_app.
- 14–20s: WeChat opens, user can paste directly. Overlay: "Phone-native tools".

## Clip K (≈20s) — "Conversation management"

**Goal:** show session history and quick resume.

- 0–5s: Tap history icon, side panel slides in. Overlay: "Session history".
- 5–12s: Browse conversations, tap one to resume.
- 12–17s: Previous context loaded, continue conversation.
- 17–20s: Long-press to bulk delete old sessions.

---

## Priority ranking

| Clip | Theme | Impact | Feasibility | Recommended |
|------|-------|--------|-------------|-------------|
| A | Screen automation (cross-app) | ⭐⭐⭐ | ✅ | **#1 Best** |
| C | Remote agent (tmux supervisor) | ⭐⭐⭐ | ✅ | **#2 Boss mode** |
| B | Screen automation (stoppable) | ⭐⭐⭐ | ✅ | #3 |
| D | Remote agent (send command) | ⭐⭐⭐ | ✅ | #4 |
| G | Camera input | ⭐⭐ | ✅ | #5 |
| H | Voice + Quick send | ⭐⭐ | ✅ | |
| I | Terminal + Agent | ⭐⭐ | ✅ | |
| J | Phone tools | ⭐⭐ | ✅ | |
| E | Notification | ⭐ | ✅ | |
| F | QR setup | ⭐ | ✅ | |
| K | History | ⭐ | ✅ | |

## Suggested publishing

- **Hero clip**: Clip A (Screen automation) — strongest on-device differentiator
- **Boss mode clip**: Clip C (Remote agent) — shows multi-agent supervision
- **Mobile experience**: Clip G (Camera) or Clip H (Voice)
- Pin top 2 on README/Release page
- Export as: `anthroid-demo-A-screen-automation.mp4`, etc.
