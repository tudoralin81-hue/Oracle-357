from pathlib import Path
import csv
import io
import urllib.request

URL = "https://raw.githubusercontent.com/datasets/s-and-p-500-companies/main/data/constituents.csv"
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

data = urllib.request.urlopen(urllib.request.Request(URL, headers={"User-Agent": UA}), timeout=15).read().decode("utf-8-sig")
rows = list(csv.DictReader(io.StringIO(data)))
seen = set()
items = []
for r in rows:
    symbol = (r.get("Symbol") or "").strip().upper()
    name = (r.get("Security") or "").strip()
    sector = (r.get("GICS Sector") or "").strip()
    if not symbol or not name or name in seen:
        continue
    seen.add(name)
    items.append((symbol, name, sector))
if len(items) < 500:
    raise SystemExit(f"S&P 500 bundle contains only {len(items)} unique companies")
items = items[:500]

def k(s):
    return s.replace("\\", "\\\\").replace('"', '\\"')

kt = [
    "package ro.alintudor.oracle.core", "",
    "/** Build-time snapshot of the 500 S&P 500 constituent companies. Runtime never downloads the universe. */",
    "object OracleSp500Universe {",
    "    const val SIZE:Int = 500",
    "    val symbols:List<String> = listOf(" + ",".join('"'+k(s)+'"' for s,_,_ in items) + ")",
    "    val names:Map<String,String> = mapOf(" + ",".join('"'+k(s)+'" to "'+k(n)+'"' for s,n,_ in items) + ")",
    "    val sectors:Map<String,String> = mapOf(" + ",".join('"'+k(s)+'" to "'+k(sec)+'"' for s,_,sec in items if sec) + ")",
    "}", ""
]
Path("app/src/main/java/ro/alintudor/oracle/core/OracleSp500Universe.kt").write_text("\n".join(kt), encoding="utf-8")
print(f"Bundled {len(items)} unique S&P 500 companies")
