import re, json, urllib.request

creds = open('/root/Lumora/.git/credentials').read()
token = None
for line in creds.splitlines():
    if 'github.com' in line and 'LumoraPlayer' in line and 'github_pat_' in line:
        # https://LumoraPlayer:<token>@github.com
        token = line.split('https://LumoraPlayer:',1)[1].split('@github.com',1)[0]
        break
if not token:
    raise SystemExit("Token not found")

wh = open('/tmp/discord_webhook_url.txt').read().strip().rstrip('/')
# Discord only renders GitHub payloads on its GitHub-compatible endpoint; the bare
# /webhooks/<id>/<token> URL answers 400 to every delivery (v6.3 went missing this way).
if not wh.endswith('/github'):
    wh += '/github'

data = {
    "name": "web",
    "active": True,
    "events": ["release"],
    "config": {
        "url": wh,
        "content_type": "json",
    }
}
req = urllib.request.Request(
    "https://api.github.com/repos/LumoraPlayer/Lumora/hooks",
    data=json.dumps(data).encode(),
    headers={
        "Authorization": f"token {token}",
        "Accept": "application/vnd.github+json",
        "Content-Type": "application/json",
    },
    method="POST",
)
try:
    with urllib.request.urlopen(req) as resp:
        body = json.loads(resp.read())
        print("Created webhook id:", body.get("id"))
except urllib.error.HTTPError as e:
    print("HTTP error:", e.code, e.read().decode())