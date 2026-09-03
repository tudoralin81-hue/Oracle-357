from pathlib import Path
import re
import shutil

# B514 FINAL APK: Growth-only patch.
# Analysis is sourced directly from current main and must remain byte-for-byte unchanged.
ANALYSIS = Path('app/src/main/java/ro/alintudor/oracle/nativeui/OracleAnalysisModules.kt')
MAIN = Path('app/src/main/java/ro/alintudor/oracle/MainActivity.kt')
GROWTH = Path('app/src/main/java/ro/alintudor/oracle/nativeui/OracleGrowthModule.kt')
LOCAL = Path('app/src/main/java/ro/alintudor/oracle/core/OracleLocalProcessor.kt')
GRADLE = Path('app/build.gradle')
BEFORE = Path('/tmp/oracle_analysis_before.kt')

shutil.copyfile(ANALYSIS, BEFORE)

# 1) Start the expensive Growth calculation at application startup, but use the
# Growth-only processor so Analysis/Portfolio/etc. are not touched by the warm-up.
s = MAIN.read_text(encoding='utf-8')
if 'private var growthPreloadRunning' not in s:
    s = s.replace(
'''    private var currentModule: String? = null
    private val mainHandler = Handler(Looper.getMainLooper())
''',
'''    private var currentModule: String? = null
    private var growthPreloadRunning = false
    private var growthPreloadCompleted = false
    private val mainHandler = Handler(Looper.getMainLooper())
''', 1)

s = s.replace(
'''        runCatching { OracleBootstrap.ensure(repository); showHub() }.onFailure { showFatalError("Pornirea Oracle a eșuat",it) }
''',
'''        runCatching {
            OracleBootstrap.ensure(repository)
            showHub()
            startGrowthPreload()
        }.onFailure { showFatalError("Pornirea Oracle a eșuat",it) }
''', 1)

