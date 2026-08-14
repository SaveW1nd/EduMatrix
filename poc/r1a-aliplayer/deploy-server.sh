#!/usr/bin/env bash
#
# R1a-Ali POC —— 部署（Ubuntu 24.04 / 阿里云轻量应用服务器）
#
# 挂在 https://poc.hqtw.cn/ 根路径。基线 POC（hls.js/DPlayer/xgplayer）已删除，
# 需要时从 git 标签 poc-r1a-hlsjs-baseline 取回。
#
# 前置：机器上已有 node 与 Caddy（证书也已签好）。
#
# 用法： sudo ./deploy-server.sh
# 可覆盖： DOMAIN=poc.hqtw.cn  PORT=8081
#
# AccessKey 不由本脚本设置。mode=vid 那条路需要你自己填（见末尾提示）。
#
set -Eeuo pipefail

DOMAIN="${DOMAIN:-poc.hqtw.cn}"
PORT="${PORT:-8081}"
SERVICE="r1a-ali"
APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CADDYFILE="/etc/caddy/Caddyfile"

STEP=0
step() { STEP=$((STEP+1)); echo; echo "════ 步骤 $STEP · $* ════"; }
say()  { echo "    $*"; }
ok()   { echo "    [OK] $*"; }
skip() { echo "    [跳过] $*"; }
warn() { echo "    [注意] $*" >&2; }
die()  { echo; echo "!!!! 卡在步骤 $STEP：$*" >&2; exit 1; }
trap 'echo; echo "!!!! 脚本在步骤 $STEP 处失败。修掉后重跑即可，它是幂等的。" >&2' ERR

if [ "$(id -u)" -ne 0 ]; then SUDO="sudo"; command -v sudo >/dev/null || die "需要 root 或 sudo"; else SUDO=""; fi

echo "R1a-Ali 部署"
echo "  域名      https://$DOMAIN/"
echo "  应用目录  $APP_DIR"
echo "  端口      $PORT"

# ---------------------------------------------------------------------------
step "前置检查"
# ---------------------------------------------------------------------------
command -v node >/dev/null || die "没有 node，先装：apt-get install -y nodejs"
command -v caddy >/dev/null || die "没有 caddy，见 README"
ok "node $(node -v)  caddy 已装"

[ -f "$APP_DIR/vendor/aliplayer-min.js" ] || warn "vendor/aliplayer-min.js 不在，下一步会下载"

# ---------------------------------------------------------------------------
step "vendor/ 阿里云播放器"
# ---------------------------------------------------------------------------
# 必须本地托管：微信里 CDN 偶发被拦，页面会报 "Aliplayer 没加载到"，
# 在手机上它长得和「①播不了」一模一样 —— 基线那轮已经吃过这个亏。
mkdir -p "$APP_DIR/vendor"
ALI_BASE="https://g.alicdn.com/apsara-media-box/imp-web-player/2.25.1"
fetch() {
  local name="$1" url="$2" dest="$APP_DIR/vendor/$1"
  if [ -s "$dest" ] && [ "$(stat -c%s "$dest")" -gt 1000 ]; then skip "$name 已存在"; return; fi
  say "下载 $name ..."
  curl -fsSL -o "$dest.tmp" "$url" || die "下载失败：$url"
  mv "$dest.tmp" "$dest"; ok "$name（$(stat -c%s "$dest") 字节）"
}
fetch aliplayer-min.js  "$ALI_BASE/aliplayer-min.js"
fetch aliplayer-min.css "$ALI_BASE/skins/default/aliplayer-min.css"

# 皮肤图片也必须本地化：aliplayer 的 CSS 以 ./img/*.png 引用它们，
# 不托管的话播放/暂停/全屏/音量图标在微信里全部 404 —— 而 ④ 要靠拖那条进度条来测。
mkdir -p "$APP_DIR/vendor/img"
for f in bigplay.png cc.png dragcursorhover.png fullscreen.png pauseanimation.png \
         playanimation.png setting.png smallpause.png smallplay.png smallscreen.png \
         snapshot.png unmutevolume.png volume.png volumehover.png volumemute.png volumemutehover.png; do
  dest="$APP_DIR/vendor/img/$f"
  [ -s "$dest" ] && continue
  curl -fsSL -o "$dest.tmp" "$ALI_BASE/skins/default/img/$f" && mv "$dest.tmp" "$dest" \
    || warn "皮肤图片 $f 下载失败（控件图标会缺一个，不影响判定）"
done
ok "皮肤图片 $(ls -1 "$APP_DIR/vendor/img" | wc -l | tr -d ' ')/16"

