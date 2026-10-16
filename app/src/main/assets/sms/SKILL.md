---
name: anthroid-sms
description: Read SMS on this Android phone (verification codes, bank or carrier notices, a sender's messages) from a Python, Node or shell script via the anthroid_sms library. Filter inside the script and print only the result the user needs, not message bodies.
---

# Reading SMS with anthroid_sms

This phone's SMS can be read from scripts through the Anthroid app. It is a library,
not a tool call, so that message bodies stay out of your context: write a short
script, filter and extract inside it, and print only the answer.

## Rules
- Do the filtering in the script (`since_minutes`, `address`, `keyword`, `limit`), then
  extract what was asked for (a code, an amount, a date) and print only that.
- Do not print full bodies or dump all messages unless the user explicitly asks to see
  the text. Prefer counts, senders and times when summarising.
- Phone numbers and codes are private: print only the ones the task needs.

## Python
```python
from anthroid_sms import query, SmsError   # importable directly, no path setup

msgs = query(since_minutes=10, keyword="验证码", limit=5)   # newest first
```
Each message is a dict: `id`, `thread_id`, `address`, `date` (epoch ms), `date_sent`,
`type` (`inbox`/`sent`/...), `read` (bool), `body`.

Filters: `since` / `until` (epoch ms), `since_minutes`, `address` (substring; digits-only
match, so `95588` matches `+86 955 88`), `keyword` (body substring), `box`
(`inbox` default, `sent`, `all`), `unread_only`, `limit` (1-200, default 20), `offset`.

Example, latest verification code from the last 10 minutes:
```python
import re
from anthroid_sms import query
for m in query(since_minutes=10, keyword="验证码"):
    hit = re.search(r"(?<!\d)(\d{4,8})(?!\d)", m["body"])
    if hit:
        print(hit.group(1))
        break
else:
    print("no code in the last 10 minutes")
```

## Node
```js
const sms = require('anthroid-sms');
const msgs = await sms.query({ sinceMinutes: 10, address: '95588', limit: 5 });
```
Same fields; options are camelCase (`sinceMinutes`, `unreadOnly`).

## Shell
```bash
anthroid-sms --status                    # app reachable and SMS permission granted?
anthroid-sms --since-minutes 60          # count + one metadata line each, no bodies
anthroid-sms --from 10086 --count
```
`--body` and `--json` print message text; avoid them unless the user asked for it.

## Errors
`SmsError` (Python) / rejected promise (Node) / exit code 2 (CLI), with a code prefix:
- `SMS_PERMISSION_REQUIRED`: ask the user to open Anthroid and allow SMS access.
- `APP_UNREACHABLE`: the Anthroid app isn't running; ask the user to open it.
- `INVALID_REQUEST`: fix the arguments.
