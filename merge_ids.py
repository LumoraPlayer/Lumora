import json
a = json.load(open('/root/DiscordLumora/config/all_ids.json'))
ids = json.load(open('/root/DiscordLumora/config/ids.json'))
ids['categories'] = a.get('categories', {})
ids['channels'] = a.get('channels', {})
json.dump(ids, open('/root/DiscordLumora/config/ids.json','w'), indent=2)
print("updated")