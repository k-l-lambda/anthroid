# Anthroid Thinking State Display Design

## Problem

Claude Code CLI emits `thinking` content blocks when using extended thinking models (e.g., `claude-sonnet-4-5`). The Anthroid app currently **silently discards** these blocks because:

1. `ClaudeCliClient.kt:498` returns `null` for any `content_block_start` that isn't `tool_use` or `text`
2. `content_block_delta` with `"type": "thinking_delta"` is not handled (only `text_delta` and `input_json_delta` are)
3. `ClaudeEvent` sealed class has no thinking event types
4. `Message` data class has no field for thinking content
5. `MessageAdapter` has no thinking UI rendering

The user sees nothing during the thinking phase -- the UI appears frozen with just a spinner until text generation begins.

## Stream-JSON Thinking Event Format

### With `--include-partial-messages` (real-time streaming)

```json
// 1. Thinking block starts
{"type":"stream_event","event":{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}}

// 2. Thinking text streams incrementally
{"type":"stream_event","event":{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"Let me analyze..."}}}

// 3. Thinking block ends
{"type":"stream_event","event":{"type":"content_block_stop","index":0}}

// 4. Text block starts (thinking is done)
{"type":"stream_event","event":{"type":"content_block_start","index":1,"content_block":{"type":"text","text":""}}}
```

### Without `--include-partial-messages` (message-level)

Thinking arrives as a complete `content[]` block inside an `assistant` message:

```json
{"type":"assistant","message":{"content":[{"type":"thinking","thinking":"...full thought..."},{"type":"text","text":"Answer"}]}}
```

## Design: Thinking State Indicator

### Option A: Animated "Thinking..." Indicator (Recommended)

Show a distinct visual state when Claude is thinking, separate from the normal streaming indicator:

```
┌─────────────────────────────────┐
│ 🧠 Thinking...                  │  ← Animated thinking indicator
│ ████░░░░░░░░  (elapsed: 12s)   │  ← Optional progress/timer
└─────────────────────────────────┘
```

When thinking completes and text starts streaming:

```
┌─────────────────────────────────┐
│ ● Here is my analysis of...     │  ← Normal streaming text
└─────────────────────────────────┘
```

**Thinking content collapsible (after completion):**

```
┌─────────────────────────────────┐
│ ▶ Thinking (1,234 tokens)       │  ← Tap to expand
│                                  │
│ Here is my analysis of the      │
│ performance issue...             │
└─────────────────────────────────┘
```

Expanded:

```
┌─────────────────────────────────┐
│ ▼ Thinking (1,234 tokens)       │  ← Tap to collapse
│ ┌─────────────────────────────┐ │
│ │ Let me analyze this step by │ │
│ │ step. First, I need to...   │ │
│ └─────────────────────────────┘ │
│                                  │
│ Here is my analysis of the      │
│ performance issue...             │
└─────────────────────────────────┘
```

### Option B: Streaming Thinking Text (transparent mode)

Show thinking text in real-time in a dimmed/italic style, then collapse it when done:

```
┌─────────────────────────────────┐
│ 🧠 Let me analyze this step     │  ← Italic, dimmed color (#999)
│ by step. First I need to...      │  ← Streams in real-time
└─────────────────────────────────┘
```

This provides maximum transparency but may overwhelm users with verbose internal reasoning.

### Recommendation: Hybrid (A + B toggle)

- **Default**: Option A (animated indicator, collapsible after completion)
- **Developer/verbose mode**: Option B (stream thinking in real-time)
- Toggle via Settings

## Implementation Plan

### 1. Add Thinking Event Types to ClaudeEvent

File: `ClaudeCliClient.kt`

```kotlin
sealed class ClaudeEvent {
    // ... existing events ...

    /** Thinking block started */
    object ThinkingStart : ClaudeEvent()

    /** Incremental thinking text (streaming) */
    data class ThinkingDelta(val content: String) : ClaudeEvent()

    /** Thinking block completed */
    data class ThinkingEnd(val fullContent: String) : ClaudeEvent()
}
```

### 2. Parse Thinking Events in `parseStreamEvent()`

File: `ClaudeCliClient.kt`, around line 486-498

