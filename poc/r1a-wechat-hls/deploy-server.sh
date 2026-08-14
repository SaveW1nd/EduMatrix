#!/usr/bin/env bash
#
# R1a POC —— 服务器部署脚本（Ubuntu 24.04 / 阿里云轻量应用服务器）
#
# 目标：把这个目录变成 https://poc.hqtw.cn 上一个能用手机扫码逐条判定的验证站。
#
# 六步，每步先检测再执行，可重复运行：
#   1 依赖（ffmpeg / node / curl / git）
#   2 Caddy（官方 apt 源，自动签 HTTPS 证书）
#   3 /etc/caddy/Caddyfile（已存在且不同则备份，不静默覆盖）
#   4 vendor/ 本地播放器库（微信里 CDN 偶发被拦，会伪装成「①不成立」）
#   5 切加密 HLS（key URI 必须写成最终域名，否则手机取不到密钥）
#   6 systemd 服务（不用 nohup —— SSH 一断就没了，而真机测试要反复来回）
#
# 用法（在服务器上，本目录内）：
#   sudo ./deploy-server.sh
#
# 可覆盖的变量：
#   DOMAIN=poc.hqtw.cn  PORT=8080  TOKEN=TESTTOKEN123  FORCE_RECUT=1
#
# 纪律：只装这个 POC 需要的东西。不装 Docker / MySQL / Redis，不改防火墙，
#       不动系统配置。这台机器后面要跑生产，POC 不该在上面留任何多余的东西。
#       测完的收尾命令见 README §七。
#
set -Eeuo pipefail

DOMAIN="${DOMAIN:-poc.hqtw.cn}"
PORT="${PORT:-8080}"
TOKEN="${TOKEN:-TESTTOKEN123}"
SERVICE="r1a-poc"
APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CADDYFILE="/etc/caddy/Caddyfile"
NODE_MIN_MAJOR=18     # server.js 实测在 Node 18.20.8 上全端点通过；它只用 http/fs/path/url

STEP=0
step()  { STEP=$((STEP+1)); echo; echo "════ 步骤 $STEP · $* ════"; }
say()   { echo "    $*"; }
ok()    { echo "    [OK] $*"; }
skip()  { echo "    [跳过] $*"; }
warn()  { echo "    [注意] $*" >&2; }
die()   { echo; echo "!!!! 卡在步骤 $STEP：$*" >&2; exit 1; }

trap 'echo; echo "!!!! 脚本在步骤 $STEP 处失败（上一条命令返回非 0）。修掉后重跑本脚本即可，它是幂等的。" >&2' ERR

if [ "$(id -u)" -ne 0 ]; then
  SUDO="sudo"
  command -v sudo >/dev/null 2>&1 || die "非 root 且没有 sudo，请用 root 运行"
else
  SUDO=""
fi

echo "R1a POC 部署"
echo "  域名      $DOMAIN"
echo "  应用目录  $APP_DIR"
echo "  后端端口  $PORT（只监听本机，对外由 Caddy 反代）"

# ---------------------------------------------------------------------------
step "依赖：ffmpeg / node / curl / git"
# ---------------------------------------------------------------------------
APT_UPDATED=0
apt_update_once() {
  if [ "$APT_UPDATED" -eq 0 ]; then
    say "apt-get update ..."
    $SUDO apt-get update -qq
    APT_UPDATED=1
  fi
}

for pkg in ffmpeg curl git ca-certificates; do
  if dpkg -s "$pkg" >/dev/null 2>&1; then
    skip "$pkg 已安装"
  else
    apt_update_once
    say "安装 $pkg ..."
    $SUDO apt-get install -y -qq "$pkg"
    ok "$pkg"
  fi
done

# Node：先看现成的够不够，不够才动 NodeSource。
# Ubuntu 24.04 的 apt nodejs 是 18.19.1，server.js 在 18 上实测全通过，所以通常走 apt。
node_major() { node -p 'process.versions.node.split(".")[0]' 2>/dev/null || echo 0; }

if command -v node >/dev/null 2>&1 && [ "$(node_major)" -ge "$NODE_MIN_MAJOR" ]; then
  skip "node $(node -v) 已满足 >= v$NODE_MIN_MAJOR"
