# Holdings Ledger

A private, on-device tracker for stocks, ETFs and crypto. No account, no analytics, no trackers.

- Holdings are stored only on the device (WebView local storage) and can be exported/imported as JSON.
- Prices come from **CoinGecko** (crypto), **Yahoo Finance** (stocks/ETFs) and **Frankfurter** (ECB exchange rates). Only ticker symbols and coin IDs are sent. The native layer refuses every other host.
- The UI is a bundled HTML/JS page (`app/src/main/assets/www/`) shown in a WebView. Native code (`MainActivity.java`) provides HTTPS requests, file export and import, the back button and edge-to-edge insets.

## Build

Requires JDK 17 and the Android SDK (compileSdk 35).

```sh
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease      # unsigned release APK
```

The GitHub Actions workflow in `.github/workflows/build.yml` builds both APKs on every push.

## Publishing on F-Droid

1. **Publish the source.** Create a public repo named `holdings-ledger` on GitHub, GitLab or Codeberg and push this folder:
   ```sh
   git init && git add . && git commit -m "Holdings Ledger 1.0.0"
   git branch -M main
   git remote add origin https://github.com/YOUR_USERNAME/holdings-ledger.git
   git push -u origin main
   ```
2. **Test the build.** Install the debug APK from the Actions run (or build it locally) on a phone. Check that prices load, charts render, backup export and import work, and the back button closes sheets.
3. **Tag the release.** `git tag v1.0.0 && git push --tags`. F-Droid builds from this tag. Every later release bumps `versionCode` and `versionName` in `app/build.gradle`, adds `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`, and gets a new `vX.Y.Z` tag. F-Droid picks new tags up automatically.
4. **Submit to fdroiddata.**
   - Create an account on gitlab.com and fork https://gitlab.com/fdroid/fdroiddata.
   - Copy `fdroid/com.holdingsledger.yml` to `metadata/com.holdingsledger.yml` in your fork, and fill in `YOUR_USERNAME` and `YOUR_NAME`.
   - Optionally run `fdroid lint com.holdingsledger`, `fdroid rewritemeta com.holdingsledger` and `fdroid build -v -l com.holdingsledger` in the fork.
   - Push to a new branch and open a merge request against `fdroiddata` master using the "App inclusion" template. The pipeline builds the app. Answer any reviewer questions in the MR.
5. After the merge, the app usually appears in the F-Droid client within a few days.

Store listing text, the icon and screenshots are in `fastlane/metadata/android/en-US/`, and F-Droid reads them from there.

### Notes for reviewers

- **Anti-feature `NonFreeNet`:** price data comes from CoinGecko and Yahoo Finance, which are non-free network services. Exchange rates come from Frankfurter, which is open source and uses ECB data.
- **No proprietary dependencies.** The only library is `androidx.webkit` from Google's Maven repository. There is no Google Play Services, Firebase or analytics code.
- **No company or crypto logos.** Assets are shown as text badges.
- **Fonts:** Exo 2 and JetBrains Mono are bundled under the SIL Open Font License 1.1 (see `app/src/main/assets/www/fonts/`).

## Licence

GPL-3.0-or-later. See `LICENSE`.
