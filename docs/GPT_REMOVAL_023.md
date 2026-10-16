# Phone 0.2.3 — remove the GPT integration

The user requested removal of ChatGPT integration and counseling, with no backup copies and preservation of personal profiles, workout records, measurement and calibration.

Removed OAuth/account activities and callback route, tokens transport, model/reasoning settings, counseling UI/history implementation, automatic AI workout review, AI candidate tuning UI/controllers, prompt assets, icon and Nimbus JWT dependency. Removed tests exclusive to these deleted features. Kept mixed local-metrics test cases as WorkoutLocalMetricsTest.

UserProfileStore is independent of external accounts. On upgrade it atomically saves device body/wearing defaults, all legacy body profiles and historical session ownership needed for old energy estimates before deleting ai-coach.json. A unique legacy profile can seed offline defaults while preserving last-side/last-placement; multiple profiles are retained without merging or selecting a different person's defaults. Invalid legacy facts remain intact instead of being overwritten with an empty profile. Tokens, generated reviews/calibration text, task preferences and cached interpretations are cleared; sensor originals, video, learned pattern candidates and validation reports remain intact. No manual backup files are created.

Existing numeric workout summaries/comparisons remain local, including same-weight repetition comparisons and same-repetition weight comparisons. Different set counts are explicitly qualified. Camera/sensor disagreement display and numeric pattern validation, manual application and rollback remain local. GPT-dependent automatic activation settings were removed; existing confirmed pattern selection is preserved. No replacement chatbot or speculative AI explanation was added.

Codex1 reviewed the removal read-only (session 01a14260-c472-78e3-9307-1550e0f20342). One concrete issue: promoting an account-only profile could hide existing wearing defaults. Fixed by preserving those defaults during migration; covered in UserProfileMigrationTest.

Verification: 12 targeted profile/migration/local-metrics tests passed before the final wearing fix. Final changed-class migration tests: 5 passed after the wearing-default fix. OTA assembly and publication results are recorded with the release. Phone screen tests, full-module test runs, lint and a general omission audit were not run. This does not prove installed-device migration/runtime behavior.

Only the phone changes (versionCode 42, versionName 0.2.3); the published watch 0.2.0 is reused unchanged.
