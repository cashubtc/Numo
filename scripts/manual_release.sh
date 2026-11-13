#!/usr/bin/env bash
# Release workflow commands. Inputs and signing credentials come from the environment.
set -euo pipefail

cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
export KEYSTORE_PATH="${KEYSTORE_PATH:-$HOME/keystore/app-release.keystore}"

decode_keystore() {
    : "${ENCODED_KEYSTORE:?ENCODED_KEYSTORE is required}"
    mkdir -p -- "$(dirname -- "$KEYSTORE_PATH")"
    printf '%s' "$ENCODED_KEYSTORE" | base64 --decode > "$KEYSTORE_PATH"
}

configure_signing() {
    cat <<EOT >> app/build.gradle.kts

// Added by GitHub Actions for app signing
android {
    signingConfigs {
        create("release") {
            storeFile = file("$KEYSTORE_PATH")
            storePassword = System.getenv("KEYSTORE_PASSWORD")
            keyAlias = System.getenv("KEY_ALIAS")
            keyPassword = System.getenv("KEY_PASSWORD")
        }
    }

    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("release")
        }
        getByName("play") {
            signingConfig = signingConfigs.getByName("release")
        }
    }
}
EOT
}

enable_debug_symbols() {
    cat <<'EOT' >> app/build.gradle.kts

// Added by GitHub Actions to bundle debug symbols for pre-release
android {
    defaultConfig {
        ndk {
            debugSymbolLevel = "FULL"
        }
    }
}
EOT
}

build_release() {
    : "${RELEASE_VERSION:?RELEASE_VERSION is required}"
    : "${RELEASE_CODE:?RELEASE_CODE is required}"
    ./gradlew "$1" -PnumoVersionCode="$RELEASE_CODE" -PnumoVersionName="${RELEASE_VERSION#v}"
}

rename_artifacts() {
    : "${RELEASE_VERSION:?RELEASE_VERSION is required}"
    local apk_dir="app/build/outputs/apk/release"
    local bundle_dir="app/build/outputs/bundle/play"
    local artifact filename suffix

    echo "Using version: $RELEASE_VERSION"
    if compgen -G "$apk_dir/*.apk" > /dev/null; then
        for artifact in "$apk_dir"/*.apk; do
            filename=$(basename -- "$artifact")
            case "$filename" in
                *universal*) suffix="universal" ;;
                *armeabi-v7a*) suffix="armeabi-v7a" ;;
                *arm64-v8a*) suffix="arm64-v8a" ;;
                *x86_64*) suffix="x86_64" ;;
                *x86*) suffix="x86" ;;
                *) suffix="universal" ;;
            esac
            echo "Renaming $artifact to $apk_dir/numo-${RELEASE_VERSION}-${suffix}.apk"
            mv -- "$artifact" "$apk_dir/numo-${RELEASE_VERSION}-${suffix}.apk"
        done
    else
        echo "No APK files found in $apk_dir"
    fi

    if compgen -G "$bundle_dir/*.aab" > /dev/null; then
        for artifact in "$bundle_dir"/*.aab; do
            echo "Renaming $artifact to $bundle_dir/numo-${RELEASE_VERSION}.aab"
            mv -- "$artifact" "$bundle_dir/numo-${RELEASE_VERSION}.aab"
            break
        done
    else
        echo "No AAB files found in $bundle_dir"
    fi
}

create_manifest() {
    : "${ANDROID_HOME:?ANDROID_HOME is required}"
    : "${RUNNER_TEMP:?RUNNER_TEMP is required}"
    : "${RELEASE_VERSION:?RELEASE_VERSION is required}"
    : "${PRE_RELEASE:?PRE_RELEASE is required}"
    local build_tools channel="stable"
    build_tools=$(find "$ANDROID_HOME/build-tools" -mindepth 1 -maxdepth 1 -type d \
        | sort -V | tail -n 1)
    if [ "$PRE_RELEASE" = true ]; then
        channel="beta"
    fi
    printf '%s' "${RELEASE_NOTES:-}" > "$RUNNER_TEMP/update-notes.txt"
    python3 scripts/create_update_manifest.py \
        --apk "app/build/outputs/apk/release/numo-${RELEASE_VERSION}-universal.apk" \
        --tag "$RELEASE_VERSION" --channel "$channel" --check-latest \
        --aapt "$build_tools/aapt" --apksigner "$build_tools/apksigner" \
        --notes-file "$RUNNER_TEMP/update-notes.txt" \
        --output app/build/outputs/apk/release/update.json
}

if [ "$#" -ne 1 ]; then
    echo "Usage: $0 <decode-keystore|configure-signing|enable-debug-symbols|build-apk|build-bundle|rename-artifacts|create-manifest>" >&2
    exit 1
fi

case "$1" in
    decode-keystore) decode_keystore ;;
    configure-signing) configure_signing ;;
    enable-debug-symbols) enable_debug_symbols ;;
    build-apk) build_release assembleRelease ;;
    build-bundle) build_release bundlePlay ;;
    rename-artifacts) rename_artifacts ;;
    create-manifest) create_manifest ;;
    *)
        echo "Unknown release command: $1" >&2
        exit 1
        ;;
esac
