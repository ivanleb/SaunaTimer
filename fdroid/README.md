# F-Droid submission

`metadata/*.yml` are the build recipes for [fdroiddata](https://gitlab.com/fdroid/fdroiddata),
one per app (phone `com.saunapulse.app`, watch `com.saunapulse.wear`). Both build from the same
tag; store texts and icons come from `<module>/fastlane/metadata/android/`.

To submit: fork fdroiddata on GitLab, copy both files into its `metadata/` folder, commit on a
new branch and open a merge request using the "App inclusion" template.

## Reproducible builds

F-Droid builds each app from the tag and publishes it with **our** signature, if its APK is
byte-identical to the one on GitHub Releases (`Binaries` and `AllowedAPKSigningKeys` in the recipes).
Verified for 1.0.0: an unsigned Windows build matched the F-Droid CI build exactly.

The release APKs must come from a **clean `git clone`** checked out at the tag. AGP writes the git
revision into `META-INF/version-control-info.textproto`, so a build from a dirty tree, another
commit or a `git worktree` (no `.git` directory) won't match.

For a new release:
1. Bump `versionCode`/`versionName` in `mobile/` and `wear/build.gradle.kts`, add
   `changelogs/<versionCode>.txt` for both apps, commit, push, then push a `vX.Y.Z` tag.
2. `git clone <repo> clean && cd clean && git checkout vX.Y.Z`, copy `local.properties`
   (no `keystore.properties`), run `./gradlew :mobile:assembleRelease :wear:assembleRelease`.
3. Sign the `*-release-unsigned.apk` files, keeping the alignment so F-Droid can copy the signature:
   `apksigner sign --ks <key> --ks-key-alias saunapulse --v1-signing-enabled false
   --v2-signing-enabled true --v3-signing-enabled false --v4-signing-enabled false
   --alignment-preserved true --out SaunaPulse-phone-X.Y.Z.apk mobile-release-unsigned.apk`
   (the same for `SaunaPulse-watch-X.Y.Z.apk`).
4. Upload both APKs to the GitHub Release `vX.Y.Z` with exactly these file names. F-Droid picks
   the tag up automatically (`UpdateCheckMode: Tags`) and checks the APKs against its own build.
