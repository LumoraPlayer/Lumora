import json, urllib.request
wh = open('/tmp/discord_webhook_url.txt').read().strip()
data = json.dumps({"content": "test from Lumora"}).encode()
req = urllib.request.Request(wh, data=data, headers={"Content-Type": "application/json", "User-Agent": "LumoraBot/1.0"}, method="POST")
try:
    with urllib.request.urlopen(req) as r:
        print("status", r.status)
except urllib.error.HTTPError as e:
    print("HTTP", e.code, e.read().decode())