package com.anthroid.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

/**
 * Accessibility service for screen automation.
 * Provides tools for Claude to interact with phone UI:
 * - Read screen text
 * - Find elements by text/id/class
 * - Click, type, swipe, scroll
 * - Press system buttons (back, home)
 */
class AnthroidAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "AnthroidA11yService"

        @Volatile
        private var instance: AnthroidAccessibilityService? = null

        @Volatile
        private var isInterrupted: Boolean = false

        private var overlay: ScreenAutomationOverlay? = null

        fun getInstance(): AnthroidAccessibilityService? = instance

        fun isRunning(): Boolean = instance != null

        /**
         * Interrupt the current operation.
         */
        fun interrupt() {
            isInterrupted = true
            overlay?.setInterrupted()
            Log.i(TAG, "Operation interrupted by user")
        }

        /**
         * Check if operation was interrupted and reset flag.
         */
        private fun checkInterrupted(): Boolean {
            if (isInterrupted) {
                isInterrupted = false
                return true
            }
            return false
        }

        /**
         * Show overlay for an operation.
         */
        private fun showOverlay(operationText: String) {
            val service = instance ?: return
            if (overlay == null) {
                overlay = ScreenAutomationOverlay.getInstance(service)
            }
            isInterrupted = false
            overlay?.show(operationText) { interrupt() }
        }

        /**
         * Complete the overlay with result.
         */
        private fun completeOverlay(resultText: String) {
            overlay?.setCompleted(resultText)
        }

        /**
         * Get all visible text on screen.
         */
        fun getScreenText(): String {
            val service = instance ?: return "Error: Accessibility service not enabled"
            val root = service.rootInActiveWindow ?: return "Error: Cannot access screen content"

            val texts = mutableListOf<String>()
            collectTexts(root, texts)
            root.recycle()

            return if (texts.isEmpty()) {
                "No text found on screen"
            } else {
                texts.joinToString("\n")
            }
        }

        /**
         * Get screen elements as structured JSON.
         */
        fun getScreenElements(includeInvisible: Boolean = false): String {
            val service = instance ?: return """{"error": "Accessibility service not enabled"}"""
            val root = service.rootInActiveWindow ?: return """{"error": "Cannot access screen content"}"""

            val elements = JSONArray()
            collectElements(root, elements, includeInvisible, 0)
            root.recycle()

            return JSONObject().apply {
                put("elements", elements)
                put("count", elements.length())
            }.toString(2)
        }

        /**
         * Find element by text content.
         */
        fun findElementByText(text: String, exactMatch: Boolean = false): String {
            val service = instance ?: return """{"error": "Accessibility service not enabled"}"""
            val root = service.rootInActiveWindow ?: return """{"error": "Cannot access screen content"}"""

            val results = JSONArray()
            findByText(root, text, exactMatch, results)
            root.recycle()

            return JSONObject().apply {
                put("found", results.length())
                put("elements", results)
            }.toString(2)
        }

        /**
         * Click element by text.
         */
        fun clickByText(text: String): String {
            showOverlay("Clicking: $text")

            if (checkInterrupted()) {
                return "Error: Operation interrupted"
            }

            val service = instance ?: return "Error: Accessibility service not enabled".also { completeOverlay(it) }
            val root = service.rootInActiveWindow ?: return "Error: Cannot access screen content".also { completeOverlay(it) }

            val node = findClickableByText(root, text)
            val result = if (node != null) {
                val clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                node.recycle()
                if (clicked) "Clicked: $text" else "Click failed: $text"
            } else {
                "Element not found: $text"
            }
            root.recycle()
            completeOverlay(result)
            return result
        }

        /**
         * Click at specific coordinates.
         */
        fun clickAt(x: Float, y: Float): String {
            showOverlay("Clicking at ($x, $y)")

            if (checkInterrupted()) {
                return "Error: Operation interrupted"
            }

            val service = instance ?: return "Error: Accessibility service not enabled".also { completeOverlay(it) }

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                return "Error: Click at position requires Android 7.0+".also { completeOverlay(it) }
            }

            val path = Path()
            path.moveTo(x, y)

            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
                .build()

            val dispatched = service.dispatchGesture(gesture, null, null)
            val result = if (dispatched) "Clicked at ($x, $y)" else "Click failed at ($x, $y)"
            completeOverlay(result)
            return result
        }

        /**
         * Type text into focused input field.
         */
        fun inputText(text: String): String {
            showOverlay("Typing: ${text.take(20)}${if (text.length > 20) "..." else ""}")

            if (checkInterrupted()) {
                return "Error: Operation interrupted"
            }

            val service = instance ?: return "Error: Accessibility service not enabled".also { completeOverlay(it) }
            val root = service.rootInActiveWindow ?: return "Error: Cannot access screen content".also { completeOverlay(it) }

            // Find focused node or first editable field
            val focusedNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?: findFirstEditable(root)

            val result = if (focusedNode != null) {
                val args = Bundle()
                args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                val success = focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                focusedNode.recycle()
                if (success) "Typed: $text" else "Type failed"
            } else {
                "No input field focused"
            }
            root.recycle()
            completeOverlay(result)
            return result
        }

        /**
         * Perform swipe gesture.
         */
        fun swipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long = 300): String {
            showOverlay("Swiping...")

            if (checkInterrupted()) {
                return "Error: Operation interrupted"
            }

            val service = instance ?: return "Error: Accessibility service not enabled".also { completeOverlay(it) }

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                return "Error: Swipe requires Android 7.0+".also { completeOverlay(it) }
            }

            val path = Path()
            path.moveTo(startX, startY)
            path.lineTo(endX, endY)

            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
                .build()

            val dispatched = service.dispatchGesture(gesture, null, null)
            val result = if (dispatched) {
                "Swiped from ($startX, $startY) to ($endX, $endY)"
            } else {
                "Swipe failed"
            }
            completeOverlay(result)
            return result
        }

        /**
         * Long press at coordinates.
         */
        fun longPressAt(x: Float, y: Float, durationMs: Long = 1000): String {
            showOverlay("Long pressing at ($x, $y)")

            if (checkInterrupted()) {
                return "Error: Operation interrupted"
            }

            val service = instance ?: return "Error: Accessibility service not enabled".also { completeOverlay(it) }

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                return "Error: Long press requires Android 7.0+".also { completeOverlay(it) }
            }

            val path = Path()
            path.moveTo(x, y)

            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
                .build()

            val dispatched = service.dispatchGesture(gesture, null, null)
            val result = if (dispatched) "Long pressed at ($x, $y)" else "Long press failed"
            completeOverlay(result)
            return result
        }

        /**
         * Press system back button.
         */
        fun pressBack(): String {
            showOverlay("Pressing back")
            val service = instance ?: return "Error: Accessibility service not enabled".also { completeOverlay(it) }
            val success = service.performGlobalAction(GLOBAL_ACTION_BACK)
            val result = if (success) "Pressed back" else "Back press failed"
            completeOverlay(result)
            return result
        }

        /**
         * Press system home button.
         */
        fun pressHome(): String {
            showOverlay("Pressing home")
            val service = instance ?: return "Error: Accessibility service not enabled".also { completeOverlay(it) }
            val success = service.performGlobalAction(GLOBAL_ACTION_HOME)
            val result = if (success) "Pressed home" else "Home press failed"
            completeOverlay(result)
            return result
        }

        /**
         * Open recent apps / overview.
         */
        fun openRecents(): String {
            showOverlay("Opening recents")
            val service = instance ?: return "Error: Accessibility service not enabled".also { completeOverlay(it) }
            val success = service.performGlobalAction(GLOBAL_ACTION_RECENTS)
            val result = if (success) "Opened recents" else "Open recents failed"
            completeOverlay(result)
            return result
        }

        /**
         * Open notifications panel.
         */
        fun openNotifications(): String {
            showOverlay("Opening notifications")
            val service = instance ?: return "Error: Accessibility service not enabled".also { completeOverlay(it) }
            val success = service.performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            val result = if (success) "Opened notifications" else "Open notifications failed"
            completeOverlay(result)
            return result
        }

        /**
         * Scroll in a direction within a scrollable element.
         */
        fun scroll(direction: String): String {
            showOverlay("Scrolling $direction")

            if (checkInterrupted()) {
                return "Error: Operation interrupted"
            }

            val service = instance ?: return "Error: Accessibility service not enabled".also { completeOverlay(it) }
            val root = service.rootInActiveWindow ?: return "Error: Cannot access screen content".also { completeOverlay(it) }

            val scrollable = findScrollable(root)
            val result = if (scrollable != null) {
                val action = when (direction.lowercase()) {
                    "up", "forward" -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                    "down", "backward" -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                    else -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                }
                val success = scrollable.performAction(action)
                scrollable.recycle()
                if (success) "Scrolled $direction" else "Scroll $direction failed"
            } else {
                "No scrollable element found"
            }
            root.recycle()
            completeOverlay(result)
            return result
        }

        // Helper methods

        private fun collectTexts(node: AccessibilityNodeInfo, texts: MutableList<String>) {
            node.text?.toString()?.takeIf { it.isNotBlank() }?.let { texts.add(it) }
            node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { texts.add(it) }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                collectTexts(child, texts)
                child.recycle()
            }
        }

        private fun collectElements(
            node: AccessibilityNodeInfo,
            elements: JSONArray,
            includeInvisible: Boolean,
            depth: Int
        ) {
            if (!includeInvisible && !node.isVisibleToUser) return

            val bounds = Rect()
            node.getBoundsInScreen(bounds)

            val element = JSONObject().apply {
                put("class", node.className?.toString() ?: "unknown")
                put("text", node.text?.toString() ?: "")
                put("description", node.contentDescription?.toString() ?: "")
                put("id", node.viewIdResourceName ?: "")
                put("clickable", node.isClickable)
                put("editable", node.isEditable)
                put("scrollable", node.isScrollable)
                put("bounds", JSONObject().apply {
                    put("left", bounds.left)
                    put("top", bounds.top)
                    put("right", bounds.right)
                    put("bottom", bounds.bottom)
                })
                put("depth", depth)
            }

            // Only add elements with meaningful content
            if (node.text != null || node.contentDescription != null ||
                node.isClickable || node.isEditable) {
                elements.put(element)
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                collectElements(child, elements, includeInvisible, depth + 1)
                child.recycle()
            }
        }

        private fun findByText(
            node: AccessibilityNodeInfo,
            text: String,
            exactMatch: Boolean,
            results: JSONArray
        ) {
            val nodeText = node.text?.toString() ?: ""
            val nodeDesc = node.contentDescription?.toString() ?: ""

            val matches = if (exactMatch) {
                nodeText == text || nodeDesc == text
            } else {
                nodeText.contains(text, ignoreCase = true) ||
                nodeDesc.contains(text, ignoreCase = true)
            }

            if (matches) {
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                results.put(JSONObject().apply {
                    put("text", nodeText)
                    put("description", nodeDesc)
                    put("class", node.className?.toString() ?: "")
                    put("clickable", node.isClickable)
                    put("bounds", JSONObject().apply {
                        put("left", bounds.left)
                        put("top", bounds.top)
                        put("right", bounds.right)
                        put("bottom", bounds.bottom)
                        put("centerX", bounds.centerX())
                        put("centerY", bounds.centerY())
                    })
                })
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                findByText(child, text, exactMatch, results)
                child.recycle()
            }
        }

        private fun findClickableByText(node: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
            val nodeText = node.text?.toString() ?: ""
            val nodeDesc = node.contentDescription?.toString() ?: ""

            if ((nodeText.contains(text, ignoreCase = true) ||
                nodeDesc.contains(text, ignoreCase = true)) && node.isClickable) {
                return AccessibilityNodeInfo.obtain(node)
            }

            // Check if parent is clickable (common pattern)
            if (nodeText.contains(text, ignoreCase = true) ||
                nodeDesc.contains(text, ignoreCase = true)) {
                var parent = node.parent
                while (parent != null) {
                    if (parent.isClickable) {
                        return parent
                    }
                    val nextParent = parent.parent
                    parent.recycle()
                    parent = nextParent
                }
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                val result = findClickableByText(child, text)
                child.recycle()
                if (result != null) return result
            }

            return null
        }

        private fun findFirstEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.isEditable) {
                return AccessibilityNodeInfo.obtain(node)
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                val result = findFirstEditable(child)
                child.recycle()
                if (result != null) return result
            }

            return null
        }

        private fun findScrollable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.isScrollable) {
                return AccessibilityNodeInfo.obtain(node)
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                val result = findScrollable(child)
                child.recycle()
                if (result != null) return result
            }

            return null
        }

        /**
         * Wait for element with text to appear on screen.
         * @param text Text to search for
         * @param timeoutMs Maximum time to wait in milliseconds
         * @param pollIntervalMs Interval between checks in milliseconds
         * @return JSON with found status and element info
         */
        suspend fun waitForElement(text: String, timeoutMs: Long = 5000, pollIntervalMs: Long = 500): String {
            showOverlay("Waiting for: $text")

            val startTime = System.currentTimeMillis()
            while (System.currentTimeMillis() - startTime < timeoutMs) {
                if (checkInterrupted()) {
                    return """{"found": false, "error": "Operation interrupted"}""".also { completeOverlay(it) }
                }

                val service = instance ?: return """{"found": false, "error": "Accessibility service not enabled"}""".also { completeOverlay(it) }
                val root = service.rootInActiveWindow
                if (root != null) {
                    val results = JSONArray()
                    findByText(root, text, false, results)
                    root.recycle()

                    if (results.length() > 0) {
                        val result = """{"found": true, "count": ${results.length()}, "elements": $results}"""
                        completeOverlay("Found: $text")
                        return result
                    }
                }

                kotlinx.coroutines.delay(pollIntervalMs)
            }

            val result = """{"found": false, "error": "Timeout waiting for element: $text"}"""
            completeOverlay("Timeout: $text")
            return result
        }

        /**
         * Click element by text and then input text.
         * Useful for search boxes that need to be clicked first.
         */
        fun focusAndInput(targetText: String, inputText: String): String {
            showOverlay("Focus and input: $targetText")

            if (checkInterrupted()) {
                return "Error: Operation interrupted"
            }

            val service = instance ?: return "Error: Accessibility service not enabled".also { completeOverlay(it) }
            val root = service.rootInActiveWindow ?: return "Error: Cannot access screen content".also { completeOverlay(it) }

            // Find the element (editable or clickable)
            val node = findEditableByText(root, targetText) ?: findClickableByText(root, targetText)

            val result = if (node != null) {
                // First click/focus
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Thread.sleep(300) // Wait for focus

                // Then set text
                val args = Bundle()
                args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, inputText)
                val success = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                node.recycle()

                if (success) "Focused '$targetText' and typed: $inputText" else "Focus succeeded but typing failed"
            } else {
                "Element not found: $targetText"
            }

            root.recycle()
            completeOverlay(result)
            return result
        }

        /**
         * Find editable element by text (for input fields with placeholder text).
         */
        private fun findEditableByText(node: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
            val nodeText = node.text?.toString() ?: ""
            val nodeDesc = node.contentDescription?.toString() ?: ""
            val nodeHint = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                node.hintText?.toString() ?: ""
            } else ""

            if (node.isEditable && (nodeText.contains(text, ignoreCase = true) ||
                nodeDesc.contains(text, ignoreCase = true) ||
                nodeHint.contains(text, ignoreCase = true))) {
                return AccessibilityNodeInfo.obtain(node)
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                val result = findEditableByText(child, text)
                child.recycle()
                if (result != null) return result
            }

            return null
        }

        /**
         * Get current app package name.
         */
        fun getCurrentApp(): String {
            val service = instance ?: return """{"error": "Accessibility service not enabled"}"""
            val root = service.rootInActiveWindow ?: return """{"error": "Cannot access screen content"}"""
            val packageName = root.packageName?.toString() ?: "unknown"
            root.recycle()
            return """{"package": "$packageName"}"""
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.i(TAG, "AnthroidAccessibilityService created")
    }

    override fun onDestroy() {
        instance = null
        Log.i(TAG, "AnthroidAccessibilityService destroyed")
        super.onDestroy()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "AnthroidAccessibilityService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We don't need to handle events - just provide tools
    }

    override fun onInterrupt() {
        Log.w(TAG, "AnthroidAccessibilityService interrupted")
    }
}
