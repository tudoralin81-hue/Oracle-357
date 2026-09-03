# Oracle B515 — Growth Stability Changelog

## Baseline
- Base branch: `build-519`
- Base commit: `36f8b388a22a339b79df54a831b4d2e9a4b8127d`
- Baseline Analysis source SHA: `76210ed4db93147487bc59e01ced70ae54f44ff6`
- Baseline is treated as frozen. `OracleAnalysisModules.kt` is not part of the functional patch.

## Problem reproduced from the reported behavior
- Growth could start a full `OracleLocalProcessor.refresh()` every time the Growth screen was opened.
- Two rapid/re-entrant opens could therefore read the same old snapshot concurrently, both run the Growth ranking engine, and the last writer could replace the visible recommendation set.
- The intended invariant is: within one 16:00 Europe/Bucharest trading-day snapshot, SHORT/MEDIUM/LONG recommendations, score, signal, risk, allocation, forecast and T0 remain identical.

## B515 changes
1. `MainActivity.kt` — Growth opening now renders the persisted snapshot immediately and only starts a refresh when the stored Growth snapshot is missing or belongs to a different trading-day anchor.
2. `MainActivity.kt` — added a local 16:00 Europe/Bucharest trading-day anchor check; weekends/holidays roll back to the last trading day.
3. `OracleLocalProcessor.kt` — `refresh()` is synchronized so concurrent refresh calls cannot both generate different Growth rankings from the same stale snapshot.
4. `OracleGrowthModule.kt` — only the Growth build footer is advanced to B515.
5. `app/build.gradle` — controlled application build identity advanced from versionCode 31 / B519 to versionCode 32 / B515.
6. `growth-b515-stability.yml` — isolated build workflow. It hard-checks the Analysis baseline SHA before and after the Growth patch.
7. The APK packaging step temporarily changes only the Analysis footer label from B513 to B514, matching the already-approved B514 APK display, then restores the source and verifies the original Analysis SHA before finishing the job. No Analysis logic/layout is changed.

## Revert point
- To revert B515 completely, return to base commit `36f8b388a22a339b79df54a831b4d2e9a4b8127d` / branch `build-519`.
- The B515 changes are isolated in commits after that base and are not merged into `main` by this iteration.

## Validation required on device
- Open Growth repeatedly without pressing Refresh: recommendation tickers and all snapshot values must remain identical.
- Scroll to the bottom, back to the top, and repeat: no recommendation changes.
- Leave Growth, return to Growth during the same 16:00 snapshot: no recommendation changes.
- Press Refresh during the same snapshot: no ranking change; only permitted live enrichment may change.
- Cross the next valid 16:00 trading-day anchor: a new snapshot may be generated.
- Open Analysis before and after these tests: it must remain visually/functionally identical to the approved B514 screen.

## 2026-08-31 — execution log
- Created isolated branch `growth-b515-stability` from the known B519/B514-good baseline.
- Added `scripts/fix_growth_b515.py`.
- Added `.github/workflows/growth-b515-stability.yml`.
- Added this changelog before APK validation.
- No direct edit was made to `OracleAnalysisModules.kt` in the repository branch.
