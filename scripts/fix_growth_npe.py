from pathlib import Path

# B535 runtime fix: ConcurrentHashMap does not accept null values.
# companyName() may legitimately fail to resolve a remote name, so a nullable
# value must never be inserted into the cache. This was the direct source of
# the Growth NullPointerException seen on-device.
p = Path('app/src/main/java/ro/alintudor/oracle/core/OracleRealData.kt')
s = p.read_text()
s = s.replace('ConcurrentHashMap<String, String?>()', 'ConcurrentHashMap<String, String>()')
s = s.replace('companyNameCache[symbol]=remote; return remote', 'remote?.let { companyNameCache[symbol]=it }; return remote')
p.write_text(s)
print('Growth NPE fix applied: nullable company-name cache writes are guarded')
