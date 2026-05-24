import urllib.request
import ssl
import re

url = "https://vidzy.live/d/m888nvcn5e58_n"
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
        print("Page length:", len(body))
        
        # Look for direct links (usually <a> tags or scripts)
        links = re.findall(r'href=["\']([^"\']+\.(?:mp4|mkv)[^"\']*)["\']', body)
        print("Direct file links found:", links)
        
        # Look for any forms or onclick downloads
        onclicks = re.findall(r'onclick=["\']([^"\']+)["\']', body)
        print("Onclicks:", onclicks[:5])
        
        # Let's save the page to download_page.html to inspect
        with open("d:\\Projets\\Animan\\download_page.html", "w", encoding="utf-8") as f:
            f.write(body)
        print("Wrote page to download_page.html")
except Exception as e:
    print("Error:", e)
