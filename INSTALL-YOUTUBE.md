# YouTube (Car): install and test guide

The app shows up on the phone as **CarTube** (this guide calls it CarBridge, the project it's built on).

A personal build of CarBridge for a Nothing Phone (3a) Pro (Android 16, Nothing OS 4.1) and a 2017
Škoda Kodiaq with wired Android Auto. It makes a separate **YouTube (Car)** app from the YouTube
you installed from Google Play. Your normal YouTube app is not changed.

**Safety:** the copy covers the car screen and pauses video unless the phone's GPS shows the car
has been still for 5 seconds. Don't rely on it as your only safeguard, and never watch while
driving. Do the "moving" test with a passenger watching the screen, or not at all.

## What you need

- The phone, with **YouTube installed from the Play Store** (split APKs are fine)
- **Android Auto** (on Android 16 it's part of the system; update it in the Play Store)
- The debug APK `patcher-debug.apk`, from this branch's latest CI run: GitHub → Actions → CI →
  the latest run on the branch → **Artifacts** → `carbridge-debug-apk` (a zip holding the APK)
- A USB cable for the car, and a computer only if you prefer installing with `adb`

## 1. Install CarBridge (debug build)

The debug build installs as `org.carbridge.debug` with the label **CarBridge**. It needs no root.

**On the phone:** unzip the artifact, open `patcher-debug.apk` with Files, and allow your file
manager to install unknown apps when asked. Nothing OS may show a Play Protect prompt for an
unknown developer: choose **More details → Install anyway**.

**Or with adb:** turn on USB debugging (Settings → About phone → tap *Build number* 7 times, then
System → Developer options → USB debugging), then:

```bash
adb install -r patcher-debug.apk
```

CarBridge asks for these permissions and nothing else: see installed apps, install and uninstall
apps (only the copies you ask for, always with Android's own confirmation), and notifications
(optional, for "YouTube was updated" notices). It has no internet permission and no tracking.

## 2. One-time setup (the yellow card in CarBridge)

1. **Let CarBridge install apps**: tap *Allow* and switch it on.
2. **Android Auto developer mode**: tap *Open Android Auto*. In Android Auto settings, scroll to
   *Version* and tap it about 10 times until it offers developer mode; confirm. Back in CarBridge,
   tap *It's on*.
3. **Unknown sources**: in Android Auto settings, open the ⋮ menu → *Developer settings* →
   turn on **Unknown sources**. Back in CarBridge, tap *It's on*.

On Android 16, Android Auto settings are in Settings → Connected devices → Connection
preferences → Android Auto if the button doesn't open them.

## 3. Create YouTube (Car)

1. In the **YouTube for the car** card, tap **Create YouTube (Car)**. Read what to expect and tap
   **Create car version**.
2. Watch the progress bar. It shows ten named steps (check, read, patch, lock, write, sign, verify,
   install, confirm, inspect). YouTube is large: reading and writing can take a minute or two.
3. Confirm Android's install prompt.
4. If anything fails, the snackbar's **Details** names the failed step, says what it usually means,
   and shows the full log. Long-press to select and copy it.

The copy installs as `com.google.android.youtube.car`, labelled **YouTube (Car)** with a green
bridge badge. Your YouTube (`com.google.android.youtube`) stays as it was.

## 4. Allow location for the copy

The parked-only lock reads speed from GPS, so the copy needs precise location. Without it the car
screen stays locked ("Available when parked").

CarBridge opens **Checks** after the install if location is missing. Tap **Allow location** →
*Permissions* → *Location* → **Allow only while using the app**, and keep **Use precise location**
on. Also keep phone location turned on.

## 5. Test each part separately

A successful patch does not prove YouTube works. Open **Checks** (tap the copy, or ⋮ → Checks) and
go through each section. Run them again after every update.

| Part | How it's checked | Pass looks like |
|---|---|---|
| **Signing** | Automatic: apksig verified every APK before install, and the installed copy's certificate is this phone's CarBridge key | Two green ticks |
| **Installation** | Automatic: package installed, version matches YouTube, all split APKs present, original kept | Green ticks; "Base + N split APK(s)" |
| **Android Auto visibility** | Automatic: car meta-data and a `CAR_LAUNCHER` entry in the installed copy, Android Auto installed, unknown sources on. Then **manual**: tick *Shows in the car's app launcher* once you see it in the Kodiaq | Green ticks, then your tick |
| **Parked-only lock** | Automatic: lock present and location granted | Green ticks |
| **In the car** | Manual: *Opens on the car screen*, *A video plays while parked*, *Video is blocked when moving* | Your ticks |

In the car:

1. Park, apply the parking brake and plug the phone in. If Android Auto was already connected when
   you installed the copy, unplug and replug: Android Auto only rereads apps on connect.
2. Open the app launcher on the Kodiaq's screen and look for **YouTube (Car)**. If it's missing,
   check unknown sources (step 2.3), then replug.
3. Open it. You'll first see "Checking that you're parked…" until the phone has a GPS fix; in a
   garage this can take a while or not happen at all. Once it has been still for 5 seconds the
   lock lifts.
4. Play a video. Expect to be **signed out**: Google sign-in normally fails in re-signed copies.
5. Passenger test only: when the car moves off, the car screen should switch to "Available when
   parked" and playback pause. When you stop again it unlocks after 5 seconds; tap play to resume.

## What may not work, and why

- **YouTube refusing to run**: Google can check its own signature or Play Integrity. If the copy
  closes as soon as it opens (Checks pass, "Opens on the car screen" fails), that's the cause, and
  CarBridge can't fix it from outside the app.
- **Sign-in**: almost certainly unavailable in the copy. Signed-out playback is the baseline.
- **Android Auto changes**: Android Auto updates can change what it shows from sideloaded apps.
- **GPS indoors**: no fix means locked, by design.
- **The signing key** lives in CarBridge's Keystore on this phone. If you uninstall CarBridge or
  clear its data, uninstall YouTube (Car) and create it again; an update would fail with a
  "signed with a different key" conflict, which CarBridge explains.

## Updating and removing

- When Play updates YouTube, CarBridge notifies you (if allowed) and the card shows **Update**.
  Updating keeps the copy's data. Run Checks again afterwards, including the in-car items.
- **Uninstall** on the card (or ⋮ → Uninstall copy) removes only YouTube (Car).

## Building it yourself

CI builds and tests every push (`.github/workflows/ci.yml`). Locally, with an Android SDK:

```bash
./gradlew :core:test :patcher:testDebugUnitTest :patcher:assembleDebug
# output: patcher/build/outputs/apk/debug/patcher-debug.apk
```

## Licence

GPL-3.0, as the original CarBridge. See [LICENSE](LICENSE) and the "Changes in this fork" section of
the [README](README.md). This build and any copies it makes are for your own use: don't share
patched YouTube APKs.
