# F-Droid submission

`metadata/*.yml` are the build recipes for [fdroiddata](https://gitlab.com/fdroid/fdroiddata),
one per app (phone `com.saunapulse.app`, watch `com.saunapulse.wear`). Both build from the same
tag; store texts and icons come from `<module>/fastlane/metadata/android/`.

To submit: fork fdroiddata on GitLab, copy both files into its `metadata/` folder, commit on a
new branch and open a merge request using the "App inclusion" template.

For a new release: bump `versionCode`/`versionName` in `mobile/` and `wear/build.gradle.kts`,
add `changelogs/<versionCode>.txt`, commit and push a `vX.Y.Z` tag. F-Droid picks it up
automatically (`UpdateCheckMode: Tags`).
