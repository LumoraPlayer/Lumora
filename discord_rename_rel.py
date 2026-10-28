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
    for name in ["🚀-releases", "releases"]:
        ch = discord.utils.get(guild.text_channels, name=name)
        if ch:
            print(f"Found: {ch.id} {ch.name}")
            try:
                await ch.edit(name="📦-releases")
                print("Renamed to 📦-releases")
            except Exception as e:
                print(f"Failed: {e}")
    await client.close()

client.run(TOKEN)