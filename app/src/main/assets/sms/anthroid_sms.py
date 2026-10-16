"""Read SMS on this phone from Python, via the Anthroid app.

Filter in your script and print only what you need, so message bodies do not end up
in an agent's context:

    from anthroid_sms import query
    for m in query(since_minutes=10, address="95588"):
        code = extract_code(m["body"])   # your own parsing
    print(code)

Each message is a dict: id, thread_id, address, date (epoch ms), date_sent, type
(inbox/sent/...), read (bool), body.

The app answers on a Unix socket that only this app's uid can connect to. It needs
the SMS permission (granted in the app); otherwise SmsError("SMS_PERMISSION_REQUIRED...").
"""

import json
import os
import socket

SOCKET_PATH = os.environ.get(
    "ANTHROID_SMS_SOCKET",
    "/data/data/com.anthroid/files/apps/com.anthroid/sms/sms.sock",
)
DEFAULT_TIMEOUT = 15.0


class SmsError(RuntimeError):
    """Raised when the app rejects a request or cannot be reached."""


def _call(request, timeout=DEFAULT_TIMEOUT):
    sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    sock.settimeout(timeout)
    try:
        try:
            sock.connect(SOCKET_PATH)
        except OSError as e:
            raise SmsError("APP_UNREACHABLE: %s (is Anthroid running?)" % e) from e
        sock.sendall(json.dumps(request).encode("utf-8"))
        sock.shutdown(socket.SHUT_WR)
        chunks = []
        while True:
            chunk = sock.recv(65536)
            if not chunk:
                break
            chunks.append(chunk)
    finally:
        sock.close()
    raw = b"".join(chunks).decode("utf-8")
    if not raw:
        raise SmsError("EMPTY_RESPONSE: the app closed the connection")
    response = json.loads(raw)
    if not response.get("ok"):
        raise SmsError(response.get("error", "UNKNOWN_ERROR"))
    return response


def query(since=None, until=None, since_minutes=None, address=None, keyword=None,
          box="inbox", unread_only=False, limit=20, offset=0, timeout=DEFAULT_TIMEOUT):
    """Return messages newest first, as a list of dicts.

    since / until: epoch milliseconds. since_minutes: relative window.
    address: sender/recipient substring (digits-only match if it has digits).
    keyword: body substring. box: "inbox" (default), "sent" or "all".
    limit: 1..200 (default 20).
    """
    request = {"op": "query", "box": box, "limit": limit, "offset": offset}
    if since is not None:
        request["since"] = int(since)
    if until is not None:
        request["until"] = int(until)
    if since_minutes is not None:
        request["since_minutes"] = int(since_minutes)
    if address:
        request["address"] = str(address)
    if keyword:
        request["keyword"] = str(keyword)
    if unread_only:
        request["unread_only"] = True
    return _call(request, timeout)["messages"]


def status(timeout=DEFAULT_TIMEOUT):
    """Return {"ok": True, "permission": bool}; raises SmsError if the app is unreachable."""
    return _call({"op": "status"}, timeout)
