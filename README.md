# Wedding Gift Tracker

A free, private app for recording wedding cash and gifts. It works fully offline, and every record stays on the phone where it was entered.

## Install

**Android phones:** open the **[Releases page](../../releases/latest)** on the phone. Tap **WeddingGifts.apk** to download it, then open it to install.
If Android asks, allow installing from this source. If Play Protect warns about an unknown developer, tap *More details → Install anyway*.

**iPhone (or any phone):** open `https://YOUR-USER-NAME.github.io/wedding-gifts/` in Safari or Chrome, then choose **Add to Home Screen**. Always open the app from that icon.

After installing, the app needs no internet or Wi-Fi.

## Important: backups

Records exist only on each phone. Uninstalling the app or losing the phone deletes them.
Use **Settings → Save backup file** regularly and keep the file somewhere safe, such as Google Drive.
To combine phones, use **Settings → Restore or merge a backup file**.

## What's in this repository

| Folder | Contents |
| --- | --- |
| `app/` | Android app project (`app/src/main/assets/index.html` is the whole app) |
| `docs/` | Web version, published with GitHub Pages |
| `.github/workflows/build-android.yml` | Builds `WeddingGifts.apk` automatically and publishes it on the Releases page |

This repository contains only the blank app. No gift records are ever uploaded.
