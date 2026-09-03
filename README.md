# Oracle

Oracle Android stock-analysis project.

## 2026-08-31 sector fix

Analysis now resolves the sector from the live Yahoo `assetProfile` when available and falls back to a canonical local ticker map when the remote profile is unavailable. The Analysis UI displays the sector resolved by `OracleAnalysisEngine` instead of a small UI-only hard-coded list.

Example: APLD resolves to **Information Technology** instead of `Sector indisponibil`.

The complete patched project archive is supplied separately as `Oracle-main-9-SECTOR-FIX-GITHUB.zip`.
