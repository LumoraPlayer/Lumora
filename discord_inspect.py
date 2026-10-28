import os
import discord

TOKEN = os.environ.get("DISCORD_BOT_TOKEN")
GUILD_ID = int(os.environ.get("GUILD_ID", "0"))

intents = discord.Intents.default()
intents.guilds = True
intents.messages = True
client = discord.Client(intents=intents)

@client.event
async def on_ready():
    guild = client.get_guild(GUILD_ID)
    if not guild:
        print("Guild not found")
        await client.close()
        return
    for ch in guild.text_channels:
        try:
            msgs = [m async for m in ch.history(limit=1)]
            count = "has msgs" if msgs else "empty"
        except Exception as e:
            count = f"err {e}"
        print(f"{ch.id}  {ch.name}  ({count})")
    await client.close()

client.run(TOKEN)