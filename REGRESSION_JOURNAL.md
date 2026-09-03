# Oracle — Regression Journal

## Rule 001 — Build success is not visual success
- Symptom: APK build succeeded, but the application opened on a skeleton/loading screen instead of the Oracle UI.
- Evidence: user screenshot from 2026-08-29 showing `ORACLE` plus grey loading bars.
- Root-cause direction: later changes replaced the previously working `OracleNativeModule` visual shell.
- Known-good baseline: commit `88b5df79dbba1ba298c50b568daba164688605bd`.
- Recovery: restore `OracleNativeModule.kt` from that baseline before making further visual changes.
- Prevention: every UI iteration must preserve a known-good commit and must not be called ready solely because GitHub Actions is green.

## Rule 002 — Backtrack before adding another fix
When a new version regresses a previously working screen:
1. Identify the last known-good commit from actual evidence.
2. Compare the regression range, file by file.
3. Restore the smallest responsible change set.
4. Build.
5. Only then make one controlled visual change at a time.

## Rule 003 — Portfolio data and actions must be functional, not decorative
- Portfolio must show the real stored tickers, share counts and market values; do not replace them with demo/static rows.
- Portfolio must support adding another local position without changing existing positions.
- `JURNAL ACTIVITATE` and portfolio export buttons must perform real local actions and must not be plain share/placeholder buttons.
- Export files must be written to the Android Downloads/Oracle folder on current Android versions.
- Prevention: test the portfolio interaction path after every portfolio change, not only compilation.

## Rule 004 — Excel export is the canonical journal/portfolio export model
- The supplied `AI-Stock-Oracle-Jurnal-Activitate` XLSX is the canonical export layout.
- Keep the exact 12 columns: `Data / Ora`, `Acțiune`, `Ticker`, `Acțiuni`, `Preț intrare`, `Preț vânzare`, `% la vânzare`, `Prognoză Oracle %`, `P/L realizat $`, `Rata de succes`, `ID poziție`, `Status`.
- Exported portfolio actions/positions are sourced from the persisted Oracle journal and current positions; never replace them with demo/static tickers.
- PDF must use the same columns, row order and footer semantics as the Excel model.
- The Excel export must be a real `.xlsx` file, not a renamed CSV.
- The total portfolio return must be displayed prominently at the top of both XLSX and PDF exports.
- The export must include `Status` with `ACTIVE` for current positions and `VÂNDUT` for closed positions.

## Rule 005 — Portfolio recommendations must not be recomputed from incomplete history
- Symptom: CRM, HOOD and MELI showed `RSI 0.0`, `Momentum 0.0%` and false `SELL` signals in Portfolio while the working Oracle analysis showed `HOLD`.
- Root cause: Portfolio recomputed technical indicators from a one-point local history and converted missing history into synthetic zeros.
- Required behavior: use the canonical Oracle analysis snapshot for seeded positions; preserve an existing valid action during local refresh; only compute a fallback action for genuinely new positions.
- Never turn missing technical data into a SELL signal.
- Regression check: seeded portfolio must remain `CRM HOLD 82/100`, `HOOD HOLD 95/100`, `MELI HOLD 95/100`, with the canonical technical snapshots loaded into Portfolio.

## Rule 006 — Missing support/resistance must never render as N/A when current price is available
- Symptom: CRM showed `Suport 20D N/A` and `Rezistență 20D N/A` even though the current price was available.
- Required behavior: if support/resistance is missing, zero, NaN or infinite, fall back to the position's current price.
- Prevention: Portfolio must never display `N/A` for these two fields when a valid current price exists.

## Rule 007 — Shared module shell must respect Android safe areas
- Symptom: module content could overlap the Android status/notification area at the top and the Android navigation area at the bottom.
- Required behavior: the shared `OracleNativeModule` shell applies the Android status-bar inset above the header and the navigation-bar inset below the module content.
- The module header is deliberately taller so `ORACLE` and the module title (including `PORTFOLIO`) remain fully visible above the divider.
- The scrollable module content has additional bottom space so the final controls/cards can be moved above Android navigation controls.
- This is a shared-shell fix and therefore applies to Portfolio, Alerts, News, Growth, Knowledge, Analysis, Watchlist and Journal.
- Do not reimplement per-module safe-area offsets unless a future device-specific regression proves it necessary.

