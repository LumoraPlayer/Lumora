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
    # Delete bot's recent messages
    async for msg in ch.history(limit=10):
        if msg.author.bot:
            try:
                await msg.delete()
                print(f"Deleted bot message {msg.id}")
            except Exception as e:
                print(f"Delete failed {msg.id}: {e}")
    # Post prominent code
    await ch.send("# Downloader Code\n```\n9528194\n```\nUse this in the **Downloader** app on Fire TV.\nDirect link: https://aftv.news/9528194")
    print("Posted big code")
    await client.close()

client.run(TOKEN)