# StockNHPlug implementation rules

## Permanent user requirements
- Kotlin Android application; Google Play publication is the intended destination.
- Preserve application → trading → execution layering. Market/research providers are separate from execution.
- API, market/research providers and strategy algorithms must be replaceable through explicit ports. Assemble concrete implementations only at AppContainer; strategy replacement must not bypass common risk/journal gates. Run scripts/check_architecture.py and maintain docs/EXTENDING.md when contracts change.
- Keys/secrets must remain encrypted on the phone; transmit only to the issuing official API for authentication. Never put user keys in source, CI, logs, URLs shown to users, or backend services.
- Data collection requires a reviewed official API/contract. Public visibility, robots.txt, and an accessible URL are not a license. No generic scraper or unofficial quote endpoint.
- Every implementation/policy change updates the relevant Markdown documents and `docs/CHANGELOG.md`. Update `docs/ARCHITECTURE.md` when classes or ownership change, `docs/DATA_SOURCES.md` when a provider changes, `docs/SECURITY.md` for security changes, and `docs/VERIFICATION.md` for actual test outcomes.
- Keep limitations truthful. Never describe an unexecuted device/broker test as passed, a mock order as real, an accepted order as filled, or a raw candle as adjusted.
- Never enable live trading until every release gate has recorded evidence. This is an implementation rule, not permission to place orders.
- Orders must be journaled before network dispatch; unknown outcomes stop trading and never automatically retry.
- Default automation algorithms are averaging-down and rebalancing. Keep strategy/group IDs distinct; the same symbol may belong to multiple groups. Group quantity/cost must come only from reconciled cumulative fills, never accepted orders or guessed broker order-number mappings. Preserve strategy/group schedule overrides and mandatory research gates for every buy path.
- Parking is a separate account cash policy with reserved ownership IDs. Sell only reconciled app-owned parking quantities; wait for terminal fills and fresh broker cash before re-evaluating a stock purchase. Keep minimum cash, turnover/cooldown limits, common risk gates and research requirements; never treat expected parking-sale proceeds as available cash.
- Do not silently replace missing financial/news/history evidence with sample data or bypass policy gates.
- Do not collect full news articles, publish market datasets, or add remote code/WebViews/accessibility automation.
- Do not spawn agents unless explicitly requested by the user.

## Verification
Run `./gradlew testDebugUnitTest lintDebug assembleDebug` and `./gradlew bundleRelease` after meaningful code changes. Device checks require a provisioned emulator/device; document when unavailable. Run instrumented Keystore tests only on a disposable test install. Do not delete real user data for testing.
