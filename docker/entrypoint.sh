#!/usr/bin/env bash
# Entrypoint of the Glashutte build image. Driven by the Makefile.
#
# Mounts expected:
#   /src          the repository (read-only)
#   /work         scratch build tree (named volume; keeps Gradle outputs between runs)
#   /cache        Gradle + Android caches (named volume)
#   /out          where finished APKs are copied
#   /viture-sdk   OPTIONAL, read-only: the locally supplied VITURE SDK
set -euo pipefail

cmd="${1:-help}"
[ "$#" -gt 0 ] && shift

SRC=/src
APP=/work/glashutte/Android

usage() {
    cat <<USAGE
glashutte-build <command> [args]
  apk-debug | apk-release | apk-all   build APKs and copy them to /out
  test                                run unit tests
  gradle <args...>                    run an arbitrary Gradle command
  shell                               interactive bash in the synced build tree
USAGE
}

sync_tree() {
    [ -d "$SRC/Android" ] || { echo "error: $SRC/Android not found (is the repo mounted at $SRC?)" >&2; exit 2; }
    mkdir -p "$APP"
    # Copy sources into a scratch tree so host build directories and a host local.properties
    # never interfere, and so the mounted source tree is never written to. Build outputs in
    # /work are kept between runs for incremental builds.
    rsync -a --delete \
        --exclude='build/' --exclude='.gradle/' --exclude='.kotlin/' \
        --exclude='.cxx/' --exclude='.externalNativeBuild/' \
        --exclude='local.properties' --exclude='/libs/viture-sdk/' \
        "$SRC/Android/" "$APP/"
}

gradle_flags() {
    GRADLE_FLAGS=(--no-daemon --console=plain)
    if [ "${GLASHUTTE_VITURE_SDK:-auto}" = "off" ]; then
        GRADLE_FLAGS+=(-Pviture.sdk=off)
        unset VITURE_SDK_DIR
    elif [ -f /viture-sdk/include/viture_glasses_provider.h ]; then
        export VITURE_SDK_DIR=/viture-sdk
    else
        unset VITURE_SDK_DIR
    fi
    # Extra user flags, e.g. GRADLE_ARGS="--info".
    # shellcheck disable=SC2206
    [ -n "${GRADLE_ARGS:-}" ] && GRADLE_FLAGS+=(${GRADLE_ARGS})
    return 0
}

sdk_tag() {
    # Make it obvious from the file name whether an APK embeds the proprietary SDK libraries.
    if [ -n "${VITURE_SDK_DIR:-}" ]; then echo "with-viture-sdk"; else echo "no-viture-sdk"; fi
}

collect() { # collect <debug|release>
    local variant="$1" dir="$APP/app/build/outputs/apk/$1" found=0 f
    mkdir -p /out
    for f in "$dir"/*.apk; do
        [ -e "$f" ] || continue
        local base
        base="$(basename "$f" .apk | sed 's/^app-//')"
        cp -f "$f" "/out/glashutte-${base}-$(sdk_tag).apk"
        echo "wrote /out/glashutte-${base}-$(sdk_tag).apk"
        found=1
    done
    [ "$found" = 1 ] || { echo "error: no APK found in $dir" >&2; exit 1; }
}

case "$cmd" in
    help|-h|--help) usage ;;
    apk-debug|apk-release|apk-all)
        sync_tree; gradle_flags; cd "$APP"
        tasks=()
        case "$cmd" in
            apk-debug)   tasks=(:app:assembleDebug) ;;
            apk-release) tasks=(:app:assembleRelease) ;;
            apk-all)     tasks=(:app:assembleDebug :app:assembleRelease) ;;
        esac
        bash ./gradlew "${GRADLE_FLAGS[@]}" "${tasks[@]}"
        case "$cmd" in
            apk-debug)   collect debug ;;
            apk-release) collect release ;;
            apk-all)     collect debug; collect release ;;
        esac
        ;;
    test)
        sync_tree; gradle_flags; cd "$APP"
        bash ./gradlew "${GRADLE_FLAGS[@]}" :glasses:testDebugUnitTest
        ;;
    gradle)
        sync_tree; gradle_flags; cd "$APP"
        bash ./gradlew "${GRADLE_FLAGS[@]}" "$@"
        ;;
    shell)
        sync_tree; gradle_flags; cd "$APP"
        exec bash
        ;;
    *) echo "unknown command: $cmd" >&2; usage >&2; exit 2 ;;
esac
