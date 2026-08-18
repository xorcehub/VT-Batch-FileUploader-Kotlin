"""
End-to-end CLI test: create unique files, upload via CLI (--wait), then
check one with --no-cache, and verify the cache JSON carries the FULL
field set (GUI parity) for both paths:
  - upload --wait        -> status="completed"  (analysis + enrich GET /files)
  - check --file --no-cache -> status="found"   (direct file-object fetch)
Runs the REAL VirusTotal API using VT_API_KEY. Burns ~6 requests + 2 uploads.
"""
import hashlib
import json
import os
import secrets
import subprocess
import sys
import tempfile

CLI = os.path.join("cli", "build", "install", "cli", "bin", "cli.bat")
assert os.getenv("VT_API_KEY"), "VT_API_KEY must be set"
assert os.path.exists(CLI), f"CLI not built: {CLI}"

workdir = tempfile.mkdtemp(prefix="vt_cli_e2e_")
cache = os.path.join(workdir, "vt_scan_data.json")
env = {**os.environ, "VT_CACHE_FILENAME": cache}

def run(*args):
    print(f"\n$ cli {' '.join(args)}", flush=True)
    r = subprocess.run([CLI, *args], env=env, capture_output=True, text=True, timeout=600)
    print(r.stdout[-3000:])
    if r.stderr:
        print("STDERR:", r.stderr[-1500:])
    return r

# 1. Two unique files (random content => guaranteed not on VT yet)
paths = []
for i in range(2):
    p = os.path.join(workdir, f"e2e_test_{secrets.token_hex(4)}_{i}.bin")
    with open(p, "wb") as f:
        f.write(secrets.token_bytes(128))
    paths.append(p)
print("created:", paths)

md5s = [hashlib.md5(open(p, "rb").read()).hexdigest() for p in paths]

# 2. Upload with wait -> exercises poll -> completed -> enrich -> cache write
r = run("upload", "--wait", "--timeout", "300", *paths)
assert r.returncode in (0, 2), f"upload exit={r.returncode}"

# 3. Fresh fetch of file 1 -> found path with fullCacheEntry (cache skipped)
r = run("check", "--file", paths[0], "--no-cache")
assert r.returncode == 0, f"check exit={r.returncode}"

# 4. Verify cache contents
with open(cache, encoding="utf-8") as f:
    data = json.load(f)

REQUIRED = [
    "filename", "size", "path", "url", "last_scan", "status",
    "last_analysis_stats", "last_analysis_date", "detections",
    "detection_count", "type_description", "tags", "meaningful_name",
    "times_submitted", "reputation", "first_submission_date",
    "last_submission_date", "total_votes_harmless", "total_votes_malicious",
]
# engine_hits is null-by-design for clean files (parser keeps them null, not [])
failures = []
for p, md5 in zip(paths, md5s):
    e = data.get(md5)
    if not e:
        failures.append(f"{md5}: MISSING from cache")
        continue
    missing = [k for k in REQUIRED if e.get(k) is None]
    status = e.get("status")
    print(f"\n=== {os.path.basename(p)} md5={md5[:10]}… status={status}")
    print(json.dumps({k: e.get(k) for k in ("detections", "meaningful_name",
          "times_submitted", "reputation", "type_description", "last_analysis_date")}, indent=2))
    if missing:
        failures.append(f"{md5} (status={status}): missing {missing}")
    # engine_hits must be a non-empty list when any engine flags the file
    if (e.get("detection_count") or 0) > 0 and not e.get("engine_hits"):
        failures.append(f"{md5}: detections>0 but engine_hits missing")
    if status not in ("found", "completed"):
        failures.append(f"{md5}: unexpected status {status!r}")

print("\n" + "=" * 60)
if failures:
    print("FAIL:")
    for f_ in failures:
        print(" -", f_)
    sys.exit(1)
print("PASS: all cache entries carry the full field set (CLI == GUI parity)")
