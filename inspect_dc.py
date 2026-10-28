import os
import discord

TOKEN = os.environ.get("DISCORD_BOT_TOKEN")
GUILD_ID = int(os.environ.get("GUILD_ID", "0"))
CHANNEL_ID = 1533219182739718264

intents = discord.Intents.default()
intents.guilds = True
intents.messages = True
client = discord.Client(intents=intents)

@client.event
async def on_ready():
    guild = client.get_guild(GUILD_ID)
    ch = guild.get_channel(CHANNEL_ID)
    print("Channel:", ch.name)
    msgs = [m async for m in ch.history(limit=20)]
    for m in reversed(msgs):
        print(f"\n--- {m.author} ({m.id}) ---")
        print("CONTENT:", repr(m.content))
        print("ATTACHMENTS:", [a.url for a in m.attachments])
        if m.embeds:
            for e in m.embeds:
                print("EMBED TITLE:", e.title)
                print("EMBED DESC:", e.description)
                print("EMBED FIELDS:", [(f.name, f.value) for f in e.fields])
    await client.close()

client.run(TOKEN)