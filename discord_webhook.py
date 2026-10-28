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
    if not ch:
        print("Channel not found")
        await client.close()
        return
    try:
        wh = await ch.create_webhook(name="GitHub Releases", reason="Lumora setup")
        print("WEBHOOK_URL=" + wh.url)
        with open("/tmp/discord_webhook_url.txt", "w") as f:
            f.write(wh.url)
    except Exception as e:
        print(f"Webhook create failed: {e}")
    await client.close()

client.run(TOKEN)