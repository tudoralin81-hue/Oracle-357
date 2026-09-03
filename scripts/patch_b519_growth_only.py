from pathlib import Path

MAIN = Path("app/src/main/java/ro/alintudor/oracle/MainActivity.kt")
GROWTH = Path("app/src/main/java/ro/alintudor/oracle/nativeui/OracleGrowthModule.kt")

# HARD GUARD: B514 Analysis must remain byte-for-byte identical.
ANALYSIS = Path("app/src/main/java/ro/alintudor/oracle/nativeui/OracleAnalysisModules.kt")
EXPECTED_ANALYSIS_SHA = "76210ed4db93147487bc59e01ced70ae54f44ff6"
import hashlib
actual = hashlib.sha1(ANALYSIS.read_bytes()).hexdigest()
if actual != EXPECTED_ANALYSIS_SHA:
    raise SystemExit(f"REFUSED: Analysis baseline changed: {actual} != {EXPECTED_ANALYSIS_SHA}")

# Growth must not paint the cached previous-day snapshot before the current
# trading-day refresh completes. Analysis and every other module are untouched.
s = MAIN.read_text(encoding="utf-8")
old = '''    private fun openModule(key:String){
        currentModule=key
        runCatching{renderModule(key,false)}.onFailure{showModuleError(key,it)}
        if (key == "analysis") return
        if (key == "knowledge") {
            if (OracleKnowledgeSync.isStale(this)) {
                OracleKnowledgeSync.refreshAsync(this) { ok, error ->
                    if (currentModule != "knowledge" || isFinishing) return@refreshAsync
                    if (ok) runCatching { renderModule("knowledge", false) }.onFailure { showModuleError("knowledge", it) }
                    else if (error != null) Toast.makeText(this, "Knowledge refresh eșuat: $error", Toast.LENGTH_LONG).show()
                }
            }
            return
        }
        Thread{val result=runCatching{OracleLocalProcessor.refresh(repository)};mainHandler.post{if(currentModule!=key||isFinishing)return@post;result.onSuccess{runCatching{renderModule(key,false)}.onFailure{showModuleError(key,it)}}.onFailure{e->Toast.makeText(this,"Refresh local eșuat: ${e.message?:e.javaClass.simpleName}",Toast.LENGTH_LONG).show()}}}.start()
    }
'''
new = '''    private fun openModule(key:String){
        currentModule=key
        // GROWTH: never render repository.growth first. That list can be the
        // previous trading-day snapshot. Show only a neutral loading shell and
        // paint Growth after OracleLocalProcessor.refresh() has completed.
        if (key == "growth") {
            runCatching{renderGrowthLoading()}.onFailure{showModuleError(key,it)}
        } else {
            runCatching{renderModule(key,false)}.onFailure{showModuleError(key,it)}
        }
        if (key == "analysis") return
        if (key == "knowledge") {
            if (OracleKnowledgeSync.isStale(this)) {
                OracleKnowledgeSync.refreshAsync(this) { ok, error ->
                    if (currentModule != "knowledge" || isFinishing) return@refreshAsync
                    if (ok) runCatching { renderModule("knowledge", false) }.onFailure { showModuleError("knowledge", it) }
                    else if (error != null) Toast.makeText(this, "Knowledge refresh eșuat: $error", Toast.LENGTH_LONG).show()
                }
            }
            return
        }
        Thread{val result=runCatching{OracleLocalProcessor.refresh(repository)};mainHandler.post{if(currentModule!=key||isFinishing)return@post;result.onSuccess{runCatching{renderModule(key,false)}.onFailure{showModuleError(key,it)}}.onFailure{e->Toast.makeText(this,"Refresh local eșuat: ${e.message?:e.javaClass.simpleName}",Toast.LENGTH_LONG).show()}}}.start()
    }

    private fun renderGrowthLoading() {
        val title = titles["growth"] ?: "GROWTH"
        val preservedScrollY = OracleNativeModule.rememberedScroll(title)
        root.removeAllViews()
        val host = OracleNativeModule(this, title, { showHub() }, { refreshModule("growth") })
        root.addView(host.root, FrameLayout.LayoutParams(-1, -1))
        OracleGrowthModule(host).render(emptyList())
        host.restoreScrollY(preservedScrollY)
    }
'''
if old not in s:
    raise SystemExit("Expected MainActivity openModule block not found")
s = s.replace(old, new, 1)
MAIN.write_text(s, encoding="utf-8")

# Growth footer: always visible, including the loading/empty state.
g = GROWTH.read_text(encoding="utf-8")
old_empty = '''        if (items.isEmpty()) {
            host.addCard("GROWTH", "Nu există încă un snapshot Growth local. Refresh va afișa ultimul rezultat Oracle disponibil.")
            return
        }
'''
new_empty = '''        if (items.isEmpty()) {
            host.addCard("GROWTH", "Se încarcă snapshot-ul Growth al sesiunii curente…")
            addBuildFooter()
            return
        }
'''
if old_empty not in g:
    raise SystemExit("Expected Growth empty-state block not found")
g = g.replace(old_empty, new_empty, 1)
old_end = '''        addNews(ordered, fallbackNews)
        addHistory(items)
    }
'''
new_end = '''        addNews(ordered, fallbackNews)
        addHistory(items)
        addBuildFooter()
    }

    private fun addBuildFooter() {
        host.content.addView(TextView(host.root.context).apply {
            text = "ORACLE • V6g-FINAL-B519"
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = .08f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(110, 120, 140))
            setPadding(0, host.dp(8), 0, host.dp(20))
        }, LinearLayout.LayoutParams(-1, -2))
    }
'''
if old_end not in g:
    raise SystemExit("Expected Growth render tail not found")
g = g.replace(old_end, new_end, 1)
GROWTH.write_text(g, encoding="utf-8")

print("B519 Growth-only patch applied; Analysis untouched")
