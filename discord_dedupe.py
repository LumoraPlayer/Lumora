import os
import discord

TOKEN = os.environ.get("DISCORD_BOT_TOKEN")
GUILD_ID = int(os.environ.get("GUILD_ID", "0"))

intents = discord.Intents.default()
intents.guilds = True
client = discord.Client(intents=intents)

# Delete these empty duplicate channels if they exist
DELETE = ["💬-general", "🆘-help", "🐛-bug-reports", "✨-feature-requests", "💻-dev-chat", "🚀-releases"]

# Rename original channels to emoji names
RENAME = {
    "general": "💬-general",
    "help-me": "🆘-help",
    "bug-report": "🐛-bug-reports",
    "feature-request": "✨-feature-requests",
    "dev": "💻-dev-chat",
    "releases": "🚀-releases",
}

@client.event
async def on_ready():
    guild = client.get_guild(GUILD_ID)
    if not guild:
        print("Guild not found")
        await client.close()
        return

    for name in DELETE:
        ch = discord.utils.get(guild.text_channels, name=name)
        if ch:
            try:
                await ch.delete(reason="Remove duplicate empty channel")
                print(f"Deleted duplicate: #{name}")
            except Exception as e:
                print(f"Failed delete #{name}: {e}")

    for old, new in RENAME.items():
        ch = discord.utils.get(guild.text_channels, name=old)
        if ch:
            try:
                await ch.edit(name=new)
                print(f"Renamed #{old} -> #{new}")
            except Exception as e:
                print(f"Failed rename #{old}: {e}")
        else:
            print(f"Original not found: #{old}")

    # Recreate webhook in releases channel
    rel = discord.utils.get(guild.text_channels, name="🚀-releases")
    if rel:
        try:
            wh = await rel.create_webhook(name="GitHub Releases", reason="Lumora setup")
            print("WEBHOOK_URL=" + wh.url)
            with open("/tmp/discord_webhook_url.txt", "w") as f:
                f.write(wh.url)
        except Exception as e:
            print(f"Webhook create failed: {e}")
    else:
        print("Releases channel not found")

    await client.close()

client.run(TOKEN)