## Rule 008 — Growth must mirror the supplied Web Oracle structure without client-side formula invention
- Growth keeps the Web visual hierarchy: snapshot/anchor, parameters, SHORT/MEDIUM/LONG sections, recommendation header, score/signal/risk/allocation, forecast, momentum, 12-weight grid, news/catalyst and Growth history.
- The daily anchor is fixed at `16:00 Europe/Bucharest`; refresh must not move T0.
- Android displays cached Oracle Growth snapshots and does not invent forecast, score, risk or horizon weights.
- The 12-parameter grid uses the profile supplied by Oracle; missing ADX is shown as `—`, never as a fabricated score.
- Growth must remain responsive on phone/tablet and inherit the shared Android safe-area shell.
- Growth history is append-only: forecast changes must not overwrite the original T0 snapshot.

## Rule 009 — Growth UI has one hero and no methodology shortcut card
- The shared `OracleNativeModule` `GrowthBanner` is the single Growth hero.
- `OracleGrowthModule` must not add a second Growth hero/card below the module header.
- The `VEZI METODOLOGIA ȘI PONDERILE` shortcut card is intentionally removed from the Growth screen; the compact 12-weight grid remains inside each recommendation card.
- Recommendation tickers must remain prominent and visible on phone/tablet.
- VEEV ADX is displayed as `—` when the Oracle snapshot contains no ADX value; this is a missing-data state, not a score of zero.

## Rule 010 — LONG Growth profile must total 100
- The corrected V5 LONG profile changes Momentum from `7` to `6`.
- Corrected LONG weights: `6/6/20/6/5/8/18/4/9/7/9/2` for `News/BO/Trend/Mom/Vol/S/R/Fund/BB/Ichimoku/Mkt/R/R/ADX`.
- The migration only changes the known legacy 101-point seed (`sum == 101` and `Momentum == 7`); other persisted Growth snapshots are preserved.

## Rule 011 — Growth live market enrichment must come from market data, not WordPress
- Growth refresh may independently derive only fields supported by live OHLCV: current price, 5D momentum, 20D momentum, actual return from the frozen reference price and ADX(14).
- These live fields are refreshed from the direct market-data adapter (`OracleMarketData`) and persisted locally.
- Score, forecast, risk, horizon weights and news/catalyst remain authoritative Oracle snapshot fields until the real Oracle calculation/data source is integrated.
- Never replace missing live data with zero or a fabricated value.
- This separation prevents the Android client from silently inventing Oracle formulas while removing WordPress as a live market-data dependency.

## Rule 012 — Shared header button semantics are fixed
- On every module screen, the **top-left button is Back** and the **top-right button is Refresh**.
- Back returns to the Oracle Start screen; it must never trigger refresh.
- Refresh performs the module refresh action; it must never navigate back.
- The **Start screen has no top-right Refresh/Back button at all**.
- This behavior is implemented centrally in `OracleNativeModule` and must not be inverted or reimplemented differently by individual modules.
- Regression check: open each module, verify left=`Back`, right=`Refresh`; return to Start and verify neither header button is present.

## Rule 013 — Growth recommendations are frozen by the 16:00 trading-day snapshot
- Symptom: Growth recommendations changed during the same snapshot window; e.g. the visible set changed from `ADSK/SNPS/CRM` to `VEEV/SNPS/CRM` without reaching the next 16:00 anchor.
- Root cause: `OracleLocalProcessor.refresh()` called `OracleGrowthEngine.run()` on every module open and every Refresh, recomputing the ranking from live market data even when the persisted Growth snapshot was still current.
- Required behavior: compute a new Growth recommendation set only when the persisted snapshot anchor differs from the current trading-day anchor.
- The anchor is exactly `16:00 Europe/Bucharest`. Before 16:00, use the previous trading day's 16:00 snapshot. Saturday and Sunday use Friday's 16:00 snapshot; Monday before 16:00 also continues to use Friday's snapshot.
- Opening Growth must never silently replace the current recommendation set. The right-top Refresh button may refresh live enrichment, but it must not recompute the frozen recommendation set inside the same snapshot window.
- When a new snapshot is generated, all recommendation rows receive the new anchor as `referenceTimestamp`; T0 is never inherited from an older ticker or moved by refresh.
- Regression check: open Growth repeatedly before the next 16:00 and verify identical recommendation tickers, score, signal, risk, allocation, forecast and snapshot timestamp. Repeat across Saturday/Sunday; the Friday snapshot must remain unchanged until Monday 16:00.

