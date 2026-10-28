import os
import discord

TOKEN = os.environ.get("DISCORD_BOT_TOKEN")
GUILD_ID = int(os.environ.get("GUILD_ID", "0"))
CHANNEL_ID = 1533219182739718264

intents = discord.Intents.default()
intents.guilds = True
client = discord.Client(intents=intents)

@client.event
async def on_ready():
    guild = client.get_guild(GUILD_ID)
    ch = guild.get_channel(CHANNEL_ID)
    print("Name:", ch.name)
    print("Topic:", repr(ch.topic))
    await client.close()

client.run(TOKEN)