from pathlib import Path

# Idempotent repair: safe to run on every push.

native = Path("app/src/main/java/ro/alintudor/oracle/nativeui/OracleNativeModule.kt")
s = native.read_text(encoding="utf-8")
old = 'center.addView(TextView(context).apply { text="BUILD 494";textSize=8f;typeface=Typeface.DEFAULT_BOLD;letterSpacing=.10f;setTextColor(Color.rgb(115,150,190));gravity=Gravity.CENTER;includeFontPadding=true })'
new = 'center.addView(TextView(context).apply { text="BUILD 498";textSize=10f;typeface=Typeface.DEFAULT_BOLD;letterSpacing=.10f;setTextColor(Color.rgb(25,205,255));gravity=Gravity.CENTER;includeFontPadding=true })\n        // ANALYSIS_VALUES_REPAIR_498'
if old in s:
    native.write_text(s.replace(old, new, 1), encoding="utf-8")

real = Path("app/src/main/java/ro/alintudor/oracle/core/OracleRealData.kt")
s = real.read_text(encoding="utf-8")
old_pe = 'val pe=summary?.trailingPe ?: quote?.trailingPe ?: ts?.trailingPe'
new_pe = 'val pe=(summary?.trailingPe ?: quote?.trailingPe ?: ts?.trailingPe)?.takeIf { it.isFinite() && it > 0.0 }'
old_fpe = 'val fpe=summary?.forwardPe ?: quote?.forwardPe ?: ts?.forwardPe'
new_fpe = 'val fpe=(summary?.forwardPe ?: quote?.forwardPe ?: ts?.forwardPe)?.takeIf { it.isFinite() && it > 0.0 }'
if old_pe in s:
    s = s.replace(old_pe, new_pe, 1)
if old_fpe in s:
    s = s.replace(old_fpe, new_fpe, 1)
# Use the public-reference sector naming as the fallback when the remote source omits sector.
s = s.replace('->"Information Technology"', '->"Technology"', 1)
real.write_text(s, encoding="utf-8")

analysis = Path("app/src/main/java/ro/alintudor/oracle/nativeui/OracleAnalysisModules.kt")
s = analysis.read_text(encoding="utf-8")
old = 'l == "REVENUE GROWTH"'
new = 'l.startsWith("REVENUE GROWTH")'
if old in s:
    analysis.write_text(s.replace(old, new, 1), encoding="utf-8")

# Keep the APK metadata synchronized with the visible Analysis build number.
gradle = Path("app/build.gradle")
s = gradle.read_text(encoding="utf-8")
s = s.replace("versionCode 16", "versionCode 17", 1)
s = s.replace("versionName 'V6g-KNOWLEDGE-B494'", "versionName 'V6g-KNOWLEDGE-B498'", 1)
gradle.write_text(s, encoding="utf-8")

print("Analysis 498 repair checked/applied.")