# ---------------------------------------------------------------------------
step "systemd 服务 $SERVICE"
# ---------------------------------------------------------------------------
APP_USER="$(stat -c '%U' "$APP_DIR")"
if ! id -u "$APP_USER" >/dev/null 2>&1; then
  warn "$APP_DIR 属主解析不出来（rsync -a 带过来的 UID），chown 成 root"
  $SUDO chown -R root:root "$APP_DIR"; APP_USER=root
fi
NODE_BIN="$(command -v node)"
UNIT="/etc/systemd/system/${SERVICE}.service"

# EnvironmentFile 是可选的：没有它服务照常起，只是 mode=vid 那条路不可用
NEW_UNIT="$(cat <<EOF
[Unit]
Description=R1a-Ali POC —— 阿里云播放器验证（一次性，测完请卸载）
After=network.target

[Service]
Type=simple
User=$APP_USER
WorkingDirectory=$APP_DIR
Environment=PORT=$PORT
EnvironmentFile=-$APP_DIR/.env
ExecStart=$NODE_BIN $APP_DIR/server.js
Restart=on-failure
RestartSec=2

[Install]
WantedBy=multi-user.target
EOF
)"

if [ -f "$UNIT" ] && [ "$($SUDO cat "$UNIT")" = "$NEW_UNIT" ]; then
  skip "unit 已一致"
else
  printf '%s\n' "$NEW_UNIT" | $SUDO tee "$UNIT" >/dev/null
  $SUDO systemctl daemon-reload
  ok "已写入 $UNIT"
fi
$SUDO systemctl enable --quiet "$SERVICE"
$SUDO systemctl reset-failed "$SERVICE" 2>/dev/null || true
$SUDO systemctl restart "$SERVICE"
sleep 1
$SUDO systemctl is-active --quiet "$SERVICE" || die "服务没起来： journalctl -u $SERVICE -n 50 --no-pager"
ok "$SERVICE 已运行"

# ---------------------------------------------------------------------------
step "Caddy 反代到 $PORT"
# ---------------------------------------------------------------------------
NEW_CADDY="$(cat <<EOF
# R1a-Ali POC —— 一次性验证站，测完请按 README 清掉
$DOMAIN {
	reverse_proxy localhost:$PORT
}
EOF
)"

if [ -f "$CADDYFILE" ] && [ "$($SUDO cat "$CADDYFILE")" = "$NEW_CADDY" ]; then
  skip "Caddyfile 已一致"
else
  if [ -f "$CADDYFILE" ]; then
    BAK="${CADDYFILE}.bak.$(date +%Y%m%d%H%M%S)"
    $SUDO cp -a "$CADDYFILE" "$BAK"
    warn "已备份原 Caddyfile 到 $BAK"
  fi
  printf '%s\n' "$NEW_CADDY" | $SUDO tee "$CADDYFILE" >/dev/null
  $SUDO caddy validate --config "$CADDYFILE" --adapter caddyfile >/dev/null 2>&1 \
    || die "Caddyfile 校验不过（原配置已备份，可回滚）"
  $SUDO systemctl reload caddy
  ok "已写入并 reload"
fi

# ---------------------------------------------------------------------------
step "自检"
# ---------------------------------------------------------------------------
T=(--connect-timeout 5 --max-time 10)
curl -fsS "${T[@]}" -o /dev/null "http://127.0.0.1:$PORT/health" && ok "本机 /health 通" \
  || die "后端不通： journalctl -u $SERVICE -n 50 --no-pager"
for i in 1 2 3 4 5 6; do
  curl -fsS "${T[@]}" -o /dev/null "https://$DOMAIN/" 2>/dev/null && { ok "https://$DOMAIN/ 通"; break; }
  [ "$i" = 6 ] && warn "https://$DOMAIN/ 还不通，看 journalctl -u caddy -n 30 --no-pager"
  sleep 3
done
trap - ERR
echo
echo "════ 完成 ════"
echo
echo "  主用法（阿里云私有加密）："
echo "      https://$DOMAIN/?mode=vid&vid=你的VideoId"
echo
echo "  vid 模式（私有加密）还需要两步，都在你这边："
echo "    1) 服务器上写 $APP_DIR/.env （chmod 600），内容："
echo "         ALIYUN_ACCESS_KEY_ID=你的AK"
echo "         ALIYUN_ACCESS_KEY_SECRET=你的SK"
echo "         ALIYUN_VOD_REGION=cn-shenzhen（按你的点播区域填）"
echo "       然后： systemctl restart $SERVICE"
echo "    2) 用 VideoId 打开： https://$DOMAIN/?mode=vid&vid=你的VideoId"
echo
echo "  日志： https://$DOMAIN/admin/logs   或   journalctl -u $SERVICE -f"
echo
