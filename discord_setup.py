# Discord server bootstrap for Lumora community.
# Usage:
#   pip install discord.py
#   export DISCORD_BOT_TOKEN="your-bot-token"
#   export GUILD_ID="your-server-id"
#   python3 discord_setup.py
#
# Bot must be invited with Administrator permission to the target server.

import os
import discord
from discord import PermissionOverwrite

TOKEN = os.environ.get("DISCORD_BOT_TOKEN")
GUILD_ID = int(os.environ.get("GUILD_ID", "0"))

if not TOKEN or not GUILD_ID:
    raise SystemExit("Set DISCORD_BOT_TOKEN and GUILD_ID environment variables.")

intents = discord.Intents.default()
intents.guilds = True
client = discord.Client(intents=intents)

ROLES = [
    ("Admin", discord.Permissions(administrator=True)),
    ("Moderator", discord.Permissions(manage_messages=True, kick_members=True, ban_members=True, manage_channels=True)),
    ("Contributor", discord.Permissions(send_messages=True, read_messages=True, attach_files=True)),
    ("Member", discord.Permissions(send_messages=True, read_messages=True, attach_files=True, add_reactions=True)),
]

CATEGORIES = {
    "Welcome & Info": [
        ("welcome", True),
        ("announcements", True),
        ("rules", True),
        ("links", True),
    ],
    "General": [
        ("general-chat", False),
        ("off-topic", False),
        ("introductions", False),
    ],
    "Support": [
        ("help", False),
        ("bug-reports", False),
        ("feature-requests", False),
        ("device-compat", False),
    ],
    "Providers & Plugins": [
        ("providers", False),
        ("plugins", False),
        ("stremio-addons", False),
    ],
    "Development": [
        ("dev-chat", True),
        ("pull-requests", True),
        ("builds", True),
    ],
    "Media": [
        ("screenshots", False),
        ("showcase", False),
    ],
}

@client.event
async def on_ready():
    guild = client.get_guild(GUILD_ID)
    if not guild:
        print("Guild not found. Check GUILD_ID and bot membership.")
        await client.close()
        return

    # Create roles
    role_map = {}
    for name, perms in ROLES:
        existing = discord.utils.get(guild.roles, name=name)
        if existing:
            role_map[name] = existing
            print(f"Role exists: {name}")
        else:
            role = await guild.create_role(name=name, permissions=perms, reason="Lumora setup")
            role_map[name] = role
            print(f"Created role: {name}")

    admin_role = role_map["Admin"]
    mod_role = role_map["Moderator"]
    member_role = role_map["Member"]

    # Create categories and channels
    for cat_name, channels in CATEGORIES.items():
        category = discord.utils.get(guild.categories, name=cat_name)
        if not category:
            category = await guild.create_category(cat_name, reason="Lumora setup")
            print(f"Created category: {cat_name}")

        for ch_name, read_only in channels:
            existing = discord.utils.get(guild.text_channels, name=ch_name)
            if existing:
                print(f"Channel exists: #{ch_name}")
                continue

            overwrites = {}
            if read_only:
                overwrites[guild.default_role] = PermissionOverwrite(send_messages=False, read_messages=True)
                overwrites[member_role] = PermissionOverwrite(send_messages=False, read_messages=True)
                overwrites[mod_role] = PermissionOverwrite(send_messages=True, read_messages=True)
                overwrites[admin_role] = PermissionOverwrite(send_messages=True, read_messages=True)
            else:
                overwrites[guild.default_role] = PermissionOverwrite(send_messages=True, read_messages=True)

            channel = await guild.create_text_channel(
                ch_name,
                category=category,
                overwrites=overwrites,
                reason="Lumora setup",
            )
            print(f"Created channel: #{ch_name}")

    print("Done. Configure webhooks/announcements manually.")
    await client.close()

client.run(TOKEN)