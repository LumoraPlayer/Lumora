import os
import discord

TOKEN = os.environ.get("DISCORD_BOT_TOKEN")
GUILD_ID = int(os.environ.get("GUILD_ID", "0"))
CHANNEL_ID = 1539563066457325631

intents = discord.Intents.default()
intents.guilds = True
client = discord.Client(intents=intents)

@client.event
async def on_ready():
    guild = client.get_guild(GUILD_ID)
    ch = guild.get_channel(CHANNEL_ID)
    print("Channel:", ch.id, ch.name)
    try:
        whs = await ch.webhooks()
        print("Webhooks:", len(whs))
        for w in whs:
            print(w.id, w.url)
    except Exception as e:
        print("Err:", e)
    await client.close()

client.run(TOKEN)