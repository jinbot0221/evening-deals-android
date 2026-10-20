#!/usr/bin/env bash
# Source this file in the cloud workspace. Android Studio manages its own SDK locally.
export ANDROID_HOME="${ANDROID_HOME:-/workspace/android-sdk}"
export ANDROID_USER_HOME="${ANDROID_USER_HOME:-/workspace/.android}"
export ANDROID_EMULATOR_HOME="${ANDROID_EMULATOR_HOME:-$ANDROID_USER_HOME}"
export ANDROID_AVD_HOME="${ANDROID_AVD_HOME:-$ANDROID_USER_HOME/avd}"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-/workspace/.gradle}"
if [[ -z "${JAVA_HOME:-}" ]]; then
  for jdk in /workspace/tools/jdk-21*; do
    if [[ -x "$jdk/bin/javac" ]]; then export JAVA_HOME="$jdk"; break; fi
  done
fi
export PATH="${JAVA_HOME:+$JAVA_HOME/bin:}$ANDROID_HOME/platform-tools:$PATH"
