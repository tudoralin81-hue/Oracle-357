from pathlib import Path

ROOT = Path('.')
ANALYSIS = ROOT / 'app/src/main/java/ro/alintudor/oracle/core/OracleAnalysisEngine.kt'
GROWTH = ROOT / 'app/src/main/java/ro/alintudor/oracle/core/OracleGrowthEngine.kt'
BOOT = ROOT / 'app/src/main/java/ro/alintudor/oracle/core/OracleBootstrap.kt'

# Risk is categorical for display, but allocation must remain a continuous,
# ticker-specific calculation. Do not collapse every RIDICAT ticker to 3.0%.
HELPER = '''\n    data class RiskAllocation(val risk: String, val allocation: Double)\n\n    fun calculateRiskAllocation(rsi: Double, volumeRatio: Double, momentum5D: Double, atrPct: Double, trend: Double): RiskAllocation {\n        val rsiRisk = ((rsi - 55.0) / 25.0).coerceIn(0.0, 1.0)\n        val volumeRisk = ((volumeRatio - 1.0) / 2.0).coerceIn(0.0, 1.0)\n        val momentumRisk = (momentum5D / 20.0).coerceIn(0.0, 1.0)\n        val volatilityRisk = (atrPct / 8.0).coerceIn(0.0, 1.0)\n        val riskScore = 100.0 * (rsiRisk * 0.30 + volumeRisk * 0.15 + momentumRisk * 0.30 + volatilityRisk * 0.25)\n        val risk = when {\n            riskScore >= 60.0 -> \"RIDICAT\"\n            riskScore >= 35.0 -> \"MEDIU\"\n            else -> \"SCĂZUT\"\n        }\n        val trendBase = (2.0 + trend / 12.5).coerceIn(2.0, 10.0)\n        val riskMultiplier = (1.0 - 0.45 * (riskScore / 100.0)).coerceIn(0.55, 1.0)\n        val allocation = (trendBase * riskMultiplier * 2.0).let { kotlin.math.round(it) / 2.0 }.coerceIn(1.0, 8.0)\n        return RiskAllocation(risk, allocation)\n    }\n'''

s = ANALYSIS.read_text(encoding='utf-8')
if 'fun calculateRiskAllocation(' not in s:
    marker = '    fun analyze(raw:String):Result? {'
    s = s.replace(marker, HELPER + '\n' + marker, 1)
old = '        val risk=if(rsi>75||vr>2.5||m5>12||atrPct>7)"RIDICAT" else if(trend>=75)"MEDIU" else "RIDICAT"\n        var alloc=when{trend>=90->8.0;trend>=85->7.0;trend>=80->6.0;trend>=75->5.0;trend>=70->4.0;else->2.0};if(risk=="RIDICAT")alloc=min(alloc,4.0);if(rsi>75)alloc=min(alloc,3.0);if(m5>12)alloc=min(alloc,3.0);if(atrPct>7)alloc=min(alloc,3.0)'
new = '        val riskAllocation=calculateRiskAllocation(rsi,vr,m5,atrPct,trend);val risk=riskAllocation.risk;val alloc=riskAllocation.allocation'
if old in s:
    s = s.replace(old, new, 1)
else:
    start = s.find('    data class RiskAllocation(')
    end = s.find('    fun analyze(raw:String):Result? {', start)
    if start < 0 or end < 0:
        raise SystemExit('Could not locate RiskAllocation helper in AnalysisEngine')
    s = s[:start] + HELPER.lstrip('\n') + '\n' + s[end:]
ANALYSIS.write_text(s, encoding='utf-8')

s = GROWTH.read_text(encoding='utf-8')
old = '        val rr=(70-atrPct*5+(if(breakout>=100)15 else 0)).coerceIn(0.0,100.0); val risk=if(rsi>75||vr>2.5||m5>12||atrPct>7)"RIDICAT" else if(trend>=75)"MEDIU" else "RIDICAT"\n        var alloc=when { trend>=90->8.0; trend>=85->7.0; trend>=80->6.0; trend>=75->5.0; trend>=70->4.0; else->2.0 }; if(risk=="RIDICAT")alloc=min(alloc,4.0); if(rsi>75)alloc=min(alloc,3.0); if(m5>12)alloc=min(alloc,3.0); if(atrPct>7)alloc=min(alloc,3.0)'
new = '        val rr=(70-atrPct*5+(if(breakout>=100)15 else 0)).coerceIn(0.0,100.0); val riskAllocation=OracleAnalysisEngine.calculateRiskAllocation(rsi,vr,m5,atrPct,trend); val risk=riskAllocation.risk; val alloc=riskAllocation.allocation'
if old in s:
    s = s.replace(old, new, 1)
else:
    raise SystemExit('GrowthEngine risk/allocation block not found')
GROWTH.write_text(s, encoding='utf-8')

s = BOOT.read_text(encoding='utf-8')
s = s.replace('private const val VERSION = 9', 'private const val VERSION = 12', 1)
s = s.replace('private const val VERSION = 10', 'private const val VERSION = 12', 1)
s = s.replace('private const val VERSION = 11', 'private const val VERSION = 12', 1)
marker = '        val crmAllocation = calculatedAllocation("CRM", 3.0)'
risk_lines = '''        val snpsRisk = OracleAnalysisEngine.analyze("SNPS")?.risk ?: "RIDICAT"\n        val veevRisk = OracleAnalysisEngine.analyze("VEEV")?.risk ?: "RIDICAT"\n        val crmRisk = OracleAnalysisEngine.analyze("CRM")?.risk ?: "RIDICAT"'''
if 'val snpsRisk = OracleAnalysisEngine.analyze("SNPS")?.risk' not in s:
    if marker not in s:
        raise SystemExit('Bootstrap allocation marker not found')
    s = s.replace(marker, marker + '\n\n' + risk_lines, 1)
s = s.replace('score=86, signal="STRONG BUY", risk="RIDICAT", allocationMax=snpsAllocation', 'score=86, signal="STRONG BUY", risk=snpsRisk, allocationMax=snpsAllocation', 1)
s = s.replace('score=85, signal="STRONG BUY", risk="RIDICAT", allocationMax=veevAllocation', 'score=85, signal="STRONG BUY", risk=veevRisk, allocationMax=veevAllocation', 1)
s = s.replace('score=81, signal="BUY", risk="RIDICAT", allocationMax=crmAllocation', 'score=81, signal="BUY", risk=crmRisk, allocationMax=crmAllocation', 1)
BOOT.write_text(s, encoding='utf-8')

print('Growth risk/allocation patch applied: continuous ticker-specific allocation and calculated risk, shared by Analysis and Growth, bootstrap bumped to 12.')
