from pathlib import Path
import re

# Analysis UI must display the sector resolved by the analysis engine, not a small UI-only ticker map.
ui = Path('app/src/main/java/ro/alintudor/oracle/nativeui/OracleAnalysisModules.kt')
s = ui.read_text(encoding='utf-8')
old = 'text = "${companyName(r.ticker)}   •   Sector: ${sector(r.ticker)}"'
new = 'text = "${companyName(r.ticker)}   •   Sector: ${r.sector ?: "Sector indisponibil"}"'
if old in s:
    s = s.replace(old, new, 1)
else:
    s = re.sub(r'text = "\$\{companyName\(r\.ticker\)\}.*?Sector: \$\{sector\(r\.ticker\)\}"', new, s, count=1)
# Remove the obsolete hard-coded UI sector() function if still present.
s = re.sub(r'\n    private fun sector\(t: String\) = when \(t\) \{.*?\n    \}\n', '\n', s, count=1, flags=re.S)
ui.write_text(s, encoding='utf-8')

# APLD was missing from the deterministic fallback even though APD was present.
real = Path('app/src/main/java/ro/alintudor/oracle/core/OracleRealData.kt')
r = real.read_text(encoding='utf-8')
r = r.replace('"LIN","APD","SHW"', '"LIN","APD","APLD","SHW"', 1)
real.write_text(r, encoding='utf-8')

print('Analysis sector fix applied: UI uses Result.sector; APLD fallback added')