old_open = '''    private fun openModule(key:String){
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
new_open = '''    private fun openModule(key:String){
        currentModule=key
        if (key == "growth") {
            runCatching { renderModule("growth", false) }.onFailure { showModuleError("growth", it) }
            if (!growthPreloadRunning && !growthPreloadCompleted) startGrowthPreload()
            return
        }
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

    private fun startGrowthPreload(force:Boolean = false) {
        if (growthPreloadRunning) return
        if (!force && growthPreloadCompleted) {
            if (currentModule == "growth") runCatching { renderModule("growth", false) }.onFailure { showModuleError("growth", it) }
            return
        }
        growthPreloadRunning = true
        Thread {
            val result = runCatching { OracleLocalProcessor.refreshGrowthOnly(repository) }
            mainHandler.post {
                growthPreloadRunning = false
                growthPreloadCompleted = result.isSuccess
                if (currentModule != "growth" || isFinishing) return@post
                if (result.isSuccess) {
                    runCatching { renderModule("growth", false) }.onFailure { showModuleError("growth", it) }
                } else {
                    runCatching { renderModule("growth", false) }.onFailure { showModuleError("growth", it) }
                    Toast.makeText(this, "Growth refresh eșuat: ${result.exceptionOrNull()?.message ?: "eroare"}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }
'''
if old_open not in s:
    raise SystemExit('MainActivity openModule anchor not found')
s = s.replace(old_open, new_open, 1)

old_refresh = '''    private fun refreshModule(key:String){
        if(currentModule!=key || isFinishing)return
        Toast.makeText(this,"Se actualizează ${titles[key]?:key.uppercase()}…",Toast.LENGTH_SHORT).show()
        Thread{
            val result=runCatching{OracleLocalProcessor.refresh(repository)}
            mainHandler.post{
                if(currentModule!=key || isFinishing)return@post
                result.onSuccess{runCatching{renderModule(key,false)}.onFailure{showModuleError(key,it)}}
                    .onFailure{e->Toast.makeText(this,"Refresh local eșuat: ${e.message?:e.javaClass.simpleName}",Toast.LENGTH_LONG).show()}
            }
        }.start()
    }
'''
new_refresh = '''    private fun refreshModule(key:String){
        if(currentModule!=key || isFinishing)return
        Toast.makeText(this,"Se actualizează ${titles[key]?:key.uppercase()}…",Toast.LENGTH_SHORT).show()
        if (key == "growth") {
            growthPreloadCompleted = false
            runCatching { renderModule("growth", false) }.onFailure { showModuleError("growth", it) }
            startGrowthPreload(force = true)
            return
        }
        Thread{
            val result=runCatching{OracleLocalProcessor.refresh(repository)}
            mainHandler.post{
                if(currentModule!=key || isFinishing)return@post
                result.onSuccess{runCatching{renderModule(key,false)}.onFailure{showModuleError(key,it)}}
                    .onFailure{e->Toast.makeText(this,"Refresh local eșuat: ${e.message?:e.javaClass.simpleName}",Toast.LENGTH_LONG).show()}
            }
        }.start()
    }
'''
if old_refresh not in s:
    raise SystemExit('MainActivity refreshModule anchor not found')
s = s.replace(old_refresh, new_refresh, 1)
MAIN.write_text(s, encoding='utf-8')

# The Growth UI is a loader while no current snapshot exists. The actual
# calculation is performed by OracleLocalProcessor.refreshGrowthOnly().
s = GROWTH.read_text(encoding='utf-8')
old = '''        if (items.isEmpty()) {
            host.addCard("GROWTH", "Nu există încă un snapshot Growth local. Refresh va afișa ultimul rezultat Oracle disponibil.")
            return
        }'''
new = '''        if (items.isEmpty()) {
            addLoadingState()
            return
        }'''
if old in s:
    s = s.replace(old, new, 1)
if 'private fun addLoadingState()' not in s:
    marker = '''    private fun addSummary(items: List<OracleGrowthRecommendation>) {
'''
    loader = '''    private fun addLoadingState() {
        val card = card(18)
        card.gravity = Gravity.CENTER
        val spinner = ProgressBar(host.root.context).apply { isIndeterminate = true }
        card.addView(spinner, LinearLayout.LayoutParams(host.dp(54), host.dp(54)).apply { gravity = Gravity.CENTER })
        card.addView(text("GROWTH", 17f, Typeface.DEFAULT_BOLD, green, 0, 10).apply { gravity = Gravity.CENTER })
        card.addView(text("Se calculează recomandările…", 13f, Typeface.DEFAULT, muted, 0, 5).apply { gravity = Gravity.CENTER })
        card.addView(text("Calculul rulează în fundal. Valorile apar numai după finalizare.", 10f, Typeface.DEFAULT, muted, 0, 7).apply { gravity = Gravity.CENTER })
        host.content.addView(card, LinearLayout.LayoutParams(-1, host.dp(190)).apply { setMargins(0, 0, 0, host.dp(10)) })
        addBuildFooter()
    }

'''
    if marker not in s:
        raise SystemExit('Growth addSummary anchor not found')
    s = s.replace(marker, loader + marker, 1)
if 'private fun addBuildFooter()' not in s:
    marker = '''    private fun horizonLabel(horizon: String) = when (horizon.uppercase(Locale.US)) {
'''
    footer = '''    private fun addBuildFooter() {
        host.content.addView(text("BUILD B514 • V6g-FINAL", 9f, Typeface.DEFAULT_BOLD, Color.rgb(125, 135, 155), host.dp(4), 8), LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(0, 0, 0, host.dp(18))
        })
    }

'''
    if marker not in s:
        raise SystemExit('Growth footer anchor not found')
    s = s.replace(marker, footer + marker, 1)
if 'addBuildFooter()' not in s.split('fun render',1)[1]:
    s = s.replace('''        addNews(ordered, fallbackNews)
        addHistory(items)
    }
''','''        addNews(ordered, fallbackNews)
        addHistory(items)
        addBuildFooter()
    }
''',1)
GROWTH.write_text(s, encoding='utf-8')

# Hard guard: Growth-only build script must not alter Analysis.
if ANALYSIS.read_bytes() != BEFORE.read_bytes():
    raise SystemExit('B514 Growth-only patch changed Analysis; this is forbidden.')

# Keep the release identity fixed for the B514 installable APK.
s = GRADLE.read_text(encoding='utf-8')
s = re.sub(r'versionCode\s+\d+', 'versionCode 35', s, count=1)
s = re.sub(r"versionName\s+'[^']+'", "versionName 'V6g-FINAL-B514'", s, count=1)
GRADLE.write_text(s, encoding='utf-8')

print('B514 Growth-only preload + animated loader applied; Analysis is byte-for-byte unchanged.')
