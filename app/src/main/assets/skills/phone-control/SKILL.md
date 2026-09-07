---
name: phone-control
description: Operate Android apps on device: screenshot, tap, scroll, type, and keys.
category: android
---

# Phone control

Operate external Android apps on the device using the `phone_control` tool with a visible pointer.

The user sees actions live with a floating pointer and control pill. Say what you are about to do before taking an action.

## When to use
- Open or interact with an external Android app
- Tap buttons, fill text fields, or scroll through content in another app
- Inspect device UI state via screenshot
- Automate repetitive phone UI steps across installed apps

## When not to use
- Interacting with web pages or dev servers: use `browser_*` tools instead
- Inspecting app crash logs or system errors: use `read_logcat`
- Automating AndroidHarness itself: AndroidHarness UI is intentionally protected from agent input

## Prerequisites
- Shizuku must be running and authorized for ADB shell privileges
- MediaProjection (screen capture) and overlay permissions must be granted
- Calling `phone_control` automatically launches the consent prompt if not already active. If declined or cancelled, do not retry without user request

## Procedure
1. Check status or take a screenshot:
   - Call `phone_control(action="screenshot")` to capture the current foreground app.
   - Calling `phone_control` prompts the user for capture and overlay permissions if not already active.
2. Inspect the screenshot:
   - The screenshot result returns an image reference and display dimensions (e.g. 1080x2400).
   - Inspect the visual content to identify target element coordinates.
3. Coordinate handling:
   - Coordinates use physical screen pixels.
   - If the vision model resizes or downscales the full screenshot, supply `screenshot_width` and `screenshot_height` alongside `x` and `y` for automatic scaling to device pixels.
   - Never use coordinates from cropped images.
4. Dispatch input:
   - `click`: `phone_control(action="click", x=..., y=...)` taps target coordinates.
   - `move`: `phone_control(action="move", x=..., y=...)` moves pointer without consuming observation.
   - `type`: `phone_control(action="type", text="...")` inputs 1-500 printable ASCII characters (spaces handled automatically). Use phone keyboard for non-ASCII or credentials.
   - `drag`: `phone_control(action="drag", x=..., y=..., x2=..., y2=...)` swipes between points.
   - `scroll`: `phone_control(action="scroll", x=..., y=..., text="-5")` scrolls vertically (-10..10, nonzero).
   - `key`: `phone_control(action="key", text="BACK")` sends system keys (BACK, HOME, ENTER, DEL, TAB, APP_SWITCH).
5. Observation rules and timing:
   - A screenshot observation allows the first input for up to 30 seconds.
   - Further inputs in the same foreground window can reuse the observation within 3 seconds of the first input.
   - Any navigation, submission, dialog/modal popup, drag, scroll, or non-DEL key invalidates observation: always take a new screenshot before subsequent input.
   - If the observation expires, take a new screenshot before retrying.

## Safety and rules
- AndroidHarness itself is blocked: if AndroidHarness is in the foreground, tell the user to switch to the target app or take a screenshot once the target app is in front.
- Floating controls: if target coordinates fall under the floating pill or overlay, ask the user to move the floating panel.
- Ask confirmation before sensitive or irreversible actions: purchases, money transfers, sending messages/emails, account deletions, or system settings changes.
- Never enter credentials, passwords, PINs, or financial secrets. Never attempt to bypass secure screens (FLAG_SECURE blocks capture by design).
- If the user pauses phone control or closes capture, respect it and stop.
