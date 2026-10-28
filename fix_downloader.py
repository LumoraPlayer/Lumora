import os
import discord
import json, urllib.request

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
    # Delete empty messages
    async for msg in ch.history(limit=50):
        if msg.content.strip() == "" and not msg.embeds and not msg.attachments:
            try:
                await msg.delete()
                print(f"Deleted empty message {msg.id}")
            except Exception as e:
                print(f"Delete failed {msg.id}: {e}")
    # Post nice message
    embed = discord.Embed(
        title="Fire TV Downloader Code",
        description="Use this code in the **Downloader** app on your Fire TV to install Lumora.",
        color=0x5865F2,
    )
    embed.add_field(name="Downloader Code", value="`9528194`", inline=True)
    embed.add_field(name="Direct Link", value="https://aftv.news/9528194", inline=True)
    embed.set_footer(text="Lumora • Sideloading made easy")
    await ch.send(embed=embed)
    print("Posted downloader code embed")
    await client.close()

client.run(TOKEN)