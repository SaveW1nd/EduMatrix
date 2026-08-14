#!/usr/bin/env bash
#
# R1a POC —— 用 ffmpeg 切一段 AES-128 加密 HLS
#
# 这个脚本唯一的产出物是 media/index.m3u8 及其分片。它里面最要紧的一行不是
# ffmpeg 命令，而是写进 enc.keyinfo 第一行的那个 key URI：
#
#     https://<你的域名>/key?token=TESTTOKEN123
#
# ffmpeg 会把这一行【原样】抄进 m3u8 的 #EXT-X-KEY:URI="..."。真实链路里这个
# 地址是转码时写死的，发起请求的是 hls.js 内核 —— 它不会带任何自定义请求头，
# 所以身份只能挂在 URL 参数上（契约 §1 的 MtsHlsUriToken 就是干这个的）。
#
# 验证项 ② 验的就是：这个 ?token= 能不能活着到达服务端。
#
# 用法：
#   ./make-hls.sh https://poc.example.com                 # 自动生成 30s 测试片
#   ./make-hls.sh https://poc.example.com ./myvideo.mp4   # 用自己的素材
#   TOKEN=ABC123 ./make-hls.sh https://poc.example.com    # 换个 token 值
#
set -euo pipefail

BASE_URL="${1:-}"
INPUT="${2:-}"
TOKEN="${TOKEN:-TESTTOKEN123}"
OUT_DIR="${OUT_DIR:-media}"
DURATION="${DURATION:-30}"

if [ -z "$BASE_URL" ]; then
  echo "用法: $0 <BASE_URL> [输入视频]" >&2
  echo "  例: $0 https://poc.example.com" >&2
  echo "  BASE_URL 必须是真机能访问到的完整前缀，不带结尾斜杠。" >&2
  exit 1
fi

BASE_URL="${BASE_URL%/}"

command -v ffmpeg >/dev/null 2>&1 || { echo "缺 ffmpeg：brew install ffmpeg" >&2; exit 1; }
command -v openssl >/dev/null 2>&1 || { echo "缺 openssl" >&2; exit 1; }

mkdir -p "$OUT_DIR"
rm -f "$OUT_DIR"/*.ts "$OUT_DIR"/*.m3u8 2>/dev/null || true

# ---------------------------------------------------------------------------
# 1. 素材：没给输入就自己生成一段有画面变化的测试片
#    要有变化，否则看不出跑马灯水印是不是真的盖在画面上、拖拽有没有真的动
# ---------------------------------------------------------------------------
GENERATED=""
if [ -z "$INPUT" ]; then
  INPUT="$OUT_DIR/_source.mp4"
  GENERATED="1"
  echo "==> 未提供素材，生成 ${DURATION}s 测试片"

  # 用 testsrc 而不是 testsrc2：它自带一个大号七段数码管秒数计数器，
  # 拖拽有没有真的跳过去、水印有没有真的盖在画面上，看一眼就知道。
  # （不用 drawtext —— 它依赖 libfreetype，homebrew 的默认 ffmpeg 没编。）
  ffmpeg -hide_banner -loglevel error -y \
    -f lavfi -i "testsrc=size=1280x720:rate=25:duration=$DURATION" \
    -f lavfi -i "sine=frequency=440:duration=$DURATION" \
    -c:v libx264 -preset veryfast -pix_fmt yuv420p -g 50 \
    -c:a aac -b:a 64k -shortest \
    "$INPUT"
else
  [ -f "$INPUT" ] || { echo "输入文件不存在: $INPUT" >&2; exit 1; }
fi

# ---------------------------------------------------------------------------
# 2. 密钥与 keyinfo
#
#    enc.keyinfo 的三行含义（顺序不能错）：
#      第 1 行  写进 m3u8 的 key URI —— 播放器按这个地址去取密钥【验证项 ②】
#      第 2 行  ffmpeg 本地读取密钥的文件路径 —— 只有切片时用，不进 m3u8
#      第 3 行  IV（十六进制，可选；写了会进 m3u8 的 IV= 字段）
# ---------------------------------------------------------------------------
KEY_URI="$BASE_URL/key?token=$TOKEN"
KEY_FILE="$OUT_DIR/enc.key"
KEY_INFO="$OUT_DIR/enc.keyinfo"

openssl rand 16 > "$KEY_FILE"
IV=$(openssl rand -hex 16)

{
  echo "$KEY_URI"
  echo "$KEY_FILE"
  echo "$IV"
} > "$KEY_INFO"

echo "==> key URI（这一行就是验证项 ② 的全部）："
echo "    $KEY_URI"

# ---------------------------------------------------------------------------
# 3. 切片
# ---------------------------------------------------------------------------
echo "==> 切 AES-128 加密 HLS"
ffmpeg -hide_banner -loglevel error -y \
  -i "$INPUT" \
  -c:v libx264 -preset veryfast -profile:v main -pix_fmt yuv420p \
  -g 50 -keyint_min 50 -sc_threshold 0 \
  -c:a aac -b:a 64k -ac 2 \
  -hls_time 4 \
  -hls_playlist_type vod \
  -hls_key_info_file "$KEY_INFO" \
  -hls_segment_filename "$OUT_DIR/seg_%03d.ts" \
  "$OUT_DIR/index.m3u8"

# ---------------------------------------------------------------------------
# 4. 自检 —— 三条都过才算切对了
# ---------------------------------------------------------------------------
echo
echo "==> 自检"

M3U8="$OUT_DIR/index.m3u8"
FAIL=""

if grep -q '#EXT-X-KEY:METHOD=AES-128' "$M3U8"; then
  echo "    [OK] m3u8 里有 #EXT-X-KEY:METHOD=AES-128"
else
  echo "    [!!] m3u8 里【没有】AES-128 —— 切出来的是不加密流，这不是要验的东西"
  FAIL="1"
fi

if grep -q "token=$TOKEN" "$M3U8"; then
  echo "    [OK] key URI 上的 ?token=$TOKEN 已写进 m3u8"
else
  echo "    [!!] key URI 上的 token 参数丢了 —— 验证项 ② 无从验起"
  FAIL="1"
fi

SEG_COUNT=$(ls -1 "$OUT_DIR"/*.ts 2>/dev/null | wc -l | tr -d ' ')
if [ "$SEG_COUNT" -gt 0 ]; then
  echo "    [OK] 生成 $SEG_COUNT 个分片"
  # 加密的 TS 分片开头不会是 0x47（TS 同步字节）。这条能挡住"以为加密了其实没有"。
  FIRST_BYTE=$(head -c 1 "$(ls -1 "$OUT_DIR"/*.ts | head -1)" | od -An -tx1 | tr -d ' \n')
  if [ "$FIRST_BYTE" = "47" ]; then
    echo "    [!!] 首个分片第一字节是 0x47（明文 TS 同步字节）—— 分片没被加密"
    FAIL="1"
  else
    echo "    [OK] 分片首字节 0x$FIRST_BYTE ≠ 0x47，确为密文"
  fi
else
  echo "    [!!] 没生成任何分片"
  FAIL="1"
fi

[ -n "$GENERATED" ] && rm -f "$INPUT"

echo
echo "==> m3u8 头部："
head -n 6 "$M3U8" | sed 's/^/    /'
echo

if [ -n "$FAIL" ]; then
  echo "自检未全过，先解决上面的 [!!] 再上真机 —— 带着一个切错的流去测，测出来的结论是假的。" >&2
  exit 1
fi

echo "完成。产物在 $OUT_DIR/，密钥在 $KEY_FILE（server.js 会读它）。"
echo "下一步： node server.js   然后手机访问 $BASE_URL/"
