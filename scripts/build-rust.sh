#!/usr/bin/env bash
set -euo pipefail
test "${GITHUB_ACTIONS:-}" = true
root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"
mode="${1:-host}"
export RUSTFLAGS="-C relocation-model=pic"
if [[ "$mode" == host ]]; then
  cargo build --manifest-path rust-core/Cargo.toml --release --locked
  mkdir -p android/native-libs/host
  cc -std=c11 -O2 -fPIC -shared -Wall -Wextra -Wno-unused-parameter \
    -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/linux" rust-core/jni/bridge.c \
    rust-core/target/release/libaction_relay_core.a -ldl -lpthread -lm \
    -o android/native-libs/host/libaction_relay_core.so
elif [[ "$mode" == android ]]; then
  ndk="$ANDROID_HOME/ndk/30.0.16248370/toolchains/llvm/prebuilt/linux-x86_64/bin"
  export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$ndk/aarch64-linux-android31-clang"
  cargo build --manifest-path rust-core/Cargo.toml --release --locked --target aarch64-linux-android
  mkdir -p android/native-libs/arm64-v8a
  "$ndk/aarch64-linux-android31-clang" -std=c11 -O2 -fPIC -shared -Wall -Wextra -Wno-unused-parameter \
    -Wl,-z,max-page-size=16384 -Wl,--gc-sections -Wl,--exclude-libs,ALL \
    rust-core/jni/bridge.c rust-core/target/aarch64-linux-android/release/libaction_relay_core.a \
    -ldl -llog -lunwind -lm -o android/native-libs/arm64-v8a/libaction_relay_core.so
  "$ndk/llvm-strip" --strip-unneeded android/native-libs/arm64-v8a/libaction_relay_core.so
else
  echo "Unknown native build mode" >&2; exit 2
fi
