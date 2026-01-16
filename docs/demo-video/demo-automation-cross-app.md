# Clip A - Screen Automation: Cross-App Task (Chinese Version)

## Overview
- **Duration**: ~20 seconds
- **App**: JD (京东)
- **Goal**: Demonstrate AI controlling other apps - Anthroid's core differentiator

## Pre-recording Setup

### Device State
- Screen on, Anthroid app open on Chat tab
- Clean conversation (new session)
- Overlay permission enabled
- Accessibility service enabled
- JD (京东) installed and logged in

### Prompt to Use
```
打开京东，帮我找一款风格低调的，耐用的无线鼠标，价位在200元左右
```

### Expected Tool Call Sequence
1. `launch_app` → Opens JD (京东)
2. `get_screen_elements` → Reads UI hierarchy
3. `click_element` → Clicks search bar
4. `input_text` → Types "无线鼠标" or similar search term
5. `click_element` → Clicks search button

---

## Shot-by-Shot Breakdown

### Shot 1: Prompt Input (0-3s)
**Screen**: Anthroid Chat interface

**Action**:
- User types: "打开京东，帮我找一款风格低调的，耐用的无线鼠标，价位在200元左右"
- Tap send button

**Overlay Text**: "Cross-app automation" (post-edit overlay)

**Visual Notes**:
- Show keyboard briefly during typing
- Message bubble appears immediately

---

### Shot 2: App Launch (3-7s)
**Screen**: Transitions from Anthroid → JD splash → JD home

**Overlay Banner Shows**:
```
🔧 launch_app
```

**Action**:
- Anthroid minimizes
- JD (京东) launches
- Banner shows tool name in top overlay

**Visual Notes**:
- Overlay banner must be clearly visible at top of screen
- Show the app transition smoothly

---

### Shot 3: Element Discovery (7-10s)
**Screen**: JD home page

**Overlay Banner Shows**:
```
🔧 get_screen_elements
```
then:
```
🔧 click_element
```

**Action**:
- Claude reads screen elements
- Identifies search bar
- Clicks on search bar
- Search page opens with keyboard

**Visual Notes**:
- Brief pause shows Claude "thinking"
- Click highlight effect visible

---

### Shot 4: Text Input (10-14s)
**Screen**: JD search page with keyboard

**Overlay Banner Shows**:
```
🔧 input_text
```
then:
```
typing: 无线鼠标
```

**Action**:
- Text "无线鼠标" appears in search field
- Keyboard may hide or search initiated

**Visual Notes**:
- Chinese text input happening automatically
- Show the text appearing character by character if possible

---

### Shot 5: Search Execution (14-17s)
**Screen**: JD search page

**Overlay Banner Shows**:
```
🔧 click_element
```

**Action**:
- Claude clicks search button
- Search results load

**Visual Notes**:
- Results grid shows wireless mice
- Products visible on screen

---

### Shot 6: Completion (17-20s)
**Screen**: JD search results OR return to Anthroid

**Overlay Banner Shows**:
```
Completed
```

**Action**:
- Claude's task completes
- Optional: Return to Anthroid showing completion message

**Claude's Response** (if shown):
```
已完成搜索。京东正在显示"无线鼠标"的搜索结果。
```

---

## Technical Details

### Tool Input/Output Reference

**launch_app**:
```json
{"package_name": "com.jingdong.app.mall"}
```
Output: "Launched: com.jingdong.app.mall"

**get_screen_elements**:
```json
{"include_bounds": true}
```
Output: List of UI elements with bounds

**click_element**:
```json
{"text": "搜索"}
```
Output: "Clicked element: 搜索"

**input_text**:
```json
{"text": "无线鼠标"}
```
Output: "Typed: 无线鼠标"

**click_element**:
```json
{"text": "搜索"}
```
Output: "Clicked element: 搜索"

---

## Recording Tips

### Camera Setup
- Portrait orientation (phone vertical)
- Stable mount or tripod
- Clean phone screen (no smudges)

### Screen Recording
- Use system screen recorder or scrcpy
- 1080p resolution minimum
- 30fps or 60fps

### Overlay Visibility
- Ensure overlay banner is bright and readable
- Banner should contrast with app background
- Consider dark mode for better contrast

### Timing
- Don't rush - let each tool call be visible for 1-2 seconds
- Allow loading animations to complete
- Pause slightly between actions for visual clarity

### Post-Production
- Add "Cross-app automation" text overlay (0-5s)
- Consider adding subtle highlight boxes around tapped elements
- May add English subtitles for international audience

---

## Backup Scenarios

### If JD Fails
Use **Settings App** instead:
```
打开设置，找到WLAN选项
```
Tool sequence:
1. launch_app → Settings
2. get_screen_elements
3. click_element (text="WLAN")

### If Search Fails
End demo after input_text showing text in search field - still demonstrates the core capability.

---

## Checklist Before Recording

- [ ] Anthroid latest version installed
- [ ] Accessibility service enabled
- [ ] Overlay permission granted
- [ ] JD (京东) installed and working
- [ ] New/clean chat session
- [ ] Screen brightness appropriate
- [ ] Do Not Disturb mode ON (no notifications)
- [ ] Battery sufficient (>50%)
- [ ] Storage space available for recording

---

## File Naming
Output: `anthroid-demo-A-screen-automation-cn.mp4`
