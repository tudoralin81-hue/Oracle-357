from pathlib import Path

p = Path('app/src/main/java/ro/alintudor/oracle/MainActivity.kt')
s = p.read_text()

# The direct Watchlist implementation lives in MainActivity, so import the
# persistent store explicitly. Keep the patch idempotent for every build.
imp = 'import ro.alintudor.oracle.core.OracleWatchlistStore\n'
anchor = 'import ro.alintudor.oracle.core.OracleRepository\n'
if imp not in s:
    if anchor not in s:
        raise SystemExit('OracleRepository import anchor not found')
    s = s.replace(anchor, anchor + imp, 1)

needle = '                    setOnClickListener { openWatchlistTicker(ticker) }'
if needle in s and 'FINAL_WATCHLIST_DIRECT_TOUCH_V2' not in s:
    replacement = '''                    setOnClickListener { openWatchlistTicker(ticker) }
                    // FINAL_WATCHLIST_DIRECT_TOUCH_V2
                    setOnTouchListener { v, event ->
                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                scroll.requestDisallowInterceptTouchEvent(true)
                                false
                            }
                            MotionEvent.ACTION_UP -> {
                                scroll.requestDisallowInterceptTouchEvent(false)
                                v.performClick()
                                true
                            }
                            MotionEvent.ACTION_CANCEL -> {
                                scroll.requestDisallowInterceptTouchEvent(false)
                                false
                            }
                            else -> false
                        }
                    }'''
    s = s.replace(needle, replacement, 1)

# Make the complete row a fallback navigation target while DELETE remains a
# separate child action.
if 'FINAL_WATCHLIST_ROW_TOUCH_V2' not in s:
    row_anchor = '''                val delete = Button(this).apply {'''
    row_patch = '''                // FINAL_WATCHLIST_ROW_TOUCH_V2
                row.setOnTouchListener { _, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            scroll.requestDisallowInterceptTouchEvent(true)
                            false
                        }
                        MotionEvent.ACTION_UP -> {
                            scroll.requestDisallowInterceptTouchEvent(false)
                            openWatchlistTicker(ticker)
                            true
                        }
                        MotionEvent.ACTION_CANCEL -> {
                            scroll.requestDisallowInterceptTouchEvent(false)
                            false
                        }
                        else -> false
                    }
                }

                val delete = Button(this).apply {'''
    if row_anchor not in s:
        raise SystemExit('Watchlist row anchor not found')
    s = s.replace(row_anchor, row_patch, 1)

p.write_text(s)
print('FINAL Watchlist direct navigation patch applied')
