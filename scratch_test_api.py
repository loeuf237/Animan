import urllib.request
import ssl
import json

url = "https://w16.french-manga.net/engine/ajax/manga_episodes_api.php?id=1498849"
headers = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36",
    "Referer": "https://w16.french-manga.net/"
}

ctx = ssl.create_default_context()
ctx.check_hostname = False
ctx.verify_mode = ssl.CERT_NONE

req = urllib.request.Request(url, headers=headers)
try:
    with urllib.request.urlopen(req, context=ctx, timeout=10) as response:
        body = response.read().decode("utf-8", errors="ignore")
        parsed = json.loads(body)
        with open("d:\\Projets\\Animan\\manga_episodes_api_response.json", "w", encoding="utf-8") as f:
            json.dump(parsed, f, indent=2, ensure_ascii=False)
        print("Successfully wrote full JSON to manga_episodes_api_response.json")
except Exception as e:
    print("Error:", e)