else
  apt_update_once
  say "安装 apt 源里的 nodejs ..."
  $SUDO apt-get install -y -qq nodejs || true

  if command -v node >/dev/null 2>&1 && [ "$(node_major)" -ge "$NODE_MIN_MAJOR" ]; then
    ok "node $(node -v)（来自 Ubuntu apt）"
  else
    warn "apt 的 node 缺失或低于 v$NODE_MIN_MAJOR（当前：$(command -v node >/dev/null 2>&1 && node -v || echo '未安装')），改用 NodeSource"
    curl -fsSL https://deb.nodesource.com/setup_20.x | $SUDO -E bash - \
      || die "NodeSource 脚本执行失败（国内网络访问 deb.nodesource.com 可能不通）"
    $SUDO apt-get install -y -qq nodejs || die "NodeSource nodejs 安装失败"
    ok "node $(node -v)（来自 NodeSource）"
  fi
fi

NODE_BIN="$(command -v node)"
command -v ffmpeg >/dev/null 2>&1 || die "ffmpeg 装了但不在 PATH 里"
ok "ffmpeg $(ffmpeg -version 2>/dev/null | head -1 | awk '{print $3}')"

# 只提示、不改动 —— 改防火墙不在这个脚本的职责范围内
if command -v ufw >/dev/null 2>&1 && $SUDO ufw status 2>/dev/null | head -1 | grep -qi active; then
  warn "系统里的 ufw 处于 active。它和阿里云控制台的防火墙是两道独立的门，两道都得放行 80/443。"
fi

# ---------------------------------------------------------------------------
step "Caddy（自动 HTTPS）"
# ---------------------------------------------------------------------------
install_caddy_from_github() {
  # 兜底：dl.cloudsmith.io 在国内偶有不通
  local arch tag url tmp
  case "$(dpkg --print-architecture)" in
    amd64) arch=amd64 ;;
    arm64) arch=arm64 ;;
    *) die "未知架构 $(dpkg --print-architecture)，请手动装 Caddy" ;;
  esac
  say "改从 GitHub Releases 取 .deb ..."
  tag="$(curl -fsSL https://api.github.com/repos/caddyserver/caddy/releases/latest \
        | grep -m1 '"tag_name"' | cut -d'"' -f4)" || die "取不到 Caddy 最新版本号"
  tag="${tag#v}"
  url="https://github.com/caddyserver/caddy/releases/download/v${tag}/caddy_${tag}_linux_${arch}.deb"
  tmp="$(mktemp -d)"
  curl -fsSL -o "$tmp/caddy.deb" "$url" || die "下载 $url 失败"
  $SUDO dpkg -i "$tmp/caddy.deb" || die "dpkg 安装 Caddy 失败"
  rm -rf "$tmp"
}

if command -v caddy >/dev/null 2>&1; then
  skip "caddy 已安装（$(caddy version 2>/dev/null | head -1)）"
else
  say "配置 Caddy 官方 apt 源 ..."
  $SUDO apt-get install -y -qq debian-keyring debian-archive-keyring apt-transport-https

  if curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' \
       | $SUDO gpg --dearmor --yes -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg \
     && curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' \
       | $SUDO tee /etc/apt/sources.list.d/caddy-stable.list >/dev/null; then
    $SUDO chmod o+r /usr/share/keyrings/caddy-stable-archive-keyring.gpg
    $SUDO chmod o+r /etc/apt/sources.list.d/caddy-stable.list
    $SUDO apt-get update -qq
    if ! $SUDO apt-get install -y -qq caddy; then
      warn "apt 源装 Caddy 失败，走 GitHub 兜底"
      install_caddy_from_github
    fi
  else
    warn "取不到 cloudsmith 源（国内网络常见），走 GitHub 兜底"
    install_caddy_from_github
  fi
  ok "caddy $(caddy version 2>/dev/null | head -1)"
fi

# ---------------------------------------------------------------------------
step "写 $CADDYFILE"
# ---------------------------------------------------------------------------
# reverse_proxy 会原样带上 query string —— ②验的就是 ?token= 能不能活着到服务端，
# 这里但凡做了重写，验出来的就不是真链路。所以配置刻意保持这么小。
NEW_CADDYFILE="$(cat <<EOF
# R1a POC —— 一次性验证站，测完请按 README §七 清掉
$DOMAIN {
	reverse_proxy localhost:$PORT
}
EOF
)"

$SUDO mkdir -p "$(dirname "$CADDYFILE")"
if [ -f "$CADDYFILE" ] && [ "$($SUDO cat "$CADDYFILE")" = "$NEW_CADDYFILE" ]; then
  skip "Caddyfile 内容已一致"
