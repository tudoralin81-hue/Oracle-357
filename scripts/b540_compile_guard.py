from pathlib import Path
p=Path('app/src/main/java/ro/alintudor/oracle/nativeui/OracleGrowthModule.kt')
s=p.read_text()
# Fix only the known B539 compile regression: visibility belongs to the row View, never to recommendation data.
s=s.replace('all.drop(6).forEach { it.visibility = View.GONE }','')
p.write_text(s)
