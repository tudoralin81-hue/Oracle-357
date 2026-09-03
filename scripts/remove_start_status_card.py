from pathlib import Path

p = Path('app/src/main/java/ro/alintudor/oracle/MainActivity.kt')
s = p.read_text(encoding='utf-8')
start = s.find('        val status=LinearLayout(this).apply {')
end_marker = '        page.addView(status,LinearLayout.LayoutParams(-1,-2).apply{setMargins(0,dp(8),0,dp(8))})\n'
if start < 0 or end_marker not in s[start:]:
    raise SystemExit('Expected Start status-card block not found')
end = s.find(end_marker, start) + len(end_marker)
s = s[:start] + s[end:]
if 'ORACLE READY' in s or 'LOCAL INTELLIGENCE' in s or 'page.addView(status' in s:
    raise SystemExit('Start status-card content still present')
if 'OracleHeroView(this){ openModule(it) }' not in s:
    raise SystemExit('Approved rectangular OracleHeroView Start is not present')
p.write_text(s, encoding='utf-8')
print('Removed the complete Start status-card block; approved rectangular Start remains unchanged.')
