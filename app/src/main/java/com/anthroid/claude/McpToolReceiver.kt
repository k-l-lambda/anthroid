package com.anthroid.claude

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

/**
 * BroadcastReceiver for MCP tool calls.
 * Receives tool requests from MCP server, executes them, writes result to file.
 */
class McpToolReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "McpToolReceiver"
        const val ACTION_MCP_TOOL_CALL = "com.anthroid.MCP_TOOL_CALL"
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_MCP_TOOL_CALL) return
        
        val toolName = intent.getStringExtra("tool") ?: return
        val callFile = intent.getStringExtra("call_file") ?: return
        
        Log.i(TAG, "Received MCP tool call: $toolName from $callFile")
        
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Read call arguments from file
                val callData = JSONObject(File(callFile).readText())
                val arguments = callData.optJSONObject("arguments") ?: JSONObject()
                
                Log.i(TAG, "Executing tool: $toolName with args: $arguments")
                
                // Execute tool
                val androidTools = AndroidTools(context)
                val result = androidTools.executeTool(toolName, arguments.toString())
                
                Log.i(TAG, "Tool result: ${result.take(100)}")
                
                // Write result to file
                val resultFile = File(callFile).parent + "/.mcp_result.json"
                val resultJson = JSONObject().apply {
                    put("result", result)
                    put("tool", toolName)
                    put("success", !result.startsWith("Error"))
                }
                File(resultFile).writeText(resultJson.toString())
                
                Log.i(TAG, "Result written to $resultFile")
                
            } catch (e: Exception) {
                Log.e(TAG, "Error executing MCP tool", e)
                
                // Write error result
                try {
                    val resultFile = File(callFile).parent + "/.mcp_result.json"
                    val errorJson = JSONObject().apply {
                        put("result", "Error: ${e.message}")
                        put("tool", toolName)
                        put("success", false)
                    }
                    File(resultFile).writeText(errorJson.toString())
                } catch (e2: Exception) {
                    Log.e(TAG, "Failed to write error result", e2)
                }
            }
        }
    }
}