else
  if [ -f "$CADDYFILE" ]; then
    BAK="${CADDYFILE}.bak.$(date +%Y%m%d%H%M%S)"
    $SUDO cp -a "$CADDYFILE" "$BAK"
    warn "已存在的 Caddyfile 内容不同，已备份到 $BAK （没有静默覆盖，请自行确认是否还需要它）"
  fi
  printf '%s\n' "$NEW_CADDYFILE" | $SUDO tee "$CADDYFILE" >/dev/null
  ok "已写入"
fi

say "校验配置 ..."
$SUDO caddy validate --config "$CADDYFILE" --adapter caddyfile >/dev/null 2>&1 \
  || die "Caddyfile 校验不过：$SUDO caddy validate --config $CADDYFILE --adapter caddyfile"

# A 记录没生效就签不到证书，先说清楚，别让人对着 curl 的报错猜
RESOLVED="$(getent hosts "$DOMAIN" 2>/dev/null | awk '{print $1}' | head -1 || true)"
if [ -n "$RESOLVED" ]; then
  say "$DOMAIN 当前解析到 $RESOLVED"
else
  warn "$DOMAIN 解析不出来。A 记录没生效的话，Caddy 签不到证书（ACME HTTP-01 要回访这个域名的 80 端口）。"
fi

if $SUDO systemctl is-active --quiet caddy; then
  $SUDO systemctl reload caddy && ok "caddy 已 reload"
else
  $SUDO systemctl enable --now caddy && ok "caddy 已启动并设为开机自启"
fi

# ---------------------------------------------------------------------------
step "vendor/ 本地播放器库"
# ---------------------------------------------------------------------------
# 为什么必须本地：微信里 CDN 偶发被拦，页面会打 "hls.js 加载失败"，
# 而在手机上它长得和「①加密 HLS 播不了」一模一样 —— 这是最容易得出假结论的地方。
mkdir -p "$APP_DIR/vendor"
fetch_vendor() {
  local name="$1" url="$2" dest="$APP_DIR/vendor/$1"
  if [ -s "$dest" ] && [ "$(stat -c%s "$dest")" -gt 1000 ]; then
    skip "$name 已存在（$(stat -c%s "$dest") 字节）"
    return
  fi
  say "下载 $name ..."
  curl -fsSL -o "$dest.tmp" "$url" || die "下载 $name 失败：$url"
  mv "$dest.tmp" "$dest"     # 先下到 .tmp 再改名：中断了也不会留半个文件让下次跳过
  ok "$name（$(stat -c%s "$dest") 字节）"
}
fetch_vendor hls.min.js     https://cdn.jsdelivr.net/npm/hls.js@1/dist/hls.min.js
fetch_vendor DPlayer.min.js  https://cdn.jsdelivr.net/npm/dplayer@1/dist/DPlayer.min.js
fetch_vendor DPlayer.min.css https://cdn.jsdelivr.net/npm/dplayer@1/dist/DPlayer.min.css

# ---------------------------------------------------------------------------
step "切一段 AES-128 加密 HLS"
# ---------------------------------------------------------------------------
# key URI 是【绝对地址】、被写死进 m3u8。写成 IP 或 localhost，手机上必然取不到密钥，
# 而那个现象和「②参数没活到服务端」长得一模一样。所以这里只能是最终域名。
EXPECT_URI="https://$DOMAIN/key?token=$TOKEN"
M3U8="$APP_DIR/media/index.m3u8"

if [ "${FORCE_RECUT:-0}" != "1" ] && [ -f "$M3U8" ] && grep -q "URI=\"$EXPECT_URI\"" "$M3U8"; then
  skip "media/index.m3u8 已存在且 key URI 正确（$EXPECT_URI）"
  say "要重切就： FORCE_RECUT=1 sudo ./deploy-server.sh"
else
  chmod +x "$APP_DIR/make-hls.sh"
  say "调用 make-hls.sh https://$DOMAIN"
  ( cd "$APP_DIR" && TOKEN="$TOKEN" ./make-hls.sh "https://$DOMAIN" ) \
    || die "make-hls.sh 没跑完（它自己的三条自检没过就会退出，照它的提示改）"
fi

# ---------------------------------------------------------------------------
step "systemd 服务 $SERVICE"
# ---------------------------------------------------------------------------
# 日志目录与密钥都在 APP_DIR 下，服务就用这个目录的属主跑，
# 免得 root 写出一堆 root 属主的日志文件。
APP_USER="$(stat -c '%U' "$APP_DIR")"
APP_GROUP="$(stat -c '%G' "$APP_DIR")"
UNIT="/etc/systemd/system/${SERVICE}.service"

