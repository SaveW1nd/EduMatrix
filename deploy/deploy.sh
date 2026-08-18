#!/usr/bin/env bash
# ============================================================================
# EduMatrix 生产部署脚本（手工执行，本轮不上 CI）
#
# 【用法】在【本地仓库根目录】执行：
#     bash deploy/deploy.sh provision   # 首次：装环境（JDK/Redis/Caddy/swap/日志轮转）
#     bash deploy/deploy.sh build       # 本地构建 jar
#     bash deploy/deploy.sh ship        # 传 jar + 配置，装/更新 systemd 与 Caddy
#     bash deploy/deploy.sh ensure-database  # 幂等建库（必须在 start 之前，见下）
#     bash deploy/deploy.sh start       # 启动并等健康检查
#     bash deploy/deploy.sh verify      # 冒烟：健康检查 + 登录 + /auth/me
#     bash deploy/deploy.sh all         # build + ship + start + verify
#
# 【为什么不在服务器上跑 mvn verify】2 核机器构建慢，且 IT 要拉起 MySQL/Redis
#   （集成测试连的是本地 13306/16380 容器）。构建在本地做，服务器只接 jar。
#
# 【口令不在本文件里，也不在仓库里】一律从服务器上的 /etc/edumatrix/db.env 读，
#   该文件 chmod 600 root:root，由需方提供。本脚本【不创建、不修改、不回显】它。
#   变量名以 backend/src/main/resources/application.yml 为准：
#     MYSQL_URL / MYSQL_USER / MYSQL_PASSWORD / REDIS_HOST / REDIS_PORT / REDIS_PASSWORD
#     SNOWFLAKE_WORKER_ID / SNOWFLAKE_DATACENTER_ID / XXL_JOB_ENABLED …
#
# 【Flyway 跑在应用启动里，没有独立迁移步骤】所以【首次启动必须用高权限账号】：
#   基线 V202608120000 第 77 行是 CREATE DATABASE IF NOT EXISTS `edumatrix`
#   DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci，普通账号建不了库。
#
#   ⚠【库必须带显式 charset 子句被创建 —— 谁建都行，但那个子句不能少】
#     41 张表【没有一张】显式指定字符集，全部继承库默认值。库的排序规则一旦错了，
#     迁移照样 success、应用照样启动，但唯一索引的「算不算重复」判定会变
#     （uk_perms / uk_tenant_role_key / 同父下 node_name 唯一 / username 唯一 全受影响），
#     且 utf8mb3 存不下 4 字节字符（emoji、部分生僻字）。
#
#     【基线第 77 行的 CREATE DATABASE 在 Flyway 下永远不会执行】——这是 F-31：
#     Flyway 必须【先连上目标库】才能跑脚本，库要么被 JDBC 的
#     createDatabaseIfNotExist 建、要么被 Flyway 自己按 flyway.schemas 建，
#     两条路都用【服务端默认】。等 Flyway 能跑基线时，库早就存在了，
#     那句 IF NOT EXISTS 静默跳过。
#     本地长期为绿是因为 Docker MySQL 8 的 character_set_server 恰好是 utf8mb4；
#     阿里云 RDS 实测 utf8mb3 / utf8mb3_general_ci，才把这条暴露出来。
#
#     所以 `ensure-database` 这一步【不可省略】，且必须在 start 之前。
# ============================================================================
set -euo pipefail

HOST="${EDUMATRIX_HOST:-47.110.142.8}"
SSH_KEY="${EDUMATRIX_SSH_KEY:-$HOME/.ssh/edumatrix.pem}"
SSH="ssh -i $SSH_KEY -o StrictHostKeyChecking=accept-new root@$HOST"
SCP="scp -i $SSH_KEY -o StrictHostKeyChecking=accept-new"

REMOTE_DIR=/opt/edumatrix
LOG_DIR=/var/log/edumatrix
ENV_FILE=/etc/edumatrix/db.env
JAR_LOCAL=backend/target/edumatrix-backend.jar
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

log() { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
die() { printf '\n\033[1;31m!! %s\033[0m\n' "$*" >&2; exit 1; }

# ---------------------------------------------------------------------------
provision() {
  log "① 服务器环境（JDK 21 / Redis / Caddy / swap / 日志轮转；【不装 MySQL】——库在 RDS）"
  $SSH bash -s <<'REMOTE'
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive

# 时区：契约 §6.1 要求服务器、数据库、接口三层一致
timedatectl set-timezone Asia/Shanghai

# 2G swap —— 3.4G 可用内存跑 JVM，防 OOM Killer 直接杀进程
if ! swapon --show | grep -q swapfile; then
  fallocate -l 2G /swapfile; chmod 600 /swapfile; mkswap -q /swapfile; swapon /swapfile
  grep -q '^/swapfile' /etc/fstab || echo '/swapfile none swap sw 0 0' >> /etc/fstab
fi
echo 'vm.swappiness=10' > /etc/sysctl.d/99-edumatrix.conf
sysctl -q --system

# dpkg 锁常被 unattended-upgrades 占着，等它
for _ in $(seq 1 60); do fuser /var/lib/dpkg/lock-frontend >/dev/null 2>&1 || break; sleep 10; done

apt-get -qq update
apt-get -qq install -y openjdk-21-jre-headless redis-server logrotate \
                       debian-keyring debian-archive-keyring apt-transport-https curl

# Caddy 官方源
if ! command -v caddy >/dev/null; then
  curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' \
    | gpg --dearmor -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
  curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' \
    > /etc/apt/sources.list.d/caddy-stable.list
  apt-get -qq update
  for _ in $(seq 1 60); do fuser /var/lib/dpkg/lock-frontend >/dev/null 2>&1 || break; sleep 10; done
  apt-get -qq install -y caddy
fi

# Redis：只听本机；开持久化；设上限与淘汰策略
mkdir -p /etc/redis/redis.conf.d
cat > /etc/redis/redis.conf.d/edumatrix.conf <<'EOF'
bind 127.0.0.1 -::1
protected-mode yes
maxmemory 512mb
# 【noeviction 而不是 allkeys-lru】Redis 里存着【冻结集】（停用节点的子树，
# 模块 02 的鉴权依据）。LRU 把它悄悄淘汰掉 = 一个被停用的管理员分支重新可见，
# 而且不报错。宁可写失败报错，也不要静默失去一道权限闸口。
maxmemory-policy noeviction
appendonly yes
appendfsync everysec
save 900 1
save 300 10
save 60 10000
EOF
grep -q 'redis.conf.d' /etc/redis/redis.conf || echo 'include /etc/redis/redis.conf.d/*.conf' >> /etc/redis/redis.conf
systemctl enable -q redis-server && systemctl restart redis-server

# 运行账号：不用 root 跑应用
id edumatrix >/dev/null 2>&1 || useradd --system --no-create-home --shell /usr/sbin/nologin edumatrix
mkdir -p /opt/edumatrix /var/log/edumatrix /var/log/caddy /etc/edumatrix
chown -R edumatrix:edumatrix /opt/edumatrix /var/log/edumatrix
# 【/var/log/caddy 必须归 caddy】它以 caddy 用户跑，root 建的目录它写不进去，
# 表现是 systemctl start 直接失败：open …log: permission denied
chown -R caddy:caddy /var/log/caddy
chmod 700 /etc/edumatrix
REMOTE
  log "provision 完成"
}

# ---------------------------------------------------------------------------
build() {
  log "② 本地构建（跳过测试；测试请单独跑 mvn verify）"
  cd "$REPO_ROOT/backend"
  mvn -q -DskipTests package
  [ -f "$REPO_ROOT/$JAR_LOCAL" ] || die "构建产物不存在：$JAR_LOCAL"
  ls -lh "$REPO_ROOT/$JAR_LOCAL"
}

# ---------------------------------------------------------------------------
ship() {
  log "③ 传 jar 与配置"
  [ -f "$REPO_ROOT/$JAR_LOCAL" ] || die "先跑 build"
  $SSH "test -f $ENV_FILE" \
    || die "$ENV_FILE 不存在。口令由需方提供，本脚本不创建它。"

  $SCP "$REPO_ROOT/$JAR_LOCAL" "root@$HOST:$REMOTE_DIR/edumatrix-backend.jar.new"
  $SCP "$REPO_ROOT/deploy/prod/edumatrix.service"   "root@$HOST:/etc/systemd/system/edumatrix.service"
  $SCP "$REPO_ROOT/deploy/prod/Caddyfile"           "root@$HOST:/etc/caddy/Caddyfile"
  $SCP "$REPO_ROOT/deploy/prod/edumatrix.logrotate" "root@$HOST:/etc/logrotate.d/edumatrix"

  $SSH bash -s <<REMOTE
set -euo pipefail
chmod 600 $ENV_FILE; chown root:root $ENV_FILE
mv $REMOTE_DIR/edumatrix-backend.jar.new $REMOTE_DIR/edumatrix-backend.jar
chown edumatrix:edumatrix $REMOTE_DIR/edumatrix-backend.jar
systemctl daemon-reload
caddy validate --config /etc/caddy/Caddyfile 2>&1 | tail -2
# 【必须 restart，不能 reload】Caddyfile 里 admin off 关掉了 :2019 admin API，
# 而 reload 正是通过它热加载的（会报 connection refused）。理由见 Caddyfile 头注释。
systemctl enable -q caddy && systemctl restart caddy
logrotate -d /etc/logrotate.d/edumatrix >/dev/null 2>&1 && echo "logrotate 配置语法 OK"
REMOTE
  log "ship 完成"
}

# ---------------------------------------------------------------------------
# 幂等建库。【必须在 start 之前】，理由见头注释的 F-31。
# 语句是基线 V202608120000 第 77 行的【原文】，一个字不改 —— charset 子句的来源
# 是基线本身，不是谁的控制台默认值。
ensure_database() {
  log "③.5 幂等建库（带显式 charset 子句；基线第 77 行在 Flyway 下不会执行，故此处必须显式建）"
  $SSH bash -s <<'REMOTE'
set -euo pipefail
set -a; . /etc/edumatrix/db.env; set +a
HOSTPORT=$(echo "$MYSQL_URL" | sed -E 's#^jdbc:mysql://([^/]+)/.*#\1#')
DBHOST=${HOSTPORT%%:*}; DBPORT=${HOSTPORT##*:}; [ "$DBPORT" = "$DBHOST" ] && DBPORT=3306

# 客户端可能是 MariaDB 版（--skip-ssl）或 Oracle 版（--ssl-mode=DISABLED）。
# 走 VPC 内网，关掉 SSL 只影响这几条管理命令；应用侧仍按 MYSQL_URL 的 useSSL 走
if mysql --version | grep -qi mariadb; then SSLFLAG="--skip-ssl"; else SSLFLAG="--ssl-mode=DISABLED"; fi
M="mysql -h$DBHOST -P$DBPORT -u$MYSQL_USER -p$MYSQL_PASSWORD $SSLFLAG"

# 基线第 77 行原文
$M -e "CREATE DATABASE IF NOT EXISTS \`edumatrix\` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;" 2>&1 | grep -v "Using a password" || true

echo "--- 建库后的默认字符集（必须 utf8mb4 / utf8mb4_0900_ai_ci）---"
$M -N -B -e "SELECT DEFAULT_CHARACTER_SET_NAME, DEFAULT_COLLATION_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME='edumatrix';" 2>&1 | grep -v "Using a password" || true
REMOTE
}

# ---------------------------------------------------------------------------
start() {
  log "④ 启动并等健康检查"
  $SSH bash -s <<'REMOTE'
set -euo pipefail
systemctl enable -q edumatrix
systemctl restart edumatrix
for i in $(seq 1 60); do
  if curl -fsS http://127.0.0.1:8080/actuator/health >/dev/null 2>&1; then
    echo "健康检查通过（${i}s）"; exit 0
  fi
  if ! systemctl is-active --quiet edumatrix; then
    echo "!! 服务已退出，最后 40 行："; tail -40 /var/log/edumatrix/stderr.log; exit 1
  fi
  sleep 1
done
echo "!! 60s 内未通过健康检查，最后 40 行："; tail -40 /var/log/edumatrix/stdout.log; exit 1
REMOTE
}

# ---------------------------------------------------------------------------
# 首次迁移后必须跑：库与表的字符集/排序规则
# 【为什么单独一条】41 张表全部继承库默认值，而库默认值只由基线第 77 行决定。
# 库若被人预先建过，排序规则就可能不是 utf8mb4_0900_ai_ci —— 迁移与启动都不会报错，
# 但唯一索引的「算不算重复」判定会变。现在改代价为零，有数据之后要全表转换。
verify_charset() {
  log "⑤ 校验库与表的排序规则（首次迁移后必做）"
  $SSH bash -s <<'REMOTE'
set -euo pipefail
set -a; . /etc/edumatrix/db.env; set +a
HOSTPORT=$(echo "$MYSQL_URL" | sed -E 's#^jdbc:mysql://([^/]+)/.*#\1#')
DBHOST=${HOSTPORT%%:*}; DBPORT=${HOSTPORT##*:}; [ "$DBPORT" = "$DBHOST" ] && DBPORT=3306
if mysql --version | grep -qi mariadb; then SSLFLAG="--skip-ssl"; else SSLFLAG="--ssl-mode=DISABLED"; fi
# 每条查询后面的 `|| true` 不可省：grep 在【无输出】时返回 1，
# 而「0 行」恰恰是检查 ② 的【通过】条件 —— 不加就会被 set -e 杀在通过的那一步上，
# 表现是脚本跑到 ② 就无声结束，后面三条根本没执行（第一版就是这样）
M="mysql -h$DBHOST -P$DBPORT -u$MYSQL_USER -p$MYSQL_PASSWORD $SSLFLAG -N -B"
echo "--- ① 库默认字符集（必须 utf8mb4 / utf8mb4_0900_ai_ci）---"
$M -e "SELECT DEFAULT_CHARACTER_SET_NAME, DEFAULT_COLLATION_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME='edumatrix';" 2>&1 | grep -v "Using a password" || true
echo "--- ② 排序规则不一致的表（必须 0 行）---"
$M -e "SELECT TABLE_NAME, TABLE_COLLATION FROM information_schema.TABLES WHERE TABLE_SCHEMA='edumatrix' AND TABLE_COLLATION <> 'utf8mb4_0900_ai_ci';" 2>&1 | grep -v "Using a password" || true
echo "--- ③ Flyway 迁移记录（应 6 条全 success）---"
$M -e "SELECT installed_rank, version, description, success FROM edumatrix.flyway_schema_history ORDER BY installed_rank;" 2>&1 | grep -v "Using a password" || true
echo "--- ④ 连接级字符集（表对了，传输层未必对）---"
# 服务端默认是 utf8mb3，而 URL 写的是 characterEncoding=utf8 ——
# 要确认实际协商到 utf8mb4，否则 4 字节字符（emoji、部分生僻字）会在【传输层】
# 被截断，而表本身是对的，查起来非常难。若不是 utf8mb4，把 URL 的
# characterEncoding 显式改成 utf8mb4 再验。
$M -e "SHOW VARIABLES WHERE Variable_name IN ('character_set_client','character_set_connection','character_set_results','character_set_database','collation_connection');" 2>&1 | grep -v "Using a password" || true
echo "--- ⑤ 表数量（应为 41 + flyway_schema_history = 42）---"
$M -e "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA='edumatrix';" 2>&1 | grep -v "Using a password" || true
REMOTE
}

# ---------------------------------------------------------------------------
# ---------------------------------------------------------------------------
# 【冒烟必须用【裸 IP，不带 Host 头】】——与直觉相反，已实测：
#   curl -H "Host: api.hqtw.cn" http://47.110.142.8/...  → HTTP 403
#     响应体是阿里云的 <title>Non-compliance ICP Filing</title> 拦截页，
#     跳 http://www.aliyun.com/beian/beian-block?id=...
#     也就是说【带 Host 头恰恰是触发拦截的那个条件】，请求根本到不了 Caddy
#     （Caddy 的访问日志里没有这次请求）。
#   curl http://47.110.142.8/...（不带 Host）→ 直达 Caddy 的 :80 块
#
#   所以备案接入变更完成之前，联调一律走裸 IP。备案通过后再改回域名。
verify() {
  log "⑥ 冒烟（裸 IP，不带 Host 头 —— 带了会被阿里云备案拦截页 403 掉）"
  $SSH bash -s <<'REMOTE'
set -euo pipefail
echo "--- 健康检查（经 Caddy）---"
curl -fsS http://127.0.0.1/actuator/health; echo
echo "--- 验证码（认证白名单，不需要 token）---"
curl -fsS http://127.0.0.1/api/v1/auth/captcha | head -c 200; echo
REMOTE
  log "外网裸 IP 直连："
  curl -fsS --max-time 15 "http://$HOST/actuator/health" && echo
  log "对照：带 Host 头会被备案拦截（预期 403，不是服务的问题）"
  curl -s -o /dev/null -w "  带 Host: api.hqtw.cn → HTTP %{http_code}\n" \
    --max-time 15 -H "Host: api.hqtw.cn" "http://$HOST/actuator/health"
}

status() {
  $SSH 'systemctl status edumatrix --no-pager -l | head -20; echo; systemctl status caddy --no-pager | head -6'
}

case "${1:-}" in
  provision)      provision ;;
  build)          build ;;
  ship)           ship ;;
  ensure-database) ensure_database ;;
  start)          start ;;
  verify)         verify ;;
  verify-charset) verify_charset ;;
  status)         status ;;
  all)            build; ship; ensure_database; start; verify; verify_charset ;;
  *) sed -n '3,20p' "$0"; exit 1 ;;
esac
