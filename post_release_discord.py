import json
import urllib.request

# Fetch latest GitHub release
req = urllib.request.Request(
    "https://api.github.com/repos/LumoraPlayer/Lumora/releases/latest",
    headers={"Accept": "application/vnd.github+json", "User-Agent": "LumoraBot"},
)
with urllib.request.urlopen(req) as resp:
    rel = json.loads(resp.read())

wh = open('/tmp/discord_webhook_url.txt').read().strip()

payload = {
    "embeds": [
        {
            "title": f"{rel.get('name') or rel.get('tag_name')}",
            "url": rel.get("html_url"),
            "description": (rel.get("body") or "")[:4000],
            "color": 0x5865F2,
            "footer": {"text": f"Tag: {rel.get('tag_name')}"},
        }
    ]
}
data = json.dumps(payload).encode()
req2 = urllib.request.Request(wh, data=data, headers={"Content-Type": "application/json", "User-Agent": "LumoraBot/1.0"}, method="POST")
with urllib.request.urlopen(req2) as resp2:
    print("Posted to Discord:", resp2.status)