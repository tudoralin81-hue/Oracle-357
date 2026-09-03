from pathlib import Path

MAIN = Path('app/src/main/java/ro/alintudor/oracle/MainActivity.kt')
s = MAIN.read_text(encoding='utf-8')
old = '''    private fun showHub() {
        currentModule=null; root.removeAllViews()
        val scroll=ScrollView(this).apply { isFillViewport=true; setBackgroundColor(Color.rgb(1,3,8)) }
        val page=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(10),dp(6),dp(10),dp(24)) }
        val hero=OracleHeroView(this){ openModule(it) }
        val heroHeightPx=(resources.displayMetrics.heightPixels*.80f).toInt().coerceAtLeast(dp(620))
        page.addView(hero,LinearLayout.LayoutParams(-1,heroHeightPx))
        val status=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL; setPadding(dp(14),dp(11),dp(14),dp(11)); setBackgroundColor(Color.rgb(8,12,24)) }
        status.addView(View(this).apply{setBackgroundColor(Color.rgb(50,220,135))},LinearLayout.LayoutParams(dp(8),dp(8)))
        status.addView(TextView(this).apply{text="  ORACLE READY";textSize=13f;typeface=Typeface.DEFAULT_BOLD;setTextColor(Color.WHITE)},LinearLayout.LayoutParams(0,-2,1f))
        status.addView(TextView(this).apply{text="LOCAL INTELLIGENCE";textSize=10f;setTextColor(Color.rgb(140,150,170))})
        page.addView(status,LinearLayout.LayoutParams(-1,-2).apply{setMargins(0,dp(8),0,dp(8))})
        scroll.addView(page); root.addView(scroll,FrameLayout.LayoutParams(-1,-1))
    }
'''
new = '''    private fun showHub() {
        currentModule = null
        root.removeAllViews()
        root.addView(OracleStartView(this) { openModule(it) }, FrameLayout.LayoutParams(-1, -1))
    }
'''
if old not in s:
    raise SystemExit('Expected B514 Start block not found; refusing to modify another version.')
MAIN.write_text(s.replace(old, new, 1), encoding='utf-8')
print('START_REDESIGN_APPLIED')
