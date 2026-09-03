from pathlib import Path
import base64


def patch(path: str, transform):
    p = Path(path)
    s = p.read_text(encoding='utf-8')
    out = transform(s)
    if out == s:
        print(f'No patch needed for {path}; source is already in the desired state.')
        return
    p.write_text(out, encoding='utf-8')


def patch_main(s: str) -> str:
    if 'rememberedScroll(title)' in s or 'savedScroll' in s:
        return s
    needle = '    private fun renderModule(key:String,refresh:Boolean=false){'
    start = s.index(needle)
    end = s.index('\n    private fun showModuleError', start)
    block = s[start:end]
    old = '    private fun renderModule(key:String,refresh:Boolean=false){\n        root.removeAllViews();'
    if old not in block:
        raise SystemExit('renderModule layout changed; refusing unsafe patch.')
    block = block.replace(old, '    private fun renderModule(key:String,refresh:Boolean=false){\n        val savedScroll = OracleNativeModule.savedScrollY(key)\n        root.removeAllViews();', 1)
    close = block.rfind('    }')
    block = block[:close] + '        host.restoreScrollY(savedScroll)\n' + block[close:]
    return s[:start] + block + s[end:]


def patch_native(s: str) -> str:
    if 'fun rememberedScroll(title:String)' in s:
        return s
    if 'fun savedScrollY(title:String)' not in s:
        s = s.replace(
            '        private val scrollPositions = mutableMapOf<String, Int>()\n',
            '        private val scrollPositions = mutableMapOf<String, Int>()\n        fun savedScrollY(title:String): Int = scrollPositions[title] ?: 0\n'
        )
    s = s.replace(
        '    private var downY = 0f\n    private var dragging = false',
        '    private var downY = 0f\n    private var downX = 0f\n    private var dragging = false'
    )
    s = s.replace('    private val threshold = (72f * resources.displayMetrics.density)', '    private val threshold = (48f * resources.displayMetrics.density)')
    s = s.replace('                downY = ev.y\n                dragging = false', '                downY = ev.y\n                downX = ev.x\n                dragging = false')
    s = s.replace(
        '                val dy = ev.y - downY\n                if (dy > (10f * resources.displayMetrics.density) && child != null && !child.canScrollVertically(-1)) {\n                    dragging = true\n                    return true\n                }',
        '                val dy = ev.y - downY\n                val dx = kotlin.math.abs(ev.x - downX)\n                if (dy > (8f * resources.displayMetrics.density) && dy > dx * 1.15f && child != null && !child.canScrollVertically(-1)) {\n                    dragging = true\n                    return true\n                }'
    )
    return s


def patch_manifest(s: str) -> str:
    return s.replace(
        'android:icon="@mipmap/ic_launcher"\n        android:roundIcon="@mipmap/ic_launcher"',
        'android:icon="@drawable/oracle_exact"\n        android:roundIcon="@drawable/oracle_exact"'
    )


patch('app/src/main/java/ro/alintudor/oracle/MainActivity.kt', patch_main)
patch('app/src/main/java/ro/alintudor/oracle/nativeui/OracleNativeModule.kt', patch_native)
patch('app/src/main/AndroidManifest.xml', patch_manifest)

raw = Path('app/src/main/assets/oracle_icon.b64').read_text(encoding='utf-8').strip()
Path('app/src/main/res/drawable').mkdir(parents=True, exist_ok=True)
Path('app/src/main/res/drawable/oracle_exact.webp').write_bytes(base64.b64decode(raw))
print('Prepared exact Oracle launcher icon:', Path('app/src/main/res/drawable/oracle_exact.webp').stat().st_size, 'bytes')
