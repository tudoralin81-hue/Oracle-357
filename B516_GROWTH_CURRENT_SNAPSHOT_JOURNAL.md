# B516 — Growth current-snapshot repair journal

## Change
- Date: 2026-08-31/2026-09-01
- Target: `OracleLocalProcessor.kt` only.
- Analysis module was not modified.
- Growth UI/recommendation presentation was not modified.

## Confirmed cause
`OracleBootstrap.kt` seeds Growth with the canonical 28.08.2026 16:00 snapshot (SNPS/VEEV/CRM) and `OracleRepository` persists Growth locally. `OracleLocalProcessor.refresh()` previously preserved any non-empty persisted Growth snapshot even when its `referenceTimestamp` no longer matched the current trading-day 16:00 anchor. Therefore the old snapshot could remain indefinitely.

## Repair
When the persisted Growth snapshot anchor equals the current trading-day anchor, it remains frozen exactly as required. When the anchor differs, `OracleGrowthEngine.run()` is now invoked to generate the new snapshot. A generated non-empty result is normalized to the current anchor and persisted. If live generation returns no candidates, the last valid persisted snapshot is preserved rather than inventing or partially rewriting data.

## Files changed
1. `app/src/main/java/ro/alintudor/oracle/core/OracleLocalProcessor.kt`
   - Previous blob SHA: `5fd93ee0b947fb37486555fa74cf98e17ae2044b`
   - New blob SHA: `96d9edb6ffcb0c5f20ef0cddcefa33f7a1dcb319`
   - Commit: `19dfe9f46f7bacef19a4e7909d384d263f517d59`

## Files explicitly left unchanged
- `app/src/main/java/ro/alintudor/oracle/nativeui/OracleAnalysisModules.kt`
- `app/src/main/java/ro/alintudor/oracle/nativeui/OracleGrowthModule.kt`
- `app/src/main/java/ro/alintudor/oracle/core/OracleBootstrap.kt`

## Revert
Revert commit `19dfe9f46f7bacef19a4e7909d384d263f517d59` to return `OracleLocalProcessor.kt` to its previous state.

## Validation required
- Build the B514 APK.
- Open Growth after build.
- Confirm the displayed recommendation set is generated for the current 16:00 trading-day anchor, not the legacy 28.08.2026 16:00 seed.
- Confirm repeated opens/refreshes within the same anchor do not rerank the recommendations.
- Confirm Analysis remains byte-for-byte unchanged.
