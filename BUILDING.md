# Building Curmudgeon Browser

Requirements: JDK 17, Android SDK with platform `android-36` and build-tools `36.0.0`. Gradle is fetched by the wrapper (9.4.1).

```sh
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew :app:assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest      # unit tests
./gradlew :app:assembleRelease        # app/build/outputs/apk/release/ (unsigned unless an upload key is configured)
./gradlew :app:bundleRelease          # Play bundle
```

Release signing is optional and reads `~/.android-keys/curmudgeon-upload.properties`
(`storeFile`, `storePassword`, `keyAlias`, `keyPassword`). Without that file the release build is unsigned,
so anyone can reproduce the APK contents from source and compare everything except the signature.

Versions are pinned: Android Gradle Plugin 9.2.1, Kotlin 2.3.20, androidx.core 1.17.0, appcompat 1.7.1,
preference 1.2.1, webkit 1.14.0.
