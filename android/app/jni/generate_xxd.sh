#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/../../.." && pwd)"
mkdir -p "$repo_root/xxd/assets" "$repo_root/xxd/shader"

emit() {
  local source="$1"
  local output="$2"
  local symbol="$3"
  xxd -i -n "$symbol" "$source" > "$output"
}

emit "$repo_root/assets/icon.png" "$repo_root/xxd/assets/icon.png.xxd" ___assets_icon_png
emit "$repo_root/assets/gamecontrollerdb.txt" "$repo_root/xxd/assets/gamecontrollerdb.txt.xxd" ___assets_gamecontrollerdb_txt
emit "$repo_root/assets/liberation.ttf" "$repo_root/xxd/assets/liberation.ttf.xxd" ___assets_liberation_ttf

shaders=(
  common.h transSimple.frag trans.frag hue.frag sprite.frag plane.frag
  gray.frag bitmapBlit.frag flatColor.frag simple.frag simpleColor.frag
  simpleAlpha.frag simpleAlphaUni.frag tilemap.frag flashMap.frag
  bicubic.frag lanczos3.frag minimal.vert simple.vert simpleColor.vert
  sprite.vert tilemap.vert tilemapvx.vert blur.frag blurH.vert blurV.vert
  simpleMatrix.vert kglInvert.frag kglCompressAlpha.frag kglSubtract.frag
  kglShadowH.frag kglShadowV.frag xbrz.frag
)

for shader in "${shaders[@]}"; do
  symbol="___shader_${shader//./_}"
  emit "$repo_root/shader/$shader" "$repo_root/xxd/shader/$shader.xxd" "$symbol"
done