```kotlin
"content_block_start" -> {
    val contentBlock = event.optJSONObject("content_block")
    val blockType = contentBlock?.optString("type", "")
    when (blockType) {
        "tool_use" -> {
            pendingToolId = contentBlock.optString("id", "")
            pendingToolName = contentBlock.optString("name", "")
            pendingToolInput.clear()
            null
        }
        "thinking" -> {
            isThinkingBlock = true        // New state flag
            pendingThinkingContent.clear() // New StringBuilder
            ClaudeEvent.ThinkingStart
        }
        "text" -> {
            if (isThinkingBlock) {
                isThinkingBlock = false
                // Thinking just ended, text is starting
            }
            null
        }
        else -> null
    }
}

"content_block_delta" -> {
    val delta = event.optJSONObject("delta")
    val deltaType = delta?.optString("type", "")
    when (deltaType) {
        "text_delta" -> ClaudeEvent.TextDelta(text)
        "thinking_delta" -> {
            val thinkingText = delta.optString("thinking", "")
            pendingThinkingContent.append(thinkingText)
            ClaudeEvent.ThinkingDelta(thinkingText)
        }
        "input_json_delta" -> { /* existing logic */ }
        else -> null
    }
}

"content_block_stop" -> {
    if (isThinkingBlock) {
        isThinkingBlock = false
        ClaudeEvent.ThinkingEnd(pendingThinkingContent.toString())
    } else if (pendingToolId != null) {
        // existing tool_use completion logic
    } else null
}
```

### 3. Extend Message Data Model

File: `ClaudeViewModel.kt`

```kotlin
data class Message(
    // ... existing fields ...
    val isThinking: Boolean = false,          // Currently in thinking state
    val thinkingContent: String? = null,       // Full thinking text (after completion)
    val thinkingTokenCount: Int? = null,       // Approximate token count for display
    val thinkingExpanded: Boolean = false       // UI state: collapsed/expanded
)
```

### 4. Handle Thinking Events in ViewModel

File: `ClaudeViewModel.kt`, in `handleEvent()`

```kotlin
is ClaudeEvent.ThinkingStart -> {
    _isThinking.value = true
    thinkingStartTime = System.currentTimeMillis()
    // Create or update the streaming message to show thinking state
    val msgId = streamingMessageId ?: UUID.randomUUID().toString()
    streamingMessageId = msgId
    updateOrAddMessage(Message(
        id = msgId,
        role = MessageRole.ASSISTANT,
        content = "",
        isStreaming = true,
        isThinking = true
    ))
}

is ClaudeEvent.ThinkingDelta -> {
    // Accumulate thinking content (for verbose mode or post-completion display)
    pendingThinkingContent += event.content
}

is ClaudeEvent.ThinkingEnd -> {
    _isThinking.value = false
    // Update message with completed thinking
    streamingMessageId?.let { id ->
        updateMessage(id) { msg ->
            msg.copy(
                isThinking = false,
                thinkingContent = event.fullContent,
                thinkingTokenCount = estimateTokens(event.fullContent)
            )
        }
    }
}
```

New state flow:

```kotlin
private val _isThinking = MutableStateFlow(false)
val isThinking: StateFlow<Boolean> = _isThinking.asStateFlow()
```

### 5. UI: Thinking Indicator Layout

New file: `res/layout/view_thinking_indicator.xml`

```xml
<LinearLayout
    android:id="@+id/thinking_container"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="horizontal"
    android:gravity="center_vertical"
    android:visibility="gone">

    <!-- Animated brain icon -->
    <ImageView
        android:id="@+id/thinking_icon"
        android:layout_width="20dp"
        android:layout_height="20dp"
        android:layout_marginEnd="8dp"
        android:src="@drawable/ic_thinking" />

    <!-- "Thinking..." with animated dots -->
    <TextView
        android:id="@+id/thinking_label"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="Thinking"
        android:textColor="#8B5CF6"
        android:textSize="14sp"
        android:textStyle="italic" />

    <!-- Elapsed timer -->
    <TextView
        android:id="@+id/thinking_timer"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginStart="8dp"
        android:textColor="#9CA3AF"
        android:textSize="12sp" />
</LinearLayout>
```

### 6. UI: Collapsible Thinking Block in MessageAdapter

Modify `item_message_assistant.xml` to include a collapsible thinking section:

```xml
<!-- Before message_content -->
<LinearLayout
    android:id="@+id/thinking_block"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="vertical"
    android:visibility="gone"
    android:layout_marginBottom="8dp">

    <!-- Tap to toggle expand/collapse -->
    <LinearLayout
        android:id="@+id/thinking_header"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="horizontal"
        android:gravity="center_vertical"
        android:background="?attr/selectableItemBackground"
        android:padding="4dp">

        <ImageView
            android:id="@+id/thinking_expand_icon"
            android:layout_width="16dp"
            android:layout_height="16dp"
            android:src="@drawable/ic_chevron_right" />

        <TextView
            android:id="@+id/thinking_summary"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginStart="4dp"
            android:textColor="#8B5CF6"
            android:textSize="13sp"
            android:textStyle="italic"
            tools:text="Thinking (1,234 tokens)" />
    </LinearLayout>

    <!-- Expandable thinking content -->
    <TextView
        android:id="@+id/thinking_content"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:visibility="gone"
        android:padding="8dp"
        android:background="#F5F3FF"
        android:textColor="#6B7280"
        android:textSize="13sp"
        android:fontFamily="monospace"
        android:maxLines="50" />
</LinearLayout>
```