NEW_UNIT="$(cat <<EOF
[Unit]
Description=R1a POC —— 微信内置浏览器加密 HLS 验证（一次性，测完请卸载）
After=network.target

[Service]
Type=simple
User=$APP_USER
Group=$APP_GROUP
WorkingDirectory=$APP_DIR
Environment=PORT=$PORT
ExecStart=$NODE_BIN $APP_DIR/server.js
Restart=on-failure
RestartSec=2

[Install]
WantedBy=multi-user.target
EOF
)"

if [ -f "$UNIT" ] && [ "$($SUDO cat "$UNIT")" = "$NEW_UNIT" ]; then
  skip "unit 文件已一致"
else
  printf '%s\n' "$NEW_UNIT" | $SUDO tee "$UNIT" >/dev/null
  $SUDO systemctl daemon-reload
  ok "已写入 $UNIT 并 daemon-reload"
fi

$SUDO systemctl enable --quiet "$SERVICE"
$SUDO systemctl restart "$SERVICE"
sleep 1
$SUDO systemctl is-active --quiet "$SERVICE" \
  || die "服务没起来，看日志： journalctl -u $SERVICE -n 50 --no-pager"
ok "$SERVICE 已运行（以 $APP_USER 身份）"

# ---------------------------------------------------------------------------
step "自检"
# ---------------------------------------------------------------------------
# 所有自检 curl 都带超时：网络挂住时不能让部署脚本在这里干等十几分钟
CURL_T=(--connect-timeout 5 --max-time 10)

say "① 后端直连 127.0.0.1:$PORT"
curl -fsS "${CURL_T[@]}" -o /dev/null "http://127.0.0.1:$PORT/health" \
  && ok "/health 通" \
  || die "后端不通： journalctl -u $SERVICE -n 50 --no-pager"

say "② HTTPS（首次签证书要等几秒，最多试 12 次，约 1 分钟）"
HTTPS_OK=0
for _ in $(seq 1 12); do
  if curl -fsS "${CURL_T[@]}" -o /dev/null "https://$DOMAIN/health" 2>/dev/null; then HTTPS_OK=1; break; fi
  sleep 5
done
if [ "$HTTPS_OK" -eq 1 ]; then
  ok "https://$DOMAIN 通，证书有效"
else
  warn "https://$DOMAIN 还不通。九成是下面三件事之一，逐条查："
  warn "  1) 阿里云控制台「防火墙」没放行 80 —— ACME HTTP-01 挑战走 80，只开 443 永远签不到证书"
  warn "  2) $DOMAIN 的 A 记录还没生效（当前解析：${RESOLVED:-解析不出来}，应为本机公网 IP）"
  warn "  3) Caddy 自己的报错： journalctl -u caddy -n 50 --no-pager"
fi

say "③ m3u8 里的 key URI（②能不能验，全看这一行）"
KEYLINE="$(curl -fsS "${CURL_T[@]}" "https://$DOMAIN/media/index.m3u8" 2>/dev/null | grep '^#EXT-X-KEY' || true)"
if [ -z "$KEYLINE" ]; then
  KEYLINE="$(curl -fsS "${CURL_T[@]}" "http://127.0.0.1:$PORT/media/index.m3u8" | grep '^#EXT-X-KEY' || true)"
  [ -n "$KEYLINE" ] && warn "（走的本机直连，因为 HTTPS 还不通）"
fi
if [ -n "$KEYLINE" ]; then
  say "$KEYLINE"
  case "$KEYLINE" in
    *"URI=\"$EXPECT_URI\""*) ok "key URI 正确：$EXPECT_URI" ;;
    *) die "key URI 不是 $EXPECT_URI —— 手机上取不到密钥，且现象和「②不成立」一样。用 FORCE_RECUT=1 重跑本脚本" ;;
  esac
else
  die "读不到 m3u8"
fi

trap - ERR
echo
echo "════ 完成 ════"
echo
echo "  手机在【微信里】扫这个地址的二维码："
echo "      https://$DOMAIN/"
echo
echo "  电脑上看手机打回来的日志（微信里没有开发者工具，这是唯一的观察窗口）："
echo "      https://$DOMAIN/admin/logs"
echo "      journalctl -u $SERVICE -f"
echo
echo "  三轮测试的 URL 与四条判定口径见 README §三。"
echo "  测完的收尾（这台机器要跑生产，别留垃圾）见 README §七。"
echo
