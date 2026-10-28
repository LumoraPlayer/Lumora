import shutil, os
src = "/root/Lumora"
dst = "/root/DiscordLumora/scripts"
os.makedirs(dst, exist_ok=True)
files = ["discord_setup.py","discord_fix.py","discord_inspect.py","discord_inspect2.py","discord_dedupe.py","discord_cleanup.py","discord_reorg.py","discord_webhook.py","list_wh.py","discord_rename_rel.py","discord_delcats.py","fix_downloader.py","fix_downloader2.py","add_todo.py","post_release_discord.py","post_all_releases.py","inspect_dc.py","inspect_dc2.py","inspect_dc3.py","test_wh.py","test_wh2.py","check_wh.py","create_github_webhook.py"]
for f in files:
    p = os.path.join(src, f)
    if os.path.exists(p):
        shutil.copy2(p, os.path.join(dst, f))
        print("copied", f)
print("done")