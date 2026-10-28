import json, urllib.request, time

TOKEN = open('/dev/stdin').read().strip() if False else None
import os
BOT = os.environ.get("DISCORD_BOT_TOKEN")
GUILD_ID = int(os.environ.get("GUILD_ID", "0"))
CHANNEL_ID = 1539563066457325631

# Fetch releases
req = urllib.request.Request(
    "https://api.github.com/repos/LumoraPlayer/Lumora/releases?per_page=100",
    headers={"Accept": "application/vnd.github+json", "User-Agent": "LumoraBot"},
)
with urllib.request.urlopen(req) as resp:
    releases = json.loads(resp.read())

# Sort oldest first by created_at
releases.sort(key=lambda r: r.get("created_at") or "")

# Discord bot client to delete messages
import discord
intents = discord.Intents.default()
intents.guilds = True
intents.messages = True
client = discord.Client(intents=intents)

@client.event
async def on_ready():
    guild = client.get_guild(GUILD_ID)
    ch = guild.get_channel(CHANNEL_ID)
    print("Channel:", ch.name, "messages deleting...")

    # Delete all messages
    deleted = 0
    async for msg in ch.history(limit=None):
        try:
            await msg.delete()
            deleted += 1
        except Exception as e:
            print("Delete err:", e)
    print(f"Deleted {deleted} messages")

    # Post releases one by one
    wh_url = open('/tmp/discord_webhook_url.txt').read().strip()
    for rel in releases:
        name = rel.get("name") or rel.get("tag_name")
        tag = rel.get("tag_name")
        url = rel.get("html_url")
        body = (rel.get("body") or "")[:1900]
        payload = {
            "embeds": [
                {
                    "title": name,
                    "url": url,
                    "description": body,
                    "color": 0x5865F2,
                    "footer": {"text": f"Tag: {tag}"},
                }
            ]
        }
        data = json.dumps(payload).encode()
        r = urllib.request.Request(wh_url, data=data, headers={"Content-Type": "application/json", "User-Agent": "LumoraBot/1.0"}, method="POST")
        try:
            with urllib.request.urlopen(r) as resp:
                print("Posted:", tag, resp.status)
        except urllib.error.HTTPError as e:
            print("Post err:", tag, e.code, e.read().decode())
        await __import__("asyncio").sleep(1.2)

    await client.close()

client.run(BOT)