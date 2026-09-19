# StockNHPlug implementation rules

## Permanent user requirements
- Kotlin Android application; Google Play publication is the intended destination.
- Preserve application → trading → execution layering. Market/research providers are separate from execution.
- Keys/secrets must remain encrypted on the phone; transmit only to the issuing official API for authentication. Never put user keys in source, CI, logs, URLs shown to users, or backend services.
- Data collection requires a reviewed official API/contract. Public visibility, robots.txt, and an accessible URL are not a license. No generic scraper or unofficial quote endpoint.
- Every implementation/policy change updates the relevant Markdown documents and `docs/CHANGELOG.md`. Update `docs/ARCHITECTURE.md` when classes or ownership change, `docs/DATA_SOURCES.md` when a provider changes, `docs/SECURITY.md` for security changes, and `docs/VERIFICATION.md` for actual test outcomes.
- Keep limitations truthful. Never describe an unexecuted device/broker test as passed, a mock order as real, an accepted order as filled, or a raw candle as adjusted.
- Never enable live trading until every release gate has recorded evidence. This is an implementation rule, not permission to place orders.
- Orders must be journaled before network dispatch; unknown outcomes stop trading and never automatically retry.
- Do not silently replace missing financial/news/history evidence with sample data or bypass policy gates.
- Do not collect full news articles, publish market datasets, or add remote code/WebViews/accessibility automation.
- Do not spawn agents unless explicitly requested by the user.

## Verification
Run `./gradlew testDebugUnitTest lintDebug assembleDebug` and `./gradlew bundleRelease` after meaningful code changes. Device checks require a provisioned emulator/device; document when unavailable. Run instrumented Keystore tests only on a disposable test install. Do not delete real user data for testing.
