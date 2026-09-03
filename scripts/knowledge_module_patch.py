from pathlib import Path
import re

p = Path('app/src/main/java/ro/alintudor/oracle/MainActivity.kt')
s = p.read_text()

# Keep the real MainActivity route, but replace the entire Knowledge renderer
# with a minimal, independent web-link card. No API/cache/sync is involved.
if '"knowledge"->OracleKnowledgeModule(host).render(' in s:
    start = s.find('            "knowledge"->OracleKnowledgeModule(host).render(')
    end = s.find('            )\n        }\n        host.restoreScrollY(preservedScrollY)', start)
    if start < 0 or end < 0:
        raise SystemExit('Knowledge route boundaries not found')
    s = s[:start] + '            "knowledge"->renderKnowledgeDirect(host)\n' + s[end + len('            )\n'):]

fn_start = s.find('    private fun renderKnowledgeDirect(host: OracleNativeModule) {')
fn_end = s.find('    private fun renderWatchlistDirect() {', fn_start)
if fn_start < 0 or fn_end < 0:
    raise SystemExit('Knowledge renderer boundaries not found')

fn = '''    private fun renderKnowledgeDirect(host: OracleNativeModule) {
        host.content.removeAllViews()
        val context = this
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(host.dp(18), host.dp(18), host.dp(18), host.dp(18))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.rgb(8, 14, 27))
                cornerRadius = host.dp(16).toFloat()
                setStroke(host.dp(1), Color.rgb(255, 205, 55))
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { openKnowledgeUrl("https://alintudor.ro/knowledge/") }
        }
        card.addView(TextView(context).apply {
            text = "KNOWLEDGE"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(255, 215, 45))
        })
        card.addView(TextView(context).apply {
            text = "Deschide alintudor.ro/knowledge/"
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(0, host.dp(8), 0, host.dp(12))
        })
        card.addView(Button(context).apply {
            text = "DESCHIDE KNOWLEDGE"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.rgb(12, 54, 82))
                cornerRadius = host.dp(11).toFloat()
            }
            setOnClickListener { openKnowledgeUrl("https://alintudor.ro/knowledge/") }
        }, LinearLayout.LayoutParams(-1, host.dp(46)))
        host.content.addView(card, LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(0, 0, 0, host.dp(14))
        })
    }

'''
s = s[:fn_start] + fn + s[fn_end:]
p.write_text(s)
print('Knowledge reduced to direct web-link card')
