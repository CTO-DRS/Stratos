#!/usr/bin/env python3
"""Stratos sanity gate — static checks that must pass before every release.

Covers: version freeze, trilingual string parity, XML/JSON validity,
string-resource wiring, manifest wiring, workflow integrity, secret scan,
placeholder scan and route wiring. Exit code 0 = ship it.
"""
import glob
import json
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ISSUES: list[str] = []


def check(cond: bool, ok_msg: str, fail_msg: str) -> None:
    if cond:
        print(f"  ✅ {ok_msg}")
    else:
        ISSUES.append(fail_msg)
        print(f"  ❌ {fail_msg}")


def section(title: str) -> None:
    print(f"\n== {title} ==")


# ---------------------------------------------------------------- version ----
section("Version freeze (1.0.0)")
gradle = (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")
m = re.search(r'versionName\s*=\s*"([^"]+)"', gradle)
check(m is not None and m.group(1) == "1.0.0",
      f"versionName = {m.group(1) if m else '?'}",
      f"versionName is not 1.0.0 (got {m.group(1) if m else 'none'})")
mcode = re.search(r"versionCode\s*=\s*(\d+)", gradle)
check(mcode is not None and mcode.group(1) == "1",
      "versionCode = 1", "versionCode changed")

forbidden = re.compile(r"v?0\.1\.0|v?1\.1\.0")
bad = []
for f in list(ROOT.glob("*.md")) + list(ROOT.glob("docs/*.md")):
    for i, line in enumerate(f.read_text(encoding="utf-8").splitlines(), 1):
        if forbidden.search(line) and "versioning" not in line.lower():
            bad.append(f"{f.name}:{i}")
check(not bad, "no stray 0.1.0 / 1.1.0 mentions in docs", f"forbidden versions in: {bad}")

# --------------------------------------------------------------- strings ----
section("Trilingual strings (ar/en/tr)")
def string_keys(path: Path) -> dict[str, str]:
    tree = ET.parse(path)
    return {e.get("name"): (e.text or "") for e in tree.getroot() if e.tag == "string"}

base = string_keys(ROOT / "app/src/main/res/values/strings.xml")
for loc in ("values-ar", "values-tr"):
    k = string_keys(ROOT / f"app/src/main/res/{loc}/strings.xml")
    missing, extra = set(base) - set(k), set(k) - set(base)
    check(not missing and not extra,
          f"{loc}: {len(k)} keys, full parity",
          f"{loc} parity broken — missing {sorted(missing)} extra {sorted(extra)}")
check(len(base) >= 200, f"{len(base)} base keys", "suspiciously few base strings")

# XML used but undefined / defined but unused
used: set[str] = set()
for kt in ROOT.glob("app/src/main/java/**/*.kt"):
    used |= set(re.findall(r"R\.string\.([A-Za-z0-9_]+)", kt.read_text(encoding="utf-8")))
undefined = sorted(used - set(base))
check(not undefined, f"all {len(used)} R.string.* keys defined",
      f"R.string.* used in code but undefined: {undefined}")

# ----------------------------------------------------------------- xml/js ----
section("Resource + asset validity")
xml_files = list(ROOT.glob("app/src/main/res/**/*.xml")) + [ROOT / "app/src/main/AndroidManifest.xml"]
bad_xml = []
for f in xml_files:
    try:
        ET.parse(f)
    except Exception as e:  # noqa: BLE001
        bad_xml.append(f"{f.relative_to(ROOT)}: {e}")
check(not bad_xml, f"{len(xml_files)} XML files well-formed", f"broken XML: {bad_xml}")

bad_json = []
for f in ROOT.glob("app/src/main/assets/textmate/*.json"):
    try:
        json.loads(f.read_text(encoding="utf-8"))
    except Exception as e:  # noqa: BLE001
        bad_json.append(f"{f.name}: {e}")
tm = list(ROOT.glob("app/src/main/assets/textmate/*.json"))
check(tm and not bad_json, f"{len(tm)} TextMate grammars valid JSON", f"broken JSON: {bad_json}")

# ----------------------------------------------------------------- kotlin ----
section("Kotlin wiring")
kt_main = ROOT / "app/src/main/java/app/stratos/host"
manifest = (ROOT / "app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
declared = re.findall(r'android:name="(\.[A-Za-z0-9_.]+)"', manifest)
for d in declared:
    rel = d.lstrip(".").replace(".", "/")
    check((kt_main / (rel + ".kt")).exists(),
          f"manifest class {d} exists", f"manifest class missing on disk: {d}")

routes = (kt_main / "ui/StratosNav.kt").read_text(encoding="utf-8")
for route, screen in [("DASHBOARD", "DashboardScreen"), ("SITES", "SitesScreen"),
                      ("EDITOR", "EditorScreen"), ("AI", "AiBuilderScreen"),
                      ("METRICS", "MetricsScreen"), ("TUNNEL", "TunnelScreen"),
                      ("SETTINGS", "SettingsScreen"), ("DOMAIN_WIZARD", "DomainWizardScreen")]:
    check(f'const val {route}' in routes and f'{screen}(' in routes,
          f"route {route} -> {screen} wired", f"route {route} not wired to {screen}")

for feature, frag in [
    ("share-target manifest filter", "android.intent.action.SEND"),
    ("open-with manifest filter", "android.intent.action.VIEW"),
    ("pending import queue", "pendingImports"),
    ("editor scale setting", "setEditorScale"),
    ("editor wrap setting", "setEditorWrap"),
    ("domain wizard token decoder", "decodeTunnelId"),
]:
    hits = any(frag in p.read_text(encoding="utf-8")
               for p in list(ROOT.glob("app/src/main/**/*.kt")) + [ROOT / "app/src/main/AndroidManifest.xml"])
    check(hits, f"feature present: {feature}", f"feature missing: {feature}")

# -------------------------------------------------------------- workflow ----
section("CI workflow")
wf = ROOT / ".github/workflows/build.yml"
check(wf.exists(), "build.yml exists (old ci.yml/release.yml removed)",
      "build.yml missing")
if wf.exists():
    txt = wf.read_text(encoding="utf-8")
    for frag in ("actions/checkout@v4", "gradlew :app:assembleDebug", "go test ./... -race"):
        check(frag in txt, f"workflow step: {frag}", f"workflow missing step: {frag}")
    check("ghp_" not in txt and "ghp_" not in gradle, "no PAT inside workflow/gradle",
          "hardcoded token pattern found in workflow/gradle")
old_wf = [p.name for p in (ROOT / ".github/workflows").glob("*.yml") if p.name != "build.yml"]
check(not old_wf, "no legacy workflow files remain", f"legacy workflows still present: {old_wf}")

# ---------------------------------------------------------------- secrets ----
section("Secret scan")
pat = re.compile(r"ghp_[A-Za-z0-9]{20,}|gho_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|AKIA[0-9A-Z]{16}")
hits = []
for f in ROOT.rglob("*"):
    if f.is_file() and not f.name.endswith((".zip", ".aar", ".jar", ".png", ".webp", ".jpg")):
        try:
            t = f.read_text(encoding="utf-8", errors="ignore")
        except Exception:  # noqa: BLE001
            continue
        for i, line in enumerate(t.splitlines(), 1):
            if pat.search(line) and "PAT" + "_" not in line:
                hits.append(f"{f.relative_to(ROOT)}:{i}")
check(not hits, "no token patterns anywhere in the tree", f"token-like strings at: {hits}")

# ------------------------------------------------------------ placeholders ----
section("Placeholder scan")
ph = []
for f in list(ROOT.glob("app/src/main/**/*.kt")) + list(ROOT.glob("*.md")) + list(ROOT.glob("docs/*.md")):
    for i, line in enumerate(f.read_text(encoding="utf-8", errors="ignore").splitlines(), 1):
        if re.search(r"TODO \(|【.*?】|FIXME|XXX placeholder", line):
            ph.append(f"{f.name}:{i}")
check(not ph, "no leftover placeholders", f"placeholders at: {ph}")

# ------------------------------------------------------------------ engine ----
section("Go engine")
check((ROOT / "engine/go.mod").exists(), "engine/go.mod present", "engine/go.mod missing")
go_tests = list(ROOT.glob("engine/**/*_test.go"))
check(len(go_tests) >= 4, f"{len(go_tests)} Go test files", "Go tests missing/thinned")

# ------------------------------------------------------------------ result ----
print(f"\n{'=' * 46}")
if ISSUES:
    print(f"SANITY FAILED — {len(ISSUES)} issue(s):")
    for i in ISSUES:
        print(f"  ❌ {i}")
    sys.exit(1)
print("SANITY PASSED — ship it 🚀")
