# Changelog

## 1.5.0 — 2026-10-22

- Add home layout choices: compact cards, pinned ordering, amount privacy and selective balance totals.
- Fetch searchable model catalogs with each selected source's API key; add favorites, saved probe suites and an in-round comparison table. Paid streaming probes still require a manual tap.
- Add independent low-balance and daily-spend rules with overnight quiet hours and cooldowns.
- Add cross-instance date reports, source coverage and comparison, partial-data labels and CSV export with spreadsheet-formula protection.
- Configure each home widget independently for one source or all sources, amount privacy and budget progress; refresh through bounded background work.
- Encrypt local Access Tokens and API Keys with Android Keystore AES-GCM. Migrate legacy plaintext atomically and retain encrypted records if a key becomes unreadable.
- Carry the new home and alert settings through credential-free or password-encrypted backup files.

Validation: 47 Android test suites / 291 tests passed with zero failures, errors or skipped tests. Robolectric Compose navigation checks cover the home options and cross-instance report; local API fixtures cover model-catalog authentication and cancellation. `testDebugUnitTest` and `assembleDebug` finished with `BUILD SUCCESSFUL`.

## 1.4.1 — 2026-10-19

- Make the home overview show fresh multi-instance balances and spending directly; remove the duplicate history-derived summary and monitoring-tools launcher.
- Add compact instance cards with independent freshness / failure states and move budget editing into a validated bottom sheet.
- Give reports, streaming tests and notifications separate pages with contextual instance selection, back navigation and no bottom navigation overlay.
- Abbreviate large USD balances and report totals through trillions (T); tap an amount for full precision. Keep the currency label separate.
- Simplify date report controls and test explanations; reduce glass navigation padding and strengthen its background for legibility.

- Load retained snapshots before the initial refresh so an offline launch preserves stale balances. Budget alert notifications open the notification page.

Validation: 44 Android test suites / 280 tests passed, with zero failures, errors or skipped tests. Native Compose checks cover overview and instance navigation, report drilldown, offline streaming metrics, notification history, full-precision amount disclosure, stale-total exclusion and validated budget editing. testDebugUnitTest and assembleDebug finished with BUILD SUCCESSFUL.

## 1.4.0 — 2026-10-18

- Retire PWA source and entry points; maintain the native Android application only.
- Add monitoring tools from Dashboard and Settings: bounded concurrent instance refresh, fresh-data totals and independent monthly budgets.
- Add inclusive local-date reports for historical months / custom ranges, comparison against the preceding equal-length period, and model / token cost drilldown with paginated logs.
- Add explicitly triggered cross-instance streaming tests: first output latency, completion tokens, output throughput, cancellation and per-instance local history. No generated content is retained.
- Add a local 200-record alert history, unread state and clear controls; include existing background alerts and individual-budget notifications.
- Export credential-free backups by default; require password encryption for secret-bearing exports. Authenticate and version the envelope, preserve existing credentials during credential-free imports, and include instance budgets.
- Clear the entire shared DataStore on reset, including appearance, layout and probe history. Remove per-instance related data on deletion.
- Show stale / partial / unavailable data and avoid silently treating failed statistics as zero.
- Initialize local Git metadata, add ignore rules, split backup UI out of SettingsScreen, document and script Android verification.

Validation: 44 Android test suites / 278 tests passed, with zero failures, errors or skipped tests. Native Compose demo-flow screenshots cover overview, report drilldown, streaming metrics and alert history. testDebugUnitTest and assembleDebug both finished with BUILD SUCCESSFUL. API integration checks use local MockWebServer fixtures; no real credentials or paid upstream requests were used during verification.
