import os
import discord

TOKEN = os.environ.get("DISCORD_BOT_TOKEN")
GUILD_ID = int(os.environ.get("GUILD_ID", "0"))

intents = discord.Intents.default()
intents.guilds = True
client = discord.Client(intents=intents)

CATEGORIES = {
    "Welcome & Info": ["📢-announcements", "📜-rules", "🔒-moderator-only", "👋-new-members"],
    "General": ["💬-general", "💬-service-discussion"],
    "Support": ["🆘-help", "🐛-bug-reports", "✨-feature-requests", "🚗-android-auto", "📺-free-epg"],
    "Providers & Plugins": ["🔌-plugins"],
    "Development": ["💻-dev-chat", "🚀-releases", "📥-downloader-code"],
    "Tickets": ["📋-ticket-transcripts"],
}

@client.event
async def on_ready():
    guild = client.get_guild(GUILD_ID)
    if not guild:
        print("Guild not found")
        await client.close()
        return

    for cat_name, channel_names in CATEGORIES.items():
        category = discord.utils.get(guild.categories, name=cat_name)
        if not category:
            category = await guild.create_category(cat_name, reason="Reorganize channels")
            print(f"Created category: {cat_name}")
        for ch_name in channel_names:
            ch = discord.utils.get(guild.text_channels, name=ch_name)
            if ch:
                if ch.category != category:
                    await ch.edit(category=category, reason="Reorganize channels")
                    print(f"Moved #{ch_name} -> {cat_name}")
                else:
                    print(f"Already in {cat_name}: #{ch_name}")
            else:
                print(f"Not found: #{ch_name}")

    await client.close()

client.run(TOKEN)