import urllib.request
import ssl
import re

url = "https://vidzy.live/embed-m888nvcn5e58.html"
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
        
        # Look for video file links or scripts
        file_matches = re.findall(r'file\s*:\s*["\']([^"\']+)["\']', body)
        print("Found file: matches:", file_matches)
        
        src_matches = re.findall(r'src\s*=\s*["\']([^"\']+\.mp4[^"\']*)["\']', body)
        print("Found src .mp4 matches:", src_matches)
        
        tracks = re.findall(r'<track[^>]+src=["\']([^"\']+)["\']', body)
        print("Found HTML5 track subtitles:", tracks)
        
        # Also let's check if the word "video" or "source" is in there
        # Let's save it to a file to inspect if matches are empty
        with open("d:\\Projets\\Animan\\embed_page.html", "w", encoding="utf-8") as f:
            f.write(body)
        print("Wrote page to embed_page.html")
except Exception as e:
    print("Error:", e)
