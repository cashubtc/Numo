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

download_release_apk() {
    : "${RELEASE_VERSION:?RELEASE_VERSION is required}"
    : "${GH_TOKEN:?GH_TOKEN is required}"
    : "${GITHUB_ENV:?GITHUB_ENV is required}"
    local apk_dir="app/build/outputs/apk/release"
    local filename="numo-${RELEASE_VERSION}-arm64-v8a.apk"
    local commit
    mkdir -p -- "$apk_dir"
    gh release download "$RELEASE_VERSION" --pattern "$filename" --dir "$apk_dir" --clobber
    commit=$(git rev-list -n 1 "$RELEASE_VERSION" --)
    printf 'RELEASE_COMMIT=%s\n' "$commit" >> "$GITHUB_ENV"
}

install_zsp() {
    : "${RUNNER_TEMP:?RUNNER_TEMP is required}"
    local version="0.3.4"
    local nak_version="0.22.3"
    local zsp="$RUNNER_TEMP/zsp"
    local nak="$RUNNER_TEMP/nak"
    curl --fail --location --retry 3 --silent --show-error \
        "https://github.com/zapstore/zsp/releases/download/v${version}/zsp-${version}-linux-amd64" \
        --output "$zsp"
    printf '%s  %s\n' \
        'f8240a54d08ad1d2c93f230b4e3af90f497a8be3b47bd06f3b52ac9995fb60d3' "$zsp" \
        | sha256sum --check
    chmod +x "$zsp"
    curl --fail --location --retry 3 --silent --show-error \
        "https://github.com/fiatjaf/nak/releases/download/v${nak_version}/nak-nodb-v${nak_version}-linux-amd64" \
        --output "$nak"
    printf '%s  %s\n' \
        'a0d2b7be59572cddeb63f216fd5b0d4b63f73b4a0ae2216114ffc35a3e6c4b2b' "$nak" \
        | sha256sum --check
    chmod +x "$nak"
}

publish_zapstore() {
    : "${RUNNER_TEMP:?RUNNER_TEMP is required}"
    : "${RELEASE_VERSION:?RELEASE_VERSION is required}"
    : "${KEYSTORE_PATH:?KEYSTORE_PATH is required}"
    : "${KEYSTORE_PASSWORD:?KEYSTORE_PASSWORD is required}"
    : "${KEY_ALIAS:?KEY_ALIAS is required}"
    : "${KEY_PASSWORD:?KEY_PASSWORD is required}"
    : "${SIGN_WITH:?SIGN_WITH is required}"
    : "${GITHUB_SHA:?GITHUB_SHA is required}"
    : "${PRE_RELEASE:?PRE_RELEASE is required}"
    local apk="app/build/outputs/apk/release/numo-${RELEASE_VERSION}-arm64-v8a.apk"
    local pkcs12="$RUNNER_TEMP/zapstore-release-key.p12"
    local zsp="$RUNNER_TEMP/zsp"
    local nak="$RUNNER_TEMP/nak"
    local channel="main"
    local commit="${RELEASE_COMMIT:-$GITHUB_SHA}"

    if [ ! -f "$apk" ]; then
        echo "Zapstore APK not found: $apk" >&2
        exit 1
    fi
    if [ ! -x "$zsp" ] || [ ! -x "$nak" ]; then
        echo "Zapstore publishing tools are not installed in $RUNNER_TEMP" >&2
        exit 1
    fi

    if ! keytool -importkeystore -srckeystore "$KEYSTORE_PATH" -srcstoretype JKS \
        -srcstorepass "$KEYSTORE_PASSWORD" -srckeypass "$KEY_PASSWORD" \
        -srcalias "$KEY_ALIAS" -destkeystore "$pkcs12" -deststoretype PKCS12 \
        -deststorepass "$KEYSTORE_PASSWORD" -destkeypass "$KEYSTORE_PASSWORD" \
        -destalias "$KEY_ALIAS" -noprompt; then
        rm -f -- "$pkcs12"
        keytool -importkeystore -srckeystore "$KEYSTORE_PATH" -srcstoretype PKCS12 \
            -srcstorepass "$KEYSTORE_PASSWORD" -srckeypass "$KEY_PASSWORD" \
            -srcalias "$KEY_ALIAS" -destkeystore "$pkcs12" -deststoretype PKCS12 \
            -deststorepass "$KEYSTORE_PASSWORD" -destkeypass "$KEYSTORE_PASSWORD" \
            -destalias "$KEY_ALIAS" -noprompt
    fi

    "$zsp" identity --link-key "$pkcs12" --link-key-expiry 2y --offline --no-color \
        | "$nak" event --github wss://relay.primal.net wss://relay.damus.io wss://relay.zapstore.dev
    if [ "$PRE_RELEASE" = true ]; then
        channel="beta"
    fi
    "$zsp" publish --quiet --skip-preview --no-color --commit "$commit" \
        --channel "$channel" release/zapstore.yaml
}

if [ "$#" -ne 1 ]; then
    echo "Usage: $0 <decode-keystore|configure-signing|enable-debug-symbols|build-apk|build-bundle|rename-artifacts|create-manifest|download-release-apk|install-zsp|publish-zapstore>" >&2
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
    download-release-apk) download_release_apk ;;
    install-zsp) install_zsp ;;
    publish-zapstore) publish_zapstore ;;
    *)
        echo "Unknown release command: $1" >&2
        exit 1
        ;;
esac
