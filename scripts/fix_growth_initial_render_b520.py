from pathlib import Path

MAIN = Path("app/src/main/java/ro/alintudor/oracle/MainActivity.kt")
BUILD = Path("app/build.gradle")

s = MAIN.read_text(encoding="utf-8")

old_open = '''    private fun openModule(key:String){
        currentModule=key
        runCatching{renderModule(key,false)}.onFailure{showModuleError(key,it)}
        if (key == "analysis") return
'''
new_open = '''    private fun openModule(key:String){
        currentModule=key
        if (key == "growth") {
            // Growth must never paint the cached previous-day snapshot first.
            // Show the loading shell, then let the existing background refresh
            // populate the current trading-day snapshot.
            runCatching{renderGrowthLoading()}.onFailure{showModuleError(key,it)}
        } else {
            runCatching{renderModule(key,false)}.onFailure{showModuleError(key,it)}
        }
        if (key == "analysis") return
'''
if old_open not in s:
    raise SystemExit("Expected openModule block not found")
s = s.replace(old_open, new_open, 1)

anchor = '''    private fun openWatchlistTicker(ticker: String) {
'''
helper = '''    private fun renderGrowthLoading() {
        val title = titles["growth"] ?: "GROWTH"
        val preservedScrollY = OracleNativeModule.rememberedScroll(title)
        root.removeAllViews()
        val host = OracleNativeModule(this, title, { showHub() }, { refreshModule("growth") })
        root.addView(host.root, FrameLayout.LayoutParams(-1, -1))
        OracleGrowthModule(host).render(emptyList())
        host.restoreScrollY(preservedScrollY)
    }

'''
if anchor not in s:
    raise SystemExit("Expected watchlist anchor not found")
s = s.replace(anchor, helper + anchor, 1)

MAIN.write_text(s, encoding="utf-8")

g = BUILD.read_text(encoding="utf-8")
g = g.replace("versionCode 31", "versionCode 32", 1)
g = g.replace("versionName 'V6g-FINAL-B519'", "versionName 'V6g-FINAL-B520'", 1)
BUILD.write_text(g, encoding="utf-8")

print("B520: Growth initial render now uses loading-only shell; Analysis untouched")
