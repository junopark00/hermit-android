# Hermit for Android: feature guide

This guide describes Hermit's features in detail. For installation, pairing and building, see the
[README](../README.md).

Hermit speaks the standard GameStream protocol, so it works with [Shell](https://github.com/junopark00/hermit-shell)
and with other GameStream-compatible hosts such as Sunshine and Apollo. A few features use host
extensions that only Shell provides; they are marked **(Shell)** below.

## Contents

- [Connecting and pairing](#connecting-and-pairing)
- [Touch input](#touch-input)
- [Pinch zoom](#pinch-zoom)
- [Text input bar](#text-input-bar)
- [Virtual keypad](#virtual-keypad)
- [Stream settings panel](#stream-settings-panel)
- [Automatic bitrate (Shell)](#automatic-bitrate-shell)
- [Bitrate input and recommended bitrate](#bitrate-input-and-recommended-bitrate)
- [Performance overlay](#performance-overlay)
- [Session summary and history](#session-summary-and-history)
- [Clipboard sync](#clipboard-sync)
- [Automatic reconnect](#automatic-reconnect)
- [Shutting down or restarting the PC (Shell)](#shutting-down-or-restarting-the-pc-shell)
- [Notices](#notices)
- [Crash reports](#crash-reports)
- [Appearance and languages](#appearance-and-languages)

## Connecting and pairing

- PCs on the local network are found automatically (mDNS). A PC on another network can be added
  with **+** on the PC list (IP address or host name).
- Tap a PC to pair. The pairing dialog shows the PIN in large digits; enter it on the host's pairing
  page (in Shell: web UI → Pairing). **Cancel** (or Back) withdraws the pending pairing request, and
  the dialog closes by itself once pairing succeeds.
- Hermit has its own application ID, so it can be installed next to other GameStream clients. Its
  pairing and settings are stored separately.

## Touch input

### Direct touch (default)

Direct touch works like touching the PC screen, in the style of Chrome Remote Desktop:

| Gesture | Result on the host |
|---|---|
| Tap | Left click where you tapped. Tap again at the same spot for a double click. |
| Drag | Scrolls the content under your finger (vertically; a clearly sideways drag at the start scrolls horizontally). A flick keeps scrolling for a moment. |
| Long press, then drag | A short vibration, then the left button is held while you move (select text, move windows or icons). Lifting the finger releases it. |
| Two-finger tap | Right click at the first finger. |
| Two-finger drag | Scroll. |

Games that need an immediate left-button drag can use trackpad mode or a mouse button on the
virtual keypad.

### Trackpad mode

With **Use the touchscreen as a trackpad** (Settings → Input, or the stream panel) the touchscreen
moves the pointer like a laptop trackpad. Movement keeps sub-pixel fractions between events, so slow
and fast motion stay exact.

**Trackpad speed** (50–300%) and **scroll speed** (25–300%, one- and two-finger scrolling)
apply immediately from Settings → Input or the stream panel.

## Pinch zoom

- Spreading two fingers zooms the stream **on this device** (up to 500%). The host's screen and
  resolution do not change.
- While zoomed, drag with two fingers to pan. One-finger touches (clicks, drags) land exactly where you
  touch on the zoomed picture (in direct touch mode; trackpad mode keeps moving the pointer).
- At normal size, two-finger tap (right click) and two-finger scroll work as usual: only a clear change
  of the distance between the fingers counts as zooming. While zoomed, two-finger drag pans, so return
  to normal size to scroll on the host.
- Tap the zoom tag at the bottom (for example `250%  ✕`), or pinch back to almost 100%, to return to
  normal size. Rotation and picture-in-picture also reset the zoom. The tag avoids the performance
  overlay, the connection warnings and the stream panel handle, sits above the on-screen controls and
  the keypad, and never sends input to the host. While the phone keyboard is up, the tag moves to the
  top of the screen.
- Turn it off with **Pinch to zoom the picture** in the stream panel or Settings → Input (on by
  default). Requires Android 7.0 or later.

## Text input bar

Typing whole words and sentences, including Korean and other non-Latin scripts, through the phone
keyboard.

- Open it with the keyboard button under the stream panel handle, a three-finger tap, **Type text…**
  in the stream panel, or the virtual keypad's quick menu.
- On Android 11 and later the bar sits right above the phone keyboard and slides with it, so the
  stream above stays in view. On older versions it opens at the top of the screen.
- Type in the bar and press **Send** (or the keyboard's send key): the text is typed on the PC as a
  whole. Composition (for example Korean syllables) happens on the phone and the finished characters
  are sent as Unicode, so the PC's input language does not matter.
- **⌫** deletes the last character in the bar, or sends Backspace to the PC when the bar is empty (the
  keyboard's own backspace does the same, including keyboards that delete without key events).
- **↵** sends the text followed by Enter (handy for chats). **⌨** closes the bar and sends the phone
  keyboard's keys directly again (arrows, Esc, game keys). **✕** closes the bar.
- The **shortcut row** below the bar (swipe for more): Shift+Enter, Esc, Tab, arrow keys, Del,
  Ctrl+A/C/X/V/Z, Alt+Tab, Win, Ctrl+Shift+Esc (Task Manager) and Ctrl+Alt+Del. Text still in the bar
  is sent first. Long-press a key to see what it does.
- **Ctrl+Alt+Del (Shell):** Windows ignores this combination from ordinary input, so Shell turns it
  into a secure attention request (SendSAS). The Shell installer enables the required policy
  (SoftwareSASGeneration).
- Long text is sent in chunks (30 bytes at a time). Characters outside the Basic Multilingual Plane,
  such as emoji, are skipped because they arrive garbled on the PC.
- Closing the bar returns keyboard and mouse input to the stream. Picture-in-picture closes it.

## Virtual keypad

A configurable on-screen panel that sends **keyboard keys and mouse buttons**, separate from the
gamepad-style on-screen controls.

- **On/off:** the **Virtual keypad** switch in the stream panel (applies immediately) or Settings →
  Input. Off by default.
- **Left side: direction**
  - Fixed joystick (default), floating joystick (appears wherever your thumb lands in the left 45% of
    the screen; touches there are not sent to the stream) or a d-pad.
  - 4 or 8 directions. The default keys are the arrow keys; each direction can be rebound.
- **Right side: keys**
  - By default 3 rows of 4 keys (Q W E R / A S D F / Z X C V), each row shifted half a key to the
    right.
  - Rows (1–4), keys per row (1–6), row shift (0–100%), key size and spacing are adjustable.
- **Bindings:** tap a key in the editor to choose what it sends: a keyboard key (letters, digits,
  arrows, Space/Enter/Esc, Shift/Ctrl/Alt/Win, F1–F12, symbols, numeric keypad, Korean/English toggle)
  or a mouse button (left, right, middle). Ctrl/Alt/Shift/Win can be added as modifiers (for example
  Ctrl+C or Win+D), and every button can have its own label.
- **Combos:** with **Combination: press several keys in order**, each tapped key is appended (up to 5; tap
  again to remove). The combo is shown at the top (for example `Ctrl+Alt+Del`, `Shift+1+2`). Pressing
  the button presses the modifiers first, then the keys in order, and releases them in reverse order.
- **Delay between keys:** 0–500 ms, for games and programs that miss keys pressed too quickly. Even a
  short tap presses every key before releasing.
- **Live editing:** **Edit keypad…** in the stream panel opens an editor on top of the running stream.
  In landscape it sits in the free space between the joystick and the keys (or on the opposite side if
  there is no room); in portrait it sits at the top. **Collapse** leaves only its title row. While the
  editor is open, the joystick and the key block can be dragged directly (tap the rest position of a
  floating joystick), size, spacing and opacity sliders apply as you move them, and positions snap to
  a 16 dp grid that is shown while editing. If the keypad is off, it is shown only while editing.
  **Reset** asks for confirmation; **Done** or Back closes the editor.
- Positions are stored relative to the screen, so they survive rotation. Editing from Settings (before
  connecting) uses a dialog because the keypad is not on screen.
- Opacity is adjustable. Touches outside the controls (including the gaps between keys) go to the
  stream as usual. Keys and directions give a short vibration when pressed (**Vibrate on each press**, on
  by default).
- **Quick menu (≡)**, on by default: attached to the top right of the key block (it can be dragged
  elsewhere while editing). It offers:
  - touch mode: direct touch or trackpad;
  - quality (bitrate): high, medium or low. Medium is the recommended bitrate for the current
    resolution and frame rate, high is 1.5×, low is half. With Shell the change applies without
    reconnecting; with automatic bitrate on, it becomes the maximum;
  - screen orientation: auto, landscape or portrait;
  - text input, keypad editing, showing or hiding the performance overlay, and all stream settings.
  Tapping outside the menu or pressing Back closes it without sending that touch to the PC. The
  editor's **Quick menu button** option hides it.
- The keypad hides in picture-in-picture, and all held keys are released when the stream ends or the
  settings change.

## Stream settings panel

While streaming, **Back** opens a settings panel instead of ending the stream. The small handle (‹) at
the screen edge opens it too.

- The handle sits on the right edge by default or on the left (**Stream settings handle** in the
  panel or in Settings); the panel opens from that side. Long-press the handle to drag it up or down;
  the position is saved. The **keyboard button** just below the handle opens and closes the text input
  bar.
- Back, ✕ or a tap outside closes the panel. **Disconnect** ends the stream.
- **Applied immediately:** performance overlay (on/off, metrics, text size; turning every metric off
  hides it), screen orientation, input (trackpad mode, trackpad speed, scroll speed, pinch zoom,
  on-screen controls, virtual keypad) and other options (clipboard sync, handle position). Options
  that do not apply to the current mode are dimmed.
- **Screen orientation:** auto (default) follows the longer side of the stream resolution. Landscape
  and portrait lock the orientation. Rotating does not interrupt the stream.
- **Bitrate (live changes need Shell):** with Shell (NVIDIA encoder) a new bitrate applies as soon as
  you release the slider or enter a value, without reconnecting. Other hosts apply it by reconnecting.
  If a live change fails, the panel says so and offers **Apply and reconnect**. A timed-out request
  may still have reached the host, so the next change is always sent, even with the same value
  (**Revert** sends the reverted value as well). If a change fails after the panel was closed, a
  notice is shown once.
- **Applied by reconnecting:** resolution (from the list or typed as `WIDTHxHEIGHT`), frame rate,
  codec and HDR. The list includes this device's own screen size. On devices locked to landscape,
  portrait resolutions are left out, and 4K, 90/120 FPS and HDR are offered only when the screen and
  decoder support them (the same rules as the settings screen). Out-of-range values (320x240 to
  7680x4320, 0.5 to 150 Mbps) are flagged in the field. **Apply and reconnect** continues with the new
  settings after about 2–3 seconds; the app on the host keeps running.
- **Quit app and disconnect** asks for confirmation, then quits the app on the host and ends the
  stream.
- Values changed in the panel are the same settings as in the settings screen and are saved.

## Automatic bitrate (Shell)

Turn it on with **Adjust the bitrate automatically (Shell host)** in Settings or **Adjust automatically
to the network** in the stream panel. Off by default.

- Once a second Hermit looks at frame loss and round-trip time and adjusts the bitrate through Shell's
  live bitrate control, without reconnecting.
  - Frame loss above 2%, or a round-trip time clearly above normal (at least 40 ms and 1.5× higher),
    lowers the bitrate to 80%: at most once every 2 seconds, down to a fifth of the chosen bitrate
    (minimum 2 Mbps).
  - After 10 good one-second intervals (and at least 10 seconds since the last change) it rises by a
    tenth of the chosen bitrate (minimum 1 Mbps), up to the chosen bitrate.
  - Seconds with fewer than 20 frames (a still screen) are not judged on frame loss, only on round-trip
    time, and count neither as good nor as bad. Recovery therefore continues slowly during document
    work with frequent pauses.
  - When turned on, it starts from the bitrate the host is currently sending (kept between a fifth of
    the chosen value, minimum 2 Mbps, and the chosen value).
- The chosen bitrate is the maximum. Moving the slider sets a new maximum and a new starting point. The
  panel shows `Automatic: now N Mbps (up to M Mbps)`.
- Turning it off returns to the chosen bitrate. Failed requests are retried every 5 seconds.
- Hosts without live bitrate control (anything other than Shell) and non-NVIDIA encoders are not
  supported. The rules match Hermit for Windows.

## Bitrate input and recommended bitrate

- Slider dialogs in Settings (bitrate, dead zone, on-screen control opacity and others) accept typed
  values, which are used as entered rather than rounded to the slider step.
- The bitrate dialog shows the **recommended bitrate** for the selected resolution, frame rate and
  codec, using the same formula as Hermit for Windows: pixels × FPS × 0.15 bits for H.264, 70% of that
  for HEVC and AV1, and above 60 FPS only the square root of the extra frame rate counts. Example:
  2560x1440 at 60 FPS → 33 Mbps (H.264) / 23 Mbps (HEVC, AV1).

## Performance overlay

Turn it on in Settings → Advanced, then choose **Performance overlay: metrics to show** and
**Performance overlay: text size** (small, medium, large).

- Metrics: video (resolution, codec, FPS), received/rendered FPS, bitrate (including the 10-second
  peak), network loss, round-trip time, host latency, decode time, estimated total latency, decoder,
  and this device (battery %, charging, battery temperature, thermal status).
- By default only the essentials are on: video, bitrate, network loss, round-trip time, host latency
  and estimated total latency.
- Estimated total latency = host latency + RTT/2 + decode time. Display queueing is not measured on
  Android and is not included.
- The overlay is a translucent panel with aligned label and value columns and tabular figures.

## Session summary and history

- After streaming for at least 30 seconds, returning to the app list shows a summary: average FPS,
  network loss, host latency (average and maximum), round-trip time and decode time, each next to the
  **average of the last 10 sessions**, rated good, fair or poor.
  - Thresholds (same as Hermit for Windows): network loss ≤ 0.1% good, > 1% poor; average host latency
    ≤ 8 ms good, > 16 ms poor; RTT ≤ 30 ms good, > 80 ms poor.
  - Average FPS is not rated: the host only sends frames when the screen changes, so a quiet screen
    gives a low number.
- Every session of 30 seconds or more is appended to `session-history.csv` in the app's private
  storage.
- **Show a summary after each session** in Settings → Advanced (on by default), or **Don't show
  again** in the summary.

## Clipboard sync

- Returning to the stream sends what you copied on the device to the host; leaving the stream puts
  what you copied on the host on the device clipboard (the same rules as Hermit for Windows).
- Text works with Shell and other hosts that offer the same clipboard extension (for example Apollo).
  **Images (Shell)** are synced too: JPEG and WebP images from the device are converted to PNG (photos
  above 16 megapixels are halved to avoid running out of memory), up to 32 MB. Content that could not
  be sent is retried the next time you return to the stream.
- Android only lets the visible app read the clipboard, and leaving the app ends the stream. Hermit
  therefore checks the host clipboard's change counter once a second while streaming, fetches changes
  ahead of time and puts them on the device clipboard the moment you leave.
- Content already exchanged is not sent again, so Android's "pasted from clipboard" notice appears only
  for new content.
- The host must grant this device clipboard permission; without it Hermit shows a notice once and stops
  trying.
- **Sync clipboard with the host** in Settings → Host settings (on by default).

## Automatic reconnect

- When the network cuts the stream off, a **Connection lost** dialog counts down 5 seconds and then
  reconnects to the same app, with **Connect now** and **Cancel** buttons. Up to 3 attempts; the
  counter resets after a minute of streaming.
- No reconnect is attempted when:
  - no video ever arrived (usually a firewall or port forwarding problem; the error is shown at once);
  - the host refused the connection (unpaired, no permission and so on);
  - a different app is now running on the host. A normal start would quit that app first; a reconnect
    stops instead.
- **Reconnect automatically when the connection drops** in Settings → Host settings (on by default).

## Shutting down or restarting the PC (Shell)

- Long-press a PC on the PC list → **Restart PC…** or **Shut down PC…** (online, paired PCs only).
- Hermit first asks the host which other devices are connected and, if there are any, names them with
  a warning that their streams will end, then asks for confirmation.
- **Also close apps with unsaved work (it is lost)** forces apps that block shutdown to close (unsaved work is lost).
  Without it, apps are only asked to close, which such an app can block.
- The host turns off or restarts after 5 seconds. After a restart you can reconnect without
  Wake-on-LAN; a PC that was shut down can be woken with Wake-on-LAN from its menu on the PC list.
- The device needs the **Launch apps** permission on Shell's web UI pairing page. Other hosts report
  that the feature is not supported.

## Notices

- Instead of system toasts, Hermit shows its own notice card at the bottom centre (above the phone
  keyboard and navigation bar). While the text input bar is at the bottom, notices move to the top.
- One notice is shown at a time; a new one replaces or joins the current one, and touches pass through
  the card. A notice whose screen closes right away is carried over to the next screen. A system toast
  is used only when no Hermit screen is visible.

## Crash reports

- If the app crashes, the error (stack trace, the last 400 lines of the app's own log, device and
  Android version) is saved on the device. The next launch shows a "Hermit closed unexpectedly"
  dialog with **Copy** and **Share**, so a report can be passed on without adb.
- On Android 11 and later, native crashes and "app not responding" events are also read from Android's
  exit records. Native tombstones are binary, so only their description is included.
- Nothing is uploaded automatically: a report leaves the device only if you copy or share it.

## Appearance and languages

- A dark theme in the IBM Carbon style shared with Hermit for Windows: background #161616, panels
  #262626, borders #393939, text #F4F4F4/#C6C6C6 and a single teal accent (#08BDBA).
- Text uses IBM Plex Sans KR on Android 10 and later, table headers IBM Plex Sans Condensed.
- The interface is in English and Korean. Other languages inherited from the upstream project are
  partly translated; strings that mention another app or host by name were removed from them and fall
  back to English.

## For developers

- Hermit's own feature code is in `app/src/main/java/com/junopark/hermit/hermit/`. The Java namespace
  `com.junopark.hermit` is separate from the application ID; JNI function names
  (`Java_com_junopark_hermit_nvstream_jni_MoonBridge_*` in `app/src/main/jni/hermit-core/*.c`) depend
  on it.
- The streaming core (`moonlight-common-c`, a git submodule) and the prebuilt OpenSSL and Opus
  libraries in `app/src/main/jni/hermit-core/` are third-party code and are not modified.
- Branding, theme and wording live in the nonRoot flavour (`app/src/nonRoot/res`,
  `app/src/nonRoot/AndroidManifest.xml`).
- Strings are generated by `python hermit/make_hermit_strings.py` into
  `app/src/main/res/values{,-ko}/hermit_strings.xml` and `app/src/nonRoot/res/values{,-ko}/strings.xml`.
  Edit the script, not the generated files.
- The launcher icon, TV banner and channel logo are generated by `python hermit/make_hermit_icon.py`.
- `hermit/check-view-types.py`, `hermit/check-strings.py` and `hermit/check-prefs.py` catch mistakes
  that compile but crash at runtime; run them before sending a change.
