# Hosted iOS build

Windows or Ubuntu can submit the build. The native compilation runs on a macOS runner with Xcode. The first job compiles and links the UIKit host, the Newscape HD and Ultra HD backend, and the native 117 HD library for arm64 iOS.

## Private GitHub Actions

The source-only compile kit is a small ZIP made from this workspace and the pinned Android native snapshot.

1. Extract it into a dedicated private GitHub repository and commit the files to its default branch.
2. In GitHub Actions, choose **iOS native compile check** and run it manually. The macOS runner reports the SDK version and whether the two iOS binaries compiled and linked. The job uses the account’s Actions minutes.
3. Use that result to fix Apple compiler or linker errors before packaging. The compile kit contains the necessary native source, build scripts, and license. Game JARs, caches, credentials, and Apple signing material stay with the existing private build stage.

The native check can also run on any approved Mac with Xcode and Python 3.11 or newer.

    bash Tools/check_native_ios.sh Inputs/AndroidNative newscape-ios-native

## Private iPhone test

After the compiler check, use the full staged runtime and the Xcode project on a Mac with an owner-controlled Apple development signing identity. The complete build and device checklist is in the main project README. The iPhone or iPad must be registered for the development build.

A compatible Apple device is needed to check the Java runtime under the development debugger, login, renderer switching, and 117 HD frame pacing. The Android phone can be used independently to test the compact plugin panels in the signed Android build.