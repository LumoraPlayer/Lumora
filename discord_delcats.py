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
    for cat in list(guild.categories):
        chans = cat.channels
        print(f"Category: {cat.name} ({len(chans)} channels)")
        if len(chans) == 0:
            try:
                await cat.delete(reason="Remove empty category")
                print(f"  Deleted empty category: {cat.name}")
            except Exception as e:
                print(f"  Failed: {e}")
    await client.close()

client.run(TOKEN)