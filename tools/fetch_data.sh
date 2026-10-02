#!/usr/bin/env bash
#
# 取回上游数据并编译成两个词典资源：
#   app/assets/pinyin.dict   拼音 → 中文 + 英文释义（CC-CEDICT + jieba 词频）
#   app/assets/assoc.idx     中文前缀索引，用于联想（同一批数据）
#
# 用法：
#   bash tools/fetch_data.sh                 # 数据缓存在 ~/.cache/biime-data
#   bash tools/fetch_data.sh /path/to/cache  # 指定缓存目录
#   BIIME_DATA_DIR=/data/dict bash tools/fetch_data.sh
#
# 数据已经下载过就直接复用；下载失败会自动换源重试。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CACHE="${1:-${BIIME_DATA_DIR:-$HOME/.cache/biime-data}}"
CEDICT_GZ="$CACHE/cedict.txt.gz"
CEDICT="$CACHE/cedict.txt"
JIEBA="$CACHE/jieba_dict.txt"

CEDICT_URL="https://www.mdbg.net/chinese/export/cedict/cedict_1_0_ts_utf-8_mdbg.txt.gz"
JIEBA_URLS=(
  "https://cdn.jsdelivr.net/gh/fxsjy/jieba@0.42.1/jieba/dict.txt"
  "https://cdn.jsdelivr.net/gh/fxsjy/jieba@master/jieba/dict.txt"
  "https://gitee.com/mirrors/jieba/raw/master/jieba/dict.txt"
  "https://raw.githubusercontent.com/fxsjy/jieba/master/jieba/dict.txt"
)

command -v python3 >/dev/null || { echo "需要 python3"; exit 1; }
command -v curl    >/dev/null || { echo "需要 curl";    exit 1; }

mkdir -p "$CACHE"

bytes() { wc -c < "$1" 2>/dev/null | tr -d ' ' || echo 0; }

# fetch <url> <输出> <最少字节> [1=断点续传]
fetch() {
  local url="$1" out="$2" min="$3" resume="${4:-0}" i
  for i in 1 2 3; do
    if [ "$resume" = 1 ]; then
      if curl -L -C - --retry 5 --retry-delay 2 --retry-all-errors \
              --connect-timeout 20 --max-time 1800 -o "$out" "$url" \
         && [ "$(bytes "$out")" -ge "$min" ]; then
        return 0
      fi
    else
      if curl -L -sS --retry 5 --retry-delay 2 --retry-all-errors \
              --connect-timeout 20 --max-time 600 -o "$out" "$url" \
         && [ "$(bytes "$out")" -ge "$min" ]; then
        return 0
      fi
    fi
    echo "    第 $i 次没拿到，3 秒后重试"
    sleep 3
  done
  return 1
}

if [ ! -s "$CEDICT" ]; then
  echo "==> 下载 CC-CEDICT（约 3.9 MB；mdbg.net 有时很慢，会自动续传）"
  echo "    源：$CEDICT_URL"
  if fetch "$CEDICT_URL" "$CEDICT_GZ" 1000000 1; then
    echo "    解压…"
    gzip -dc "$CEDICT_GZ" > "$CEDICT"
  fi
fi

if [ ! -s "$JIEBA" ]; then
  echo "==> 下载 jieba 词频表（约 4.8 MB）"
  for u in "${JIEBA_URLS[@]}"; do
    echo "    源：$u"
    if fetch "$u" "$JIEBA" 1000000; then
      break
    fi
  done
fi

if [ ! -s "$CEDICT" ]; then
  echo "拿不到 CC-CEDICT。手动下载后放到：$CEDICT"
  echo "   $CEDICT_URL"
  exit 1
fi
if [ ! -s "$JIEBA" ]; then
  echo "拿不到 jieba 词频表。手动下载后放到：$JIEBA"
  echo "   ${JIEBA_URLS[0]}"
  exit 1
fi

echo "==> 编译词典资源（约 2 秒）"
python3 "$ROOT/tools/build_dict.py" \
  --cedict "$CEDICT" \
  --freq   "$JIEBA" \
  --out    "$ROOT/app/assets/pinyin.dict" \
  --assoc  "$ROOT/app/assets/assoc.idx"

ls -la "$ROOT/app/assets/"
echo "词典资源就绪，接着跑：bash build.sh"
