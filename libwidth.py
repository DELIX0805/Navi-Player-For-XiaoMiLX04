"""查曲库里歌手名的宽度分布：估算有多少首歌的歌手名会超出 174px 的控件宽度。"""
import hashlib
import json
import re
import subprocess
import urllib.request

from devpath import adb_path

ADB = adb_path()
PREFS = "/data/data/com.tongsir.naviplayer/shared_prefs/navi.xml"

raw = subprocess.run([ADB, "shell", "cat", PREFS], capture_output=True, timeout=60)
xml = (raw.stdout + raw.stderr).decode("utf-8", "replace")
cfg = dict(re.findall(r'<string name="([^"]+)">([^<]*)</string>', xml))
print("prefs keys:", list(cfg.keys()))

url = cfg.get("url", "").rstrip("/")
user = cfg.get("user", "")
pw = cfg.get("pass", "")
print("server:", url, "user:", user)

salt = "abcdef012345"
tok = hashlib.md5((pw + salt).encode()).hexdigest()
base = "%s/rest/" % url


def call(method, extra=""):
    u = ("%s%s?u=%s&t=%s&s=%s&v=1.16.1&c=naviplayer&f=json%s"
         % (base, method, user, tok, salt, extra))
    with urllib.request.urlopen(u, timeout=20) as r:
        return json.loads(r.read().decode("utf-8"))


j = call("getRandomSongs.view", "&size=500")
songs = j["subsonic-response"].get("randomSongs", {}).get("song", [])
print("sampled:", len(songs))

AVAIL = 174.0     # 控件可用宽度（px）


def width(s):
    w = 0.0
    for ch in s:
        w += 18.0 if ord(ch) > 0x2E80 else 9.5
    return w


over = [s for s in songs if width(s.get("artist") or "") > AVAIL]
print("歌手名超出控件的歌: %d / %d" % (len(over), len(songs)))
over.sort(key=lambda s: -width(s.get("artist") or ""))
for s in over[:12]:
    a = s.get("artist") or ""
    print("   %5.0fpx  %-36s | %s" % (width(a), a[:36], s.get("title", "")[:26]))
