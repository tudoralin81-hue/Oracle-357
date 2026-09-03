# Oracle News Module V1

## Scope

Native Android presentation of normalized Oracle news. The UI does not invent, fetch, score, or deduplicate news.

## Display

- newest items first, with BREAKING items prioritized;
- ticker and market fallback;
- publisher/source;
- publication time rendered in Europe/Bucharest;
- relevance score when supplied;
- sentiment score when supplied;
- source type;
- article URL opens externally;
- maximum 100 displayed items to keep the native screen responsive.

## Data contract

`OracleNews` carries ticker, title, source, URL, publishedAt, breaking, publisher, sourceType, receivedAt, timezone, relevanceScore, sentimentScore, rawId and engineVersion.

## Architecture rule

Oracle remains the single intelligence layer. External providers/feed connectors belong upstream of Android. Android consumes normalized records from `OracleRepository`.

## Acceptance

Do not merge to `main` until the project compiles successfully and the News module is verified on the current stable Oracle baseline.

## Analysis chart update

Analysis technical chart now supports 1D, 1H, 30M, 5D, 1M, 3M and 1Y ranges, with enlarged chart labels and an explanation block for the trend/support/resistance lines.
