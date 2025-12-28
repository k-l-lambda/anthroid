#!/data/data/com.anthroid/files/usr/bin/python3
"""
MCP Server for Android Tools - Direct execution version
Uses am/pm commands directly without socket dependency
"""
import sys
import json
import subprocess
import os
import time

HOME = os.environ.get('HOME', '/data/data/com.anthroid/files/home')

def log(msg):
    print(f"[AndroidMCP] {msg}", file=sys.stderr, flush=True)

def read_jsonrpc():
    line = sys.stdin.readline()
    if not line:
        return None
    return json.loads(line.strip())

def write_jsonrpc(msg):
    print(json.dumps(msg), flush=True)

def run_cmd(cmd, timeout=30):
    """Run shell command and return output."""
    try:
        result = subprocess.run(cmd, shell=True, capture_output=True, text=True, timeout=timeout)
        return result.stdout + result.stderr
    except Exception as e:
        return f"Error: {e}"

def show_notification(title, message):
    """Show notification using termux-toast or am command."""
    # Try termux-toast first (simpler, works without root)
    try:
        subprocess.run(['termux-toast', '-s', message], timeout=5, capture_output=True)
        return f"Toast shown: {message}"
    except:
        pass
    
    # Fallback: use am start with ACTION_VIEW for a simple notification
    # Or write to a file that the app monitors
    result_file = f"{HOME}/.notification_request"
    with open(result_file, 'w') as f:
        json.dump({"title": title, "message": message, "time": time.time()}, f)
    return f"Notification requested: {title} - {message}"

def open_url(url):
    """Open URL in browser."""
    cmd = f'am start -a android.intent.action.VIEW -d "{url}"'
    return run_cmd(cmd)

def launch_app(package):
    """Launch app by package name."""
    cmd = f'monkey -p {package} -c android.intent.category.LAUNCHER 1'
    return run_cmd(cmd)

def list_apps(filter_type="user", limit=50):
    """List installed apps."""
    if filter_type == "system":
        cmd = "pm list packages -s"
    elif filter_type == "user":
        cmd = "pm list packages -3"
    else:
        cmd = "pm list packages"
    
    output = run_cmd(cmd)
    packages = [line.replace("package:", "") for line in output.strip().split("\n") if line.startswith("package:")]
    return json.dumps(packages[:limit], indent=2)

def get_location():
    """Get location using termux-location if available."""
    try:
        result = subprocess.run(['termux-location'], capture_output=True, text=True, timeout=30)
        return result.stdout
    except:
        return "Error: termux-location not available. Install termux-api package."

def query_calendar():
    """Query calendar - requires termux-api."""
    try:
        result = subprocess.run(['termux-calendar-list'], capture_output=True, text=True, timeout=10)
        return result.stdout
    except:
        return "Error: termux-calendar-list not available. Install termux-api package."

def call_tool(name, args):
    """Execute tool by name."""
    log(f"Calling: {name} with {args}")
    
    if name == "show_notification":
        return show_notification(args.get("title", "Notification"), args.get("message", ""))
    elif name == "open_url":
        return open_url(args.get("url", ""))
    elif name == "launch_app":
        return launch_app(args.get("package", ""))
    elif name == "list_apps":
        return list_apps(args.get("filter", "user"), args.get("limit", 50))
    elif name == "get_location":
        return get_location()
    elif name == "query_calendar":
        return query_calendar()
    else:
        return f"Unknown tool: {name}"

def get_tools():
    return [
        {"name": "show_notification", "description": "Show a notification/toast", "inputSchema": {"type": "object", "properties": {"title": {"type": "string"}, "message": {"type": "string"}}, "required": ["message"]}},
        {"name": "open_url", "description": "Open a URL in browser", "inputSchema": {"type": "object", "properties": {"url": {"type": "string"}}, "required": ["url"]}},
        {"name": "launch_app", "description": "Launch an app", "inputSchema": {"type": "object", "properties": {"package": {"type": "string"}}, "required": ["package"]}},
        {"name": "list_apps", "description": "List installed apps", "inputSchema": {"type": "object", "properties": {"filter": {"type": "string", "enum": ["user", "system", "all"]}, "limit": {"type": "integer"}}}},
        {"name": "get_location", "description": "Get device location", "inputSchema": {"type": "object", "properties": {}}},
        {"name": "query_calendar", "description": "Query calendar events", "inputSchema": {"type": "object", "properties": {}}}
    ]

def handle_request(request):
    method = request.get("method", "")
    req_id = request.get("id")
    params = request.get("params", {})
    
    log(f"Method: {method}")
    
    if method == "initialize":
        return {"jsonrpc": "2.0", "id": req_id, "result": {"protocolVersion": "2024-11-05", "capabilities": {"tools": {}}, "serverInfo": {"name": "android-tools", "version": "1.0.0"}}}
    elif method == "notifications/initialized":
        return None
    elif method == "tools/list":
        return {"jsonrpc": "2.0", "id": req_id, "result": {"tools": get_tools()}}
    elif method == "tools/call":
        name = params.get("name", "")
        args = params.get("arguments", {})
        result = call_tool(name, args)
        log(f"Result: {str(result)[:200]}")
        return {"jsonrpc": "2.0", "id": req_id, "result": {"content": [{"type": "text", "text": str(result)}]}}
    else:
        return {"jsonrpc": "2.0", "id": req_id, "error": {"code": -32601, "message": f"Unknown method: {method}"}}

def main():
    log("Android MCP Server v2 starting...")
    while True:
        try:
            request = read_jsonrpc()
            if request is None:
                break
            response = handle_request(request)
            if response:
                write_jsonrpc(response)
        except Exception as e:
            log(f"Error: {e}")

if __name__ == "__main__":
    main()
