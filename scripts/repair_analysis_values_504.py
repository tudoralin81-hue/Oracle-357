from pathlib import Path
import re

gradle = Path('app/build.gradle')
s = gradle.read_text(encoding='utf-8')
s = re.sub(r'versionCode\s+\d+', 'versionCode 20', s, count=1)
s = re.sub(r"versionName\s+'[^']+'", "versionName 'V6g-KNOWLEDGE-B504'", s, count=1)
gradle.write_text(s, encoding='utf-8')

header = Path('app/src/main/java/ro/alintudor/oracle/nativeui/OracleNativeModule.kt')
s = header.read_text(encoding='utf-8')
s = re.sub(r'text=\"BUILD \d+\"', 'text="BUILD 504"', s, count=1)
header.write_text(s, encoding='utf-8')

real = Path('app/src/main/java/ro/alintudor/oracle/core/OracleRealData.kt')
s = real.read_text(encoding='utf-8')
s = s.replace('val fpe=(summary?.forwardPe ?: quote?.forwardPe ?: ts?.forwardPe)?.takeIf { it.isFinite() && it > 0.0 }', 'val fpe=listOf(summary?.forwardPe, quote?.forwardPe, ts?.forwardPe).firstOrNull { it != null && it.isFinite() && it > 0.0 }', 1)
s = s.replace('val cap=summary?.marketCap ?: quote?.marketCap ?: ts?.marketCap', 'val cap=ts?.marketCap ?: summary?.marketCap ?: quote?.marketCap', 1)
real.write_text(s, encoding='utf-8')

analysis = Path('app/src/main/java/ro/alintudor/oracle/nativeui/OracleAnalysisModules.kt')
s = analysis.read_text(encoding='utf-8')
s = s.replace('name to (r.rawValues.getOrNull(i) ?: "Valoare indisponibilă")', 'if (i == 0) null else name to (r.rawValues.getOrNull(i) ?: "Valoare indisponibilă")', 1)
s = s.replace('"ADX(14) ${money(adx)} • scor Oracle %.1f/100".format(Locale.US,adxScore)', '"ADX(14) ${money(adx)}"', 1)
analysis.write_text(s, encoding='utf-8')

print('B504 Analysis repair applied')
