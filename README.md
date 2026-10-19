# Hisaab

A simple money tracker for Android: add spending and income in two taps, keep track of monthly bills, and keep a list of things to buy.

## Download

**[Download Hisaab.apk](../../releases/latest/download/Hisaab.apk)**

Open the file on your phone. If Android asks, allow installing apps from your browser or file manager.

## How it's built

The app is a single web page (`app/src/main/assets/index.html`) inside a small Android WebView shell. Every push to `main` builds a new signed APK with GitHub Actions and publishes it as the latest release. Your data is stored on the phone and stays when you update to a newer APK.
