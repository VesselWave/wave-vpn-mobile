# Another app holds Always-on VPN: Eddie vs Wave — 2026-11-08

What a user sees when another app owns Android's Always-on VPN slot, or takes
the VPN away, and what Wave now shows instead of nothing.

## Setup

- Emulator `T3_Pixel_API_36` (Android 16, arm64). The planned API 37 emulator was
  busy running another session's instrumented tests, so it was not used.
- Eddie 4.1.0 (`org.airvpn.eddie`, versionCode 40), the official APK set.
- Wave debug builds of `origin/main` (baseline) and of this change.
- A disposable QA app, "Always-on test VPN" (`me.vwave.qa.holder`). It declares a
  `VpnService`, accepts Android's VPN consent, and never establishes a tunnel. It
  was set as Always-on VPN with **Block connections without VPN off**, so the
  network stayed usable. It was not committed and was uninstalled afterwards.
- Afterwards: Always-on was cleared (`always_on_vpn_app=null`, lockdown `0`), the
  QA app and placeholder files were removed, the Quick Settings tile was removed,
  Wave's VPN consent was restored, and the emulator's original Wave install was
  reinstalled.

## What Android does

When another app is Always-on, `VpnService.prepare()` returns a consent intent
for every other app. Android opens `com.android.vpndialogs/.ConfirmDialog` and
closes it straight away, with nothing shown on screen: logcat records the
`START`, and the caller gets `RESULT_CANCELED`. A user tapping **Cancel**
produces the same result code, so an app cannot tell the two apart. Apps also
cannot read which package holds Always-on.

`prepare()` also has a side effect. When the caller was approved before and no
other app is Always-on, it makes the caller the VPN app again, and Android stops
the VPN that was running. Wave therefore calls it only for a user action (tap
Connect, tap the tile), never on resume or in the background.

## Observed

| Scenario | Eddie 4.1.0 | Wave before | Wave after |
|---|---|---|---|
| Connect while another app is Always-on | Not reached on the device (see Gaps). From Eddie's 4.1.0 source: a modal dialog, "Cannot connect to the server because the system has canceled VPN connection. This usually happens when there is another app requesting VPN connection or has 'VPN Always On' permission granted." | Consent dialog flashes and closes; screen stays **Disconnected** with no message. | Persistent panel: **Android didn't let Wave connect**, naming Always-on as one cause and offering **Open VPN settings**. |
| Tap Connect again | — | Same silence. | Same panel; one more instant cancel; no added prompts. |
| Open VPN settings, turn off the other app's Always-on, return | — | — | Panel stays; **no automatic consent prompt** on return (0 `ConfirmDialog` starts). |
| User taps **Cancel** in the real consent dialog | Same modal dialog as above (source). | Silent. | Same panel. |
| Allow in the consent dialog | — | Connects. | Panel clears as Connect starts; connected to a real server, with handshake and traffic counters. |
| Another VPN app takes over while Wave is connected | Source: modal dialog `connection_revoked`. | From code, not run: `onRevoke` only disconnects, so the screen shows **Disconnected**. | Panel: **Android turned off Wave's VPN**, naming another VPN app or Android settings as the cause. |
| Quick Settings tile tapped without VPN consent | — | From code, not run: the tap is ignored. | Opens Wave on Overview and shows Android's consent dialog once; Cancel shows the panel. |

Also checked: the panel at 2.0× font scale in the dark theme. The page scrolls,
the icon stays beside the first title line, and the link fits on one line.

## Why a panel, not a dialog or snackbar

- It explains why Connect did nothing, beside the button, and stays until the
  user acts on it. Connect or Disconnect clears it. A snackbar would disappear
  before the user returns from Android settings.
- It doesn't block the screen the way Eddie's modal does, and it never prompts
  by itself.
- Its copy states both possible causes. It does not claim another app holds
  Always-on, because Android doesn't tell apps that.

## Gaps

- Eddie's runtime connect path was not reached. The AirVPN login from the local
  `.env` was rejected twice ("Wrong login/password"); further attempts were
  stopped to avoid account cooldowns. The VPN Profile file picker would not
  select a placeholder profile. Eddie's behavior above therefore comes from its
  pinned 4.1.0 source (`VPNManager.handleActivityResult`, `MainActivity`
  `CONNECTION_CANCELED` / `CONNECTION_REVOKED_BY_SYSTEM`), not from an observation.
- Only Android 16 was exercised. API 26–33 still use the same code paths; on
  API < 34 the tile opens the app through the deprecated
  `startActivityAndCollapse(Intent)`, which was not run.
- Always-on *with lockdown* held by another app was not exercised. Android's
  consent check gives the same result either way, but the network is blocked.
- Consent was reset with `appops set … ACTIVATE_VPN ignore` to show the real
  dialog. A rotation attempt during the tile flow didn't visibly rotate the
  emulator, so recreation isn't covered by evidence. The tile request is cleared
  from the intent so recreation cannot repeat it.
