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
    msgs = [m async for m in ch.history(limit=5)]
    for m in msgs:
        print("ID:", m.id)
        print("Author:", m.author, m.author.id)
        print("Type:", m.type)
        print("Content:", repr(m.content))
        print("Embeds:", m.embeds)
        print("Attachments:", m.attachments)
        print("Components:", m.components)
        print("System content:", m.system_content if hasattr(m,'system_content') else None)
        print("---")
    await client.close()

client.run(TOKEN)