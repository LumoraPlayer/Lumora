import os
import discord

TOKEN = os.environ.get("DISCORD_BOT_TOKEN")
GUILD_ID = int(os.environ.get("GUILD_ID", "0"))

intents = discord.Intents.default()
intents.guilds = True
client = discord.Client(intents=intents)

RENAME_ABOVE = {
    "moderator-only": "🔒-moderator-only",
    "new-members": "👋-new-members",
    "downloader-code": "📥-downloader-code",
    "android-auto-help": "🚗-android-auto",
    "service-discussion": "💬-service-discussion",
    "free-epg": "📺-free-epg",
    "ticket-transcripts": "📋-ticket-transcripts",
}

@client.event
async def on_ready():
    guild = client.get_guild(GUILD_ID)
    if not guild:
        print("Guild not found")
        await client.close()
        return

    ticket = discord.utils.get(guild.text_channels, name="ticket-transcripts")
    if not ticket:
        # maybe already renamed
        ticket = discord.utils.get(guild.text_channels, name="📋-ticket-transcripts")
    if not ticket:
        print("ticket-transcripts not found")
        await client.close()
        return

    cutoff = ticket.position
    print(f"Ticket transcripts position: {cutoff}")

    # Delete all text channels below ticket transcripts
    for ch in list(guild.text_channels):
        if ch.position > cutoff:
            try:
                await ch.delete(reason="Cleanup channels below ticket transcripts")
                print(f"Deleted: #{ch.name}")
            except Exception as e:
                print(f"Failed delete #{ch.name}: {e}")

    # Rename remaining non-emoji channels above
    for old, new in RENAME_ABOVE.items():
        ch = discord.utils.get(guild.text_channels, name=old)
        if ch:
            try:
                await ch.edit(name=new)
                print(f"Renamed #{old} -> #{new}")
            except Exception as e:
                print(f"Failed rename #{old}: {e}")

    await client.close()

client.run(TOKEN)