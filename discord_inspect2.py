import os
import discord

TOKEN = os.environ.get("DISCORD_BOT_TOKEN")
GUILD_ID = int(os.environ.get("GUILD_ID", "0"))

intents = discord.Intents.default()
intents.guilds = True
client = discord.Client(intents=intents)

@client.event
async def on_ready():
    guild = client.get_guild(GUILD_ID)
    if not guild:
        print("Guild not found")
        await client.close()
        return
    for ch in guild.text_channels:
        print(f"{ch.position:3} {ch.id}  {ch.name}")
    await client.close()

client.run(TOKEN)