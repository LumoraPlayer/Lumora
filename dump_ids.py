import os, json
import discord

TOKEN = os.environ.get("DISCORD_BOT_TOKEN")
GUILD_ID = int(os.environ.get("GUILD_ID", "0"))

intents = discord.Intents.default()
intents.guilds = True
client = discord.Client(intents=intents)

@client.event
async def on_ready():
    guild = client.get_guild(GUILD_ID)
    data = {
        "guild_id": str(guild.id),
        "categories": {},
        "channels": {},
    }
    for cat in guild.categories:
        data["categories"][cat.name] = str(cat.id)
        for ch in cat.channels:
            data["channels"][ch.name] = str(ch.id)
    # uncategorized
    for ch in guild.text_channels:
        if ch.category is None:
            data["channels"][ch.name] = str(ch.id)
    for ch in guild.voice_channels:
        if ch.category is None:
            data["channels"][ch.name] = str(ch.id)
    print(json.dumps(data, indent=2))
    with open("/root/DiscordLumora/config/all_ids.json", "w") as f:
        json.dump(data, f, indent=2)
    await client.close()

client.run(TOKEN)