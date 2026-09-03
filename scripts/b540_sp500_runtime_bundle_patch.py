from pathlib import Path
import re

p = Path('app/src/main/java/ro/alintudor/oracle/core/OracleGrowthEngine.kt')
s = p.read_text(encoding='utf-8')

# Replace the network-dependent universe loader with the build-time bundle.
start = s.find('    private fun loadUniverse():List<String>{')
if start < 0:
    raise SystemExit('loadUniverse not found')
brace = s.find('{', start)
depth = 0
end = None
for i in range(brace, len(s)):
    if s[i] == '{': depth += 1
    elif s[i] == '}':
        depth -= 1
        if depth == 0:
            end = i + 1
            break
if end is None:
    raise SystemExit('loadUniverse end not found')
new_loader = '''    private fun loadUniverse():List<String> = OracleSp500Universe.symbols\n'''
s = s[:start] + new_loader + s[end:]

# Make the bundled company/sector metadata authoritative for recommendation display.
s = s.replace(
    'val byTicker=seed.associateBy{it.ticker.uppercase(Locale.US)}',
    'val byTicker=seed.associateBy{it.ticker.uppercase(Locale.US)}', 1)
s = s.replace(
    'val company=meta?.company?.takeIf { it.isNotBlank() && !it.equals(pick.ticker,true) } ?: lookupCompanyName(pick.ticker) ?: pick.ticker',
    'val company=meta?.company?.takeIf { it.isNotBlank() && !it.equals(pick.ticker,true) } ?: OracleSp500Universe.names[pick.ticker] ?: lookupCompanyName(pick.ticker) ?: pick.ticker', 1)
s = s.replace(
    'val sector=OracleRealData.resolvedSector(c.ticker,f?.sector)',
    'val sector=OracleRealData.resolvedSector(c.ticker,f?.sector ?: OracleSp500Universe.sectors[c.ticker])',
)
s = s.replace(
    'val sector=OracleRealData.resolvedSector(pick.ticker,f?.sector ?: meta?.sector) ?: "—"',
    'val sector=OracleRealData.resolvedSector(pick.ticker,f?.sector ?: meta?.sector ?: OracleSp500Universe.sectors[pick.ticker]) ?: "—"',
)
p.write_text(s, encoding='utf-8')

# Fix all market-data UAs to a browser UA here as a final guard.
m = Path('app/src/main/java/ro/alintudor/oracle/core/OracleMarketData.kt')
ms = m.read_text(encoding='utf-8')
ms = ms.replace('Oracle-Stock-Intelligence/1.0', 'Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/126.0.0.0 Mobile Safari/537.36')
m.write_text(ms, encoding='utf-8')
print('Bundled S&P500 runtime patch applied')
