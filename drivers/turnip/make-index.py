#!/usr/bin/env python3
"""Rebuilds drivers.json (release "turnip-index") from the turnip-* releases and prunes
old ones, keeping the newest KEEP releases per channel."""
import json
import os
import subprocess
import sys
import tempfile
import urllib.request
from datetime import datetime, timezone

KEEP = int(os.environ.get("KEEP_PER_CHANNEL", "10"))
INDEX_TAG = "turnip-index"
repo = os.environ["GH_REPO"]


def gh(*args, check=True):
    return subprocess.run(["gh", *args], check=check, capture_output=True, text=True).stdout


releases = json.loads(gh("release", "list", "--limit", "500", "--json", "tagName,createdAt"))
manifests = []
for r in releases:
    tag = r["tagName"]
    if not tag.startswith("turnip-") or tag == INDEX_TAG:
        continue
    url = f"https://github.com/{repo}/releases/download/{tag}/manifest.json"
    try:
        with urllib.request.urlopen(url, timeout=30) as resp:
            manifests.append(json.load(resp))
    except Exception as e:  # releases without a manifest are not ours
        print(f"skip {tag}: {e}", file=sys.stderr)

manifests.sort(key=lambda m: m.get("date", ""), reverse=True)
kept, per_channel = [], {}
for m in manifests:
    n = per_channel.get(m["channel"], 0)
    if n < KEEP:
        kept.append(m)
        per_channel[m["channel"]] = n + 1
    else:
        print(f"pruning {m['id']}")
        gh("release", "delete", m["id"], "--cleanup-tag", "-y", check=False)

index = {"schema": 1, "generated": datetime.now(timezone.utc).isoformat(), "drivers": kept}
with tempfile.TemporaryDirectory() as d:
    path = os.path.join(d, "drivers.json")
    with open(path, "w") as f:
        json.dump(index, f, indent=2, ensure_ascii=False)
    if subprocess.run(["gh", "release", "view", INDEX_TAG], capture_output=True).returncode != 0:
        gh("release", "create", INDEX_TAG, "--title", "Индекс драйверов Turnip",
           "--notes", "drivers.json для приложения: список доступных сборок Turnip.", "--latest=false")
    gh("release", "upload", INDEX_TAG, path, "--clobber")
print(json.dumps(index, indent=2, ensure_ascii=False))
