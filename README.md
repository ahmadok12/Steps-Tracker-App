# TrueSteps

An Android step counter that **removes steps counted while you're riding a motorbike or in a car**.

## How it decides

Steps are collected in 30-second blocks. Each block is checked against:

1. **GPS speed**: 15 km/h or faster means a vehicle, and the block is removed.
2. **Google activity recognition**: "In vehicle" or "On bicycle" at 60%+ confidence means the block is removed.
3. **Step rate**: more than 4 steps per second is vibration, not feet, so the block is removed.
4. **Walking confirmed**: Google says "Walking" or GPS shows walking pace, so the block is kept.
5. **Right after a ride** (3 minutes): slow movement is held until walking is confirmed. This handles traffic jams and signals.
6. **Not sure yet** (for example, GPS hasn't locked on at the start of a ride): the block is held. The next clear block decides it.

## Battery

- GPS runs only in short bursts (3 readings, then off for at least a minute). A burst starts only when there are 20+ steps in 30 seconds and Google's activity detection isn't already confident.
- Activity detection checks every 30 seconds while you're moving, and every 3 minutes while you're idle.
- Step updates are grouped about every 10 seconds, so the processor can sleep in between.
- **Quiet hours** (Settings ⚙): only the step counter runs. GPS and activity detection are off.

The "Recent decisions" list in the app shows every block and the reason it was kept or removed.

## Build the APK (no Android Studio needed)

1. Create a new **private** repository on GitHub.
2. Upload all files from this folder to it. Keep the `.github` folder, which is hidden on some systems.
3. Open the **Actions** tab. The "Build APK" workflow runs automatically and takes about 5 minutes. To run it again, use **Run workflow**.
4. Open the finished run and download **TrueSteps-apk** under *Artifacts*. Unzip it to get `app-debug.apk`.
5. Copy the APK to your phone, open it, and allow "Install unknown apps" when asked.

**Or with Android Studio:** File → Open → choose this folder → Run ▶ with your phone connected over USB.

## First run on the phone

1. Tap **Start tracking** and allow **Physical activity**, **Location** ("While using the app" is enough) and **Notifications**.
2. Tap **battery settings** and set TrueSteps to **Unrestricted** / **Don't optimize**. Infinix, Tecno, Xiaomi, Oppo and Vivo phones also have an "Auto-start" or "Background activity" setting, which must be allowed too.
3. A silent notification stays visible while tracking runs. That's normal, and it's what keeps the app alive.

## Project layout

| File | What it does |
|---|---|
| `StepFilter.kt` | Decision logic (pure Kotlin, tunable thresholds at the top) |
| `StepTrackingService.kt` | Background service: step sensor, GPS, activity updates, 30 s blocks |
| `ActivityUpdatesReceiver.kt` | Receives Google activity-recognition results |
| `StepDatabase.kt` | SQLite storage of every block |
| `MainActivity.kt` | The screen |
| `BootReceiver.kt` | Restarts tracking after a reboot |
