# Oracle — R8 rules for the release build.
#
# General approach: let R8 obfuscate and shrink everything by default (that's
# the whole point — it's what turns a decompiled APK into meaningless class/
# method names instead of readable Oracle source). Only the few things below
# that are found by NAME at runtime (reflection, or things the OS looks up by
# exact class name outside the manifest) need an explicit keep rule; anything
# not listed here is intentionally left to be renamed/stripped.

# OracleAnalysisWatchlistEyeOverlay finds this method via reflection by its
# literal string name ("openModule") to jump from Watchlist into Analysis.
# Without this rule, R8 renames the method and that lookup throws at runtime.
-keepclassmembers class ro.alintudor.luxoculi.OracleMysticActivity {
    private void openModule(java.lang.String);
}

# Activities/Services/Receivers declared in AndroidManifest.xml are already
# kept automatically by the Android Gradle Plugin's built-in rules (the OS
# finds them by the exact class name in the manifest) — no manual keep rules
# needed here for OracleFirebaseMessagingService, OracleGrowthWidgetProvider,
# OracleWidgetConfigActivity, or OracleKnowledgeRefreshReceiver.

# Firebase Messaging ships its own consumer-rules.pro inside the library AAR,
# applied automatically — no manual Firebase keep rules needed either.

# org.json (JSONObject/JSONArray) is part of the Android platform itself,
# never touched by R8 regardless of these rules.

# Keep line numbers in stack traces for crash reports, without keeping the
# original file names (smaller, still useful for debugging a real crash).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
