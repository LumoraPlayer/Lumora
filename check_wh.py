import json, urllib.request
wh = open('/tmp/discord_webhook_url.txt').read().strip()
req = urllib.request.Request(wh, method="GET")
try:
    with urllib.request.urlopen(req) as r:
        print("GET status", r.status)
        print(r.read().decode()[:500])
except urllib.error.HTTPError as e:
    print("HTTP", e.code, e.read().decode())