## Rule 014 — Canonical weekend seed must use a trading-day anchor
- Symptom: the app still reranked Growth on Saturday 29.08.2026 even though Rule 013 correctly mapped the weekend to Friday's 16:00 anchor.
- Root cause: `OracleBootstrap` seeded the approved weekend snapshot with `29.08.2026 16:00`, which is a Saturday timestamp. `OracleLocalProcessor` correctly computed Friday `28.08.2026 16:00` as the active anchor, saw a mismatch, and legitimately reran the live Growth engine.
- Required behavior: the canonical snapshot for the 29.08.2026 weekend view must be stamped `28.08.2026 16:00 Europe/Bucharest` (`1787922000000L`).
- Migration version is incremented so an already-installed app repairs the bad Saturday anchor exactly once.
- Regression check: after migration, repeatedly open/refresh Growth on Saturday/Sunday and verify that SNPS/VEEV/CRM, their score/forecast/risk/allocation and the `28.08.2026 16:00` anchor remain unchanged.

## Rule 015 — Analysis must show raw values, not Oracle scores
- News is hidden from the Analysis parameter list; it remains an internal Growth factor only.
- Analysis must display the real/raw value for every visible parameter.
- ADX must display `ADX(14) <value>` only; never append an Oracle score.
- Risk / Reward must display `ATR <value>` only; never append an Oracle R/R score.
- Fundamentals must display real company fundamentals and must never display a Fundamentals/Oracle score.
- Missing source data remains missing; do not replace it with a fabricated Oracle score or zero.

## Rule 016 — Fundamentals market cap must have a real share-count fallback
- Symptom: Build 462 displayed real fundamentals for APLD but `Market cap=—` even though current price and company share count were available.
- Root cause: the Yahoo quote/quoteSummary response did not always expose `marketCap` directly, while `sharesOutstanding` and current price were available.
- Required behavior: when `marketCap` is absent, calculate it from `sharesOutstanding × current price` using the same live source. Do not substitute an Oracle score or hard-coded ticker value.
- Trailing/forward P/E may legitimately remain `—` when the source reports them unavailable; for APLD Yahoo currently reports both as unavailable because the company is not profitable.
- Regression check: APLD at `$25.34` must show a real market cap around `$7.39B`, not `—`; sector must remain the resolved `Information Technology` classification.

## Rule 017 — Analysis build source must be the current main source
- Evidence: `.github/workflows/b522-growth-only-from-b514-good.yml` checked out fixed commit `e1195536704f375f6a44f1ae5985bc464671ec0b` before building, then explicitly applied `scripts/b511_patch.py` to `OracleAnalysisModules.kt`.
- Confirmed fact: `OracleAnalysisModules.kt` at `main` and at `e1195536704f375f6a44f1ae5985bc464671ec0b` currently have the same blob SHA `76210ed4db93147487bc59e01ced70ae54f44ff6`.
- Consequence: the APK build path was not using `main` as the authoritative Analysis source; it was reconstructing Analysis from a frozen historical base.
- Required behavior: build from `main`; Growth-only processing must leave `OracleAnalysisModules.kt` byte-for-byte unchanged.
- Prevention: the approved build workflow must not apply a historical Analysis patch or checkout a historical Analysis baseline.
- Revert point: the pre-change `main` commit immediately before this audit/fix.

## Rule 018 — APK verification must validate stable package metadata, not optimized DEX strings
- Evidence: the B514 build from commit `48205cf177777e4d1ce17076a8f3c75024f66bcd` compiled successfully and APK signature verification succeeded, but the workflow failed because it searched optimized `classes.dex` for `V6g-FINAL-B514` and `BUILD B514`.
- Confirmed fact: the failure occurred in the verification step after `:app:assembleRelease` succeeded; the APK was signed with the Android Debug certificate because production signing secrets were empty for that run.
- Required behavior: verify the APK signature and read `versionName`/`versionCode` from the APK manifest using `aapt dump badging`. Do not require arbitrary build-marker strings to survive DEX optimization.
- Change: B514 workflow verification now checks `versionName='V6g-FINAL-B514'` and `versionCode='35'` from APK badging and then uploads the APK.
- Revert point: commit `7362d4cef0fb2bed1e8a33003542f2513d62f42e` is the workflow fix; the immediately preceding commit `48205cf177777e4d1ce17076a8f3c75024f66bcd` remains the prior build state.
