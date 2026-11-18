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
- `ITEM_ALREADY_OWNED` runs the ownership reconcile on both paths (launch result and listener,
  new `Event.AlreadyOwned`). **Reworked after critic D1/D2:** it is not an entitlement by itself;
  see "Rework after critic" below. A failed query never revokes a known entitlement.

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
- ITEM_ALREADY_OWNED with a PURCHASED row unlocks and acknowledges exactly once (the initial
  null/empty success assertions were replaced by critic D1/D2; see the rework section)
- `Event.AlreadyOwned` from the store listener unlocks
- response codes map to connection / unavailable / generic / silent strings
- a failed connection reports the mapped BILLING_UNAVAILABLE message
- a launch CONNECTION failure says Play cannot be reached
- the offer token from the priced offer reaches the launch (and `token` is not repurposed)

Updated `ProBillingRaceTest` R4 (now `// fixbilling: critic D1/D2`): already-owned with a
null/empty re-query releases Buy without unlocking. No test was ignored or weakened for data
safety; all other assertions are unchanged.

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
- No local unlock is recorded from `ITEM_ALREADY_OWNED` alone any more (critic D1/D2); the
  3-day acknowledgement window can only start once a PURCHASED row is returned, which is the
  documented rule.
- No manifest, dependency, permission or network changes. No device test was run from this lane.

## Rework after critic (FIX FIRST, D1/D2)
Critic `CRITIC_fixbilling.md` found that `reconcileOwned` called `grantOwned()` whenever the
confirming query had no PURCHASED row, so `ITEM_ALREADY_OWNED` unlocked Pro with no purchase and
even on a PENDING-only answer. Lead ruling (D1/D2, Medium) applied:

- `reconcileOwned` no longer grants anything by itself and `Controller.grantOwned()` was removed.
  It filters the answer for `PRODUCT_ID`:
  - PURCHASED row -> `Controller.applyOwned(...)` unlocks and acknowledges exactly once (guarded
    by `acknowledged`);
  - PENDING only -> no unlock, no acknowledge, `State.Pending` (existing `pro_pending` copy,
    no new string needed), `reconcileDue` stays set;
  - query fails or returns nothing -> no unlock, `State.Failed(pro_error_connection)`, Buy
    released, `reconcileDue` stays set for the next foreground. Known paid entitlements are
    still never revoked.
- Applies to the launch path and to `Event.AlreadyOwned` from the store listener; outside a Buy
  `endBuy` is a no-op, so those paths stay silent while keeping the reconcile due.

Tests (rewritten red first, marked `// fixbilling: critic D1/D2`):
- `already owned with a failing query does not unlock and retries on the next foreground`
  (replaces the old success test) - not Pro, no acknowledge, connection copy, next foreground
  queries and unlocks on a PURCHASED row.
- `already owned with a pending purchase stays locked and unacknowledged` - `State.Pending`,
  not Pro, nothing acknowledged, reconcile still due.
- `already owned applies and acknowledges a purchased row exactly once` - includes a later
  reconcile with the acknowledged row and asserts no second acknowledge.
- `ProBillingRaceTest` R4: null/empty re-query releases Buy as `Failed` and never unlocks.

Verification:
- Red: `GATE FAIL fixbilling tests=40 failures=3` (run 44266226237).
- Billing classes: `GATE PASS fixbilling tests=40 failures=0 skipped=0 lint=[skipped]`
  (run 44266663756).
- Full gate (cloud): `GATE PASS fixbilling tests=1700 failures=0 skipped=0 lint=[skipped]`
  (run 44267148239).
- Commit `68a0dbf` fixbilling: ITEM_ALREADY_OWNED grants only on a PURCHASED row (critic D1/D2);
  this report update follows.

## Rework 2 after judge (MUST FIX 1-2)
Judge `JUDGE_fixbilling.md` verdict FAIL: acknowledgement was coordinated only inside one
snapshot, a failed acknowledge had no retry, and a debounced resume had no timer. Lead ruling
applied; overruled item 3 (empty successful query still revokes, `record(false)`) was **not**
changed and its test stays.

MUST FIX 1 - one acknowledgement per token across all entry points (`ProBilling.kt`):
- `AckLedger` (process-wide, main thread only) keeps per-token in-flight, completed and failure
  state. `acknowledgeThenUnlock` asks `AckLedger.begin(token)` before sending; a second caller
  (listener + reconcile + ALREADY_OWNED + restore) coalesces and sends nothing. A success is
  remembered, so a stale `isAcknowledged=false` snapshot does not re-acknowledge.
- A failure releases the token (`AckLedger.fail`) and schedules a bounded retry on the main
  `Handler`: 5 s, 30 s, 120 s (no busy loop), and the next reconcile also retries. A successful
  retry grants Pro quietly. Entitlement is never cleared while acking. A throwing acknowledge is
  treated as a failed attempt (token released, retried).
- Optional item done: `onPurchase` ignores any product other than `PRODUCT_ID`.

MUST FIX 2 - do not lose the only post-background check:
- When the 30 s debounce suppresses a foreground call, `refreshOwned` schedules exactly one
  deferred reconcile at the end of the window (`deferredReconcile`, replaced if a newer resume
  arrives, cancelled when a real reconcile starts). It runs on the handler, so no further
  resume is needed.
- `Event.Pending` (listener) and a pending purchase now set `reconcileDue`, so the next
  foreground is not debounced away.
- Optional items done: removed the stale "unlock regardless" comment; a failed/empty product
  query now uses the mapped connection error (`errorText(..., pro_error_product)`).

Tests added (red first, `ProBillingRecoveryTest`):
- `overlapping listener reconcile and already owned acknowledge the token once` - listener ack
  held in flight, then reconcile and ALREADY_OWNED for the same token: exactly 1 acknowledge,
  the in-flight result grants Pro, a later stale snapshot still sends none.
- `a failed acknowledge is retried without another foreground then stops` - 2 calls total
  (5 s timer), Pro after success, no further retries within 200 s.
- `a suppressed foreground schedules one deferred reconcile` - empty success at t=0, purchase
  completes, resume at t=2 s is debounced, 28 s later the deferred query finds it, unlocks and
  acknowledges once, with no further resume.
- `a pending listener update keeps the next foreground due`.

Verification:
- Red: `GATE FAIL fixbilling tests=44 failures=4` (run 44268951437) - 3 acknowledges instead
  of 1; no acknowledge retry; no deferred reconcile; pending update did not keep due.
- Billing classes: `GATE PASS fixbilling tests=44 failures=0 skipped=0` (local `-Tests` run).
- Full gate (cloud): `GATE PASS fixbilling tests=1704 failures=0 skipped=0 lint=[skipped]`
  (run 44270097325).
- Commit `abcb84f` fixbilling: shared per-token acknowledgement ledger with bounded retry,
  deferred reconcile (judge must-fix 1-2); this report update follows.
- Earlier summary numbers in this report are superseded by 1704 full-suite tests at this
  revision; all cloud runs are lane evidence, not independent verification.
