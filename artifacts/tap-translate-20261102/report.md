# Tap-to-translate QA — 2026-11-02

Device: Pixel 7 Pro AVD, Android 15, x86_64 debug. Version 2.2 (code 5). Synthetic test page: Welcome / Good morning / Thank you for your help. The fixture is in the test APK only.

- Recording regression checks: 2 passed. A 24 kHz WAV prepares in Android MediaPlayer without autoplay; an 8 MiB size cap and discard behavior are verified.
- Ready at 22:33:58.925. No capture or network request before the first button tap at 22:34:19.304 (over 20 seconds idle).
- First tap: exactly one screenshot at 22:34:19.710; one 44-character translation request at 22:34:21.948; complete at 22:34:26.740. All three source lines translated. No automatic playback.
- Nghe đọc changed to Dừng nghe while playing. Audio playback was initiated only by this explicit tap.
- Dịch lại: one screenshot at 22:35:25.828 and one 44-character request at 22:35:27.734. OCR again saw 3 lines from the fixture, not the result controls. Complete at 22:35:31.634.
- Đóng dismissed the panel and restored the floating button. Later idle check remained at 2 screenshots / 2 requests / 2 completions. No automatic next translation.
- Tắt nút Dịch was exercised via the app UI.

The final rebuild only changes the connection status wording to avoid technical model details. Device metrics and response times here are a few emulator samples, not guarantees for phones, arbitrary languages, or full documents. Protected windows may block screenshots or app overlays. Whole-screen translation is implemented; region selection is not.
