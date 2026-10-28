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
    # Find Development category
    dev_cat = discord.utils.get(guild.categories, name="Development")
    if not dev_cat:
        dev_cat = await guild.create_category("Development", reason="Todo features")
    # Roles
    admin = discord.utils.get(guild.roles, name="Admin")
    mod = discord.utils.get(guild.roles, name="Moderator")
    everyone = guild.default_role

    overwrites = {
        everyone: discord.PermissionOverwrite(send_messages=False, read_messages=True, add_reactions=True),
    }
    if admin:
        overwrites[admin] = discord.PermissionOverwrite(send_messages=True, read_messages=True, manage_messages=True)
    if mod:
        overwrites[mod] = discord.PermissionOverwrite(send_messages=True, read_messages=True, manage_messages=True)

    # Create channel if not exists
    existing = discord.utils.get(guild.text_channels, name="📝-to-do-features")
    if existing:
        print("Channel already exists")
    else:
        ch = await guild.create_text_channel(
            "📝-to-do-features",
            category=dev_cat,
            overwrites=overwrites,
            topic="Feature to-do list. Read-only for members; Admins/Moderators can post updates.",
            reason="Add todo-features channel",
        )
        print(f"Created #{ch.name}")
    await client.close()

client.run(TOKEN)