import os
import discord

TOKEN = os.environ.get("DISCORD_BOT_TOKEN")
GUILD_ID = int(os.environ.get("GUILD_ID", "0"))

if not TOKEN or not GUILD_ID:
    raise SystemExit("Set DISCORD_BOT_TOKEN and GUILD_ID.")

intents = discord.Intents.default()
intents.guilds = True
client = discord.Client(intents=intents)

RENAME = {
    "welcome": "👋-welcome",
    "announcements": "📢-announcements",
    "rules": "📜-rules",
    "links": "🔗-links",
    "general-chat": "💬-general",
    "off-topic": "☕-off-topic",
    "introductions": "🙋-introductions",
    "help": "🆘-help",
    "bug-reports": "🐛-bug-reports",
    "feature-requests": "✨-feature-requests",
    "device-compat": "📱-device-compat",
    "providers": "📡-providers",
    "plugins": "🔌-plugins",
    "stremio-addons": "🎬-stremio-addons",
    "dev-chat": "💻-dev-chat",
    "pull-requests": "🔀-pull-requests",
    "builds": "🚀-releases",
    "screenshots": "🖼️-screenshots",
    "showcase": "🌟-showcase",
}

@client.event
async def on_ready():
    guild = client.get_guild(GUILD_ID)
    if not guild:
        print("Guild not found")
        await client.close()
        return

    for old, new in RENAME.items():
        ch = discord.utils.get(guild.text_channels, name=old)
        if ch:
            try:
                await ch.edit(name=new)
                print(f"Renamed #{old} -> #{new}")
            except Exception as e:
                print(f"Failed rename #{old}: {e}")
        else:
            # maybe already renamed
            ch2 = discord.utils.get(guild.text_channels, name=new)
            if ch2:
                print(f"Already renamed: #{new}")

    # Create webhook in releases channel
    rel = discord.utils.get(guild.text_channels, name="🚀-releases")
    if not rel:
        rel = discord.utils.get(guild.text_channels, name="builds")
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