### 7. UI: MessageAdapter Binding

In `MessageAdapter.kt`, update `AssistantViewHolder.bind()`:

```kotlin
// Thinking indicator (during active thinking)
val thinkingContainer = itemView.findViewById<View>(R.id.thinking_container)
val thinkingTimer = itemView.findViewById<TextView>(R.id.thinking_timer)

if (message.isThinking) {
    thinkingContainer.visibility = View.VISIBLE
    streamingIndicator.visibility = View.GONE  // Hide normal spinner
    // Start timer animation
    startThinkingTimer(thinkingTimer)
} else {
    thinkingContainer.visibility = View.GONE
    stopThinkingTimer()
}

// Collapsible thinking block (after completion)
val thinkingBlock = itemView.findViewById<View>(R.id.thinking_block)
val thinkingHeader = itemView.findViewById<View>(R.id.thinking_header)
val thinkingSummary = itemView.findViewById<TextView>(R.id.thinking_summary)
val thinkingContent = itemView.findViewById<TextView>(R.id.thinking_content)
val expandIcon = itemView.findViewById<ImageView>(R.id.thinking_expand_icon)

if (message.thinkingContent != null && !message.isThinking) {
    thinkingBlock.visibility = View.VISIBLE
    val tokenCount = message.thinkingTokenCount ?: 0
    thinkingSummary.text = "Thinking ($tokenCount tokens)"

    thinkingHeader.setOnClickListener {
        val expanded = thinkingContent.visibility == View.VISIBLE
        thinkingContent.visibility = if (expanded) View.GONE else View.VISIBLE
        expandIcon.rotation = if (expanded) 0f else 90f
        if (!expanded) {
            thinkingContent.text = message.thinkingContent
        }
    }
} else {
    thinkingBlock.visibility = View.GONE
}
```

### 8. Animated Thinking Dots

Utility for "Thinking..." with animated dots:

```kotlin
class ThinkingDotsAnimator(private val textView: TextView) {
    private var dotCount = 0
    private val handler = Handler(Looper.getMainLooper())
    private val runnable = object : Runnable {
        override fun run() {
            dotCount = (dotCount + 1) % 4
            textView.text = "Thinking" + ".".repeat(dotCount)
            handler.postDelayed(this, 500)
        }
    }

    fun start() { handler.post(runnable) }
    fun stop() { handler.removeCallbacks(runnable) }
}
```

### 9. Also Handle in ClaudeApiClient.kt

Same changes needed in the HTTP API client for consistency.

## Color Scheme

| Element | Color | Hex |
|---------|-------|-----|
| Thinking indicator text | Purple | `#8B5CF6` |
| Thinking block background | Light purple | `#F5F3FF` |
| Thinking content text | Gray | `#6B7280` |
| Thinking timer | Light gray | `#9CA3AF` |
| Expand/collapse chevron | Purple | `#8B5CF6` |

## State Machine

```
IDLE → [ThinkingStart] → THINKING → [ThinkingEnd] → STREAMING_TEXT → [MessageEnd] → IDLE
                                                   ↘ [ToolUse] → TOOL_EXECUTION → ...
```

The key insight: thinking always comes before text/tool_use in a response. The sequence is:
1. `content_block_start` (thinking)
2. `thinking_delta` (0..N)
3. `content_block_stop` (thinking ends)
4. `content_block_start` (text or tool_use)
5. Normal text/tool handling

## Files to Modify

| File | Change |
|------|--------|
| `ClaudeCliClient.kt` | Add thinking event parsing, new state vars |
| `ClaudeApiClient.kt` | Same thinking event parsing for HTTP mode |
| `ClaudeViewModel.kt` | Handle thinking events, new state flows, extend Message |
| `MessageAdapter.kt` | Thinking indicator + collapsible block rendering |
| `item_message_assistant.xml` | Add thinking block layout elements |
| `ClaudeFragment.kt` | Observe `isThinking` for overlay/status display |
| New: `ThinkingDotsAnimator.kt` | Animated dots utility |
| New: `res/drawable/ic_thinking.xml` | Brain/sparkle icon vector drawable |

## Testing

1. Use a thinking-enabled model (e.g., `claude-sonnet-4-5-20250929`)
2. Send a complex prompt that triggers extended thinking
3. Verify: thinking indicator appears immediately
4. Verify: indicator transitions to text streaming when thinking completes
5. Verify: collapsible thinking block shows after message completes
6. Verify: expand/collapse toggle works
7. Verify: non-thinking models (e.g., `claude-sonnet-4-20250514`) skip thinking UI entirely
