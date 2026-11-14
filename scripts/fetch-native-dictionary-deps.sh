#!/usr/bin/env bash
# Fetches the third-party sources the native dictionary renderer builds against.
#
# dictionary-renderer-core expects them in tools/native-dictionary/.deps, which
# is gitignored because the trees are large. Clean checkouts (CI, fresh clones)
# run this before a build that includes the native renderer.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEPS="$ROOT/tools/native-dictionary/.deps"
mkdir -p "$DEPS"

fetch_git() {
    local name="$1" url="$2" commit="$3"
    local dir="$DEPS/$name"
    if [ -f "$dir/.fetched" ]; then
        echo "== $name already present"
        return
    fi
    echo "== $name @ $commit"
    rm -rf "$dir"
    mkdir -p "$dir"
    git -C "$dir" init -q
    git -C "$dir" remote add origin "$url"
    git -C "$dir" fetch -q --depth 1 origin "$commit"
    git -C "$dir" checkout -q --detach FETCH_HEAD
    rm -rf "$dir/.git"
    touch "$dir/.fetched"
}

# Revisions match the trees documented in tools/README.md.
fetch_git rapidjson https://github.com/Tencent/rapidjson.git 24b5e7a8b27f42fa16b96fc70aade9106cf7102f
fetch_git lexbor https://github.com/lexbor/lexbor.git 145872a2e5d6c22d923afcd3c386a21e59f9a0e2
fetch_git harfbuzz https://github.com/harfbuzz/harfbuzz.git 43a778492219032b9d747112e412dec2201d2667
fetch_git freetype https://github.com/freetype/freetype.git 42608f77f20749dd6ddc9e0536788eaad70ea4b5
fetch_git zlib https://github.com/madler/zlib.git 51b7f2abdade71cd9bb0e7a373ef2610ec6f9daf
fetch_git libpng https://github.com/pnggroup/libpng.git 2b978915d82377df13fcbb1fb56660195ded868a
fetch_git sheenbidi https://github.com/Tehreer/SheenBidi.git 67b6f9d1489fb9222a9f46bba12649d4e7e3bfef
fetch_git libunibreak https://github.com/adah1972/libunibreak.git 32555542453f59101356ecbb8686f5e198f92b62
fetch_git plutovg https://github.com/sammycage/plutovg.git f63f9b59dda96c7d1ca3c76e9b33e5ae4b91fab1

# AGG 2.5 is vendored in dictionary-renderer-core/third_party/agg; the upstream
# 2.5 tarball has the broken scanline32_u8_am base_type typedef.

test -f "$DEPS/rapidjson/include/rapidjson/reader.h"
test -f "$DEPS/lexbor/CMakeLists.txt"
test -f "$DEPS/harfbuzz/CMakeLists.txt"
test -f "$DEPS/freetype/CMakeLists.txt"
test -f "$DEPS/zlib/zlib.h"
test -f "$DEPS/libpng/CMakeLists.txt"
test -f "$DEPS/sheenbidi/Source/SheenBidi.c"
test -f "$DEPS/libunibreak/src/linebreak.c"
test -f "$DEPS/plutovg/CMakeLists.txt"

echo "Native dictionary dependencies are ready in $DEPS"
