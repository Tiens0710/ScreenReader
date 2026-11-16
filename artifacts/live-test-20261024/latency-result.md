# Screen-change latency verification

Device: Pixel 7 Pro AVD, Android 15, debug x86_64. Times are from device logcat; first audio receipt is not speaker presentation or a screenshot proof of the outline.

Before fixes: Network & internet tap 16:45:53.394 -> audio 16:46:00.628: 7.234 seconds, including discarded OCR.

Settling + cooperative cancellation: Network page tap 16:56:09.322 -> audio 16:56:13.913: 4.591 seconds. Second tap 16:56:43.907 -> audio 16:56:48.128: 4.221 seconds.

Final cloud-turn replacement test: switched to Wi-Fi Settings during an active Gemini request at 17:00:34.557 -> new screen audio at 17:00:39.251: 4.694 seconds. OCR of the new page: 1090 ms; request sent 17:00:37.414. New request was sent before the previous normal turn had to complete.

Two device regression checks passed: quiet window resets on each change; superseded Paddle inference exits between stages and a subsequent frame remains usable.

These are a few emulator samples, not a phone benchmark or a guaranteed latency bound. Server/audio timing and UI animations vary.
