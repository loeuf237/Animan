import re

with open("d:\\Projets\\Animan\\embed_page.html", "r", encoding="utf-8") as f:
    content = f.read()

# Search for the eval block
pattern = r"eval\(function\(p,a,c,k,e,d\)\{.*?\}\('(.*?)',(\d+),(\d+),'([^']*)'\.split\('\|'\)\)\)"
match = re.search(pattern, content, re.DOTALL)

if match:
    p, a, c, k = match.groups()
    a = int(a)
    c = int(c)
    words = k.split('|')
    
    # Simple baseN encoder/decoder
    def baseN(num, b):
        if num == 0:
            return '0'
        digits = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        res = []
        while num:
            res.append(digits[num % b])
            num //= b
        return "".join(reversed(res))
    
    # We build a dictionary from baseN(i, a) to word
    lookup = {}
    for i in range(c):
        key = baseN(i, a)
        # If words has a value at i, use it, otherwise key itself
        val = words[i] if i < len(words) and words[i] else key
        lookup[key] = val
        
    # Replace in p
    # Standard packer replacement splits by word boundaries
    def replace_word(match_obj):
        w = match_obj.group(0)
        return lookup.get(w, w)
        
    unpacked = re.sub(r'\b[0-9a-zA-Z_]+\b', replace_word, p)
    
    with open("d:\\Projets\\Animan\\unpacked.js", "w", encoding="utf-8") as out:
        out.write(unpacked)
    print("Successfully unpacked to unpacked.js")
else:
    print("Could not find packer eval block in HTML")
