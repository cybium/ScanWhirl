# LANE REPORT - fixbilling

## Task
One coherent lane from `JUDGE_RESEARCH.md` §3 items 1-5 and §4 item 2: make the ownership
check retryable and foreground-driven, reconnect automatically, pass Play's offer token from the
same offer whose price is shown, and give actionable EN/HI error copy for the billing response
codes. File: `app/src/main/java/com/scanwhirl/app/util/ProBilling.kt` (billing-ktx 8.3.0, one
non-consumable INAPP product, app stays offline: no INTERNET permission and no network code added).

## What was wrong
1. `refreshOwned` set `checkedOnStart = true` **before** connecting or querying, so one failed
   start disabled every later onResume check in that process. A PENDING purchase completed while
   disconnected could stay unacknowledged (Play refunds after 3 days).
2. No `enableAutoServiceReconnection()`: a transient `SERVICE_DISCONNECTED` was only retried by a
   later manual tap.
3. `launchBillingFlow` set only `setProductDetails(...)`; the selected one-time offer token was
   dropped, and `Offer.token` was the query identity (tests rely on that meaning).
4. Every non-OK code except `USER_CANCELED` became the generic "purchase could not be completed",
   and `ITEM_ALREADY_OWNED` became a purchase failure when the confirming query lagged.

## Changes
`util/ProBilling.kt`
- `checkedOnStart` replaced by `reconcileDue` / `reconcileInFlight` / `lastReconcileAt`. A failed
  connection or failed/null query keeps `reconcileDue = true`; only a successful
  `queryPurchasesAsync` clears it. `ShellActivity.onResume` already calls `refreshOwned`, so no
  activity edit was needed: **foreground hook = `ShellActivity.onResume` (existing call)**.
- Debounce: a successful reconcile is not repeated within `RECONCILE_WINDOW_MS` (30 s); failed
  attempts and PENDING purchases (whose payment may complete while disconnected) retry on the
  next foreground immediately. Queries never overlap and are silent (listener untouched, R2).
- `enableAutoServiceReconnection()` on the Billing 8 builder; `onBillingServiceDisconnected`
  stays a no-op as the current docs recommend for this mode. No sleep/retry loop.
- `Offer` gained a separate `offerToken` field (its `token` stays the query identity used by
  R7). `PlayBilling.queryOffer` takes price **and** `offerToken` from the same
  `oneTimePurchaseOfferDetailsList[0]`; `launchBillingFlow` calls `setOfferToken(...)` with it.
- Error mapping `failureText(context, code)`: SERVICE_UNAVAILABLE / SERVICE_DISCONNECTED /
  NETWORK_ERROR -> connection copy, BILLING_UNAVAILABLE -> not available copy, USER_CANCELED ->
  silent (`null`), everything else -> existing generic copy. Used by the purchase listener, setup
  failure, restore/query failure and launch (new `LaunchResult.CONNECTION` / `UNAVAILABLE`).
- `ITEM_ALREADY_OWNED` is now success on both paths (launch result and listener, new
  `Event.AlreadyOwned`): it runs the reconcile, unlocks (`Controller.grantOwned`) even if the
  confirming query fails/answers empty, and leaves the reconcile due so the purchase is
  acknowledged once Play returns the row. A failed query never revokes a known entitlement.

Resources (new files)
- `res/values/strings_fixbilling.xml`: `pro_error_connection` ("Can't reach Google Play. Check the
  Play Store app and try again."), `pro_error_unavailable` ("Purchases aren't available on this
  device or account."). ASCII apostrophes, no long dashes.
- `res/values-hi/strings_fixbilling.xml`: Hindi values, each starting with `<!-- review -->`.

## Tests (written first, red on the old code)
New `app/src/test/java/com/scanwhirl/app/util/ProBillingRecoveryTest.kt` (11 tests, fake answers
through a queue like Play):
- failed start-up query -> next foreground queries again and unlocks (old: expected 2, was 1)
- failed query / failed connection never revoke an unlocked entitlement
- PENDING -> PURCHASED is acknowledged exactly once on reconcile, never twice
- successful reconcile is debounced, then queried again after the window
- ITEM_ALREADY_OWNED with a null or empty confirming query unlocks (old: Failed) and with a
  purchase row acknowledges it once
- `Event.AlreadyOwned` from the store listener unlocks
- response codes map to connection / unavailable / generic / silent strings
- a failed connection reports the mapped BILLING_UNAVAILABLE message
- a launch CONNECTION failure says Play cannot be reached
- the offer token from the priced offer reaches the launch (and `token` is not repurposed)

Updated `ProBillingRaceTest` R4 to the judge's semantics: already-owned with a null/empty
re-query releases Buy as a **success** (`Owned` + entitlement) instead of `Failed`. No test was
ignored or weakened for data safety; all other assertions are unchanged.

## Verification
- Red run (behaviour tests): `GATE FAIL fixbilling tests=6 failures=3` (run 44236391693).
- Billing classes: `GATE PASS fixbilling tests=39 failures=0 skipped=0 lint=[skipped]`
  (run 44237864797, `-Tests "com.scanwhirl.app.util.ProBilling*"`).
- Full gate (cloud): `GATE PASS fixbilling tests=1699 failures=0 skipped=0 lint=[skipped]`
  (run 44238708712). One earlier full run failed only on a Maven Central 429 download, retried.

## Commits
- `0aff3d1` fixbilling: reconcile on foreground, retry failed ownership checks, offer token and
  error copy
- (this report commit follows)

## Notes for the lead / device check
- Docs used: Context7 `/websites/developer_android_google_play_billing` (integrate, errors,
  one-time-product multi-offer API), fetched during this lane: auto reconnection is 8.0+ and
  strongly recommended; one-time offer launches require `setOfferToken` from
  `getOneTimePurchaseOfferDetailsList()`; query in `onResume` is the documented way to catch
  pending -> purchased transitions.
- `ProGate.setPro` still records a local unlock after `ITEM_ALREADY_OWNED` even if the query was
  unreachable; the next successful reconcile acknowledges the purchase, so the 3-day window is
  protected but a long offline period is still a risk Play cannot confirm until reconnection.
- No manifest, dependency, permission or network changes. No device test was run from this lane.
