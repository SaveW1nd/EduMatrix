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
#
# ============================================================================
# 【监控告警规则（Prometheus）—— 与 common/metrics/MetricsRegistry 逐条对应】
# ============================================================================
#   模块 11（授权引擎）上线后必须配的两条，各管一件事：
#
#     time() - grant_consistency_last_run_epoch_seconds > 93600   # 26h：巡检没跑
#     grant_dangling_count > 0                                    # 有真悬挂
#
#   26h = 24h 周期 + 2h 余量。
#   【第一条存在之后，第二条才允许「缺席」】：grant_dangling_count 是 per-tenant 的、
#   且只在该租户被首次扫到之后才注册 —— 0 租户或调度器没触发时它一条序列都没有，
#   而 `> 0` 这种写法在序列缺席时【不会触发】，与「一切健康」长得一模一样。
#   缺席由第一条负责喊。
#
#   grant_consistency_last_run_epoch_seconds 【不带标签、构造器注册、永远存在】，
#   初值 0 =「从未跑过」→ 刚部署完它就报警是【期望行为】，不是误报。
#   ⚠ 别把初值改成当下时刻（模块 09 那个 10 秒一轮的消费者那样做是对的）——
#     日任务照抄会让「任务从不触发」在每次重启后被掩盖一天，而部署比一天频繁时被永久掩盖。
#
#   验证方式：/actuator/* 对公网一律 404（设计如此），要在【服务器本机】curl：
#     curl -s http://127.0.0.1:8080/actuator/prometheus | grep grant_
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

# ============================================================================
# 【服务端强制加密 —— 这不是「内网可以免」的那类开关】（F-111）
# ============================================================================
# 实测（2026-08-21，生产 RDS）：
#     SHOW VARIABLES LIKE 'require_secure_transport';  →  ON
# 未加密的连接在【握手阶段】就被直接拒绝，与走不走 VPC 内网无关：
#     ERROR 3159 (HY000): Connections using insecure transport are prohibited
#                         while --require_secure_transport=ON.        （退出码 1）
# 【应用侧一直是对的】：db.env 里 MYSQL_URL 写着 sslMode=REQUIRED，
# 错的只有下面这几条管理命令 —— 它们此前显式写了 --skip-ssl / --ssl-mode=DISABLED。
# 「应用连得上而脚本连不上」的全部差别就在这一个开关上。
#
# 客户端实测是 MariaDB 版（mysql from 11.8.6-MariaDB, client 15.2），
# 所以是 --ssl 而【不是】--ssl-mode —— 后者在它上面报
#     mysql: unknown variable 'ssl-mode=REQUIRED'
# 分支保留是为了将来换成 Oracle 客户端时不用再查一遍，两边现在都【开】TLS。
#
# --ssl-verify-server-cert=0 是【明写出来的取舍】，不是疏忽：验 CA 要往机器上
# 放一份 CA 文件，那是一个新的部署产物，本轮不引入。写成显式的 0 顺带消掉
# MariaDB 11.4+ 的那句
#     WARNING: option --ssl-verify-server-cert is disabled,
#              because of an insecure passwordless login
# —— 它是因为口令走 MYSQL_PWD、客户端以为没给口令才打的，与连接安不安全无关。
# 实测本次连接协商到 TLSv1.2 / ECDHE-RSA-AES256-GCM-SHA384。
if mysql --version | grep -qi mariadb; then
  SSLFLAGS="--ssl --ssl-verify-server-cert=0"
else
  SSLFLAGS="--ssl-mode=REQUIRED"
fi

# ============================================================================
# 【口令走环境变量，不走 -p】—— 一处改动解掉三层，根因链见 F-111
# ============================================================================
#   -p"$MYSQL_PASSWORD" → mysql 每次在 stderr 打一行 "Using a password" 警告
#     → 为藏警告加 `| grep -v` → 管道让退出码变成 grep 的，mysql 的丢了
#     → grep 在【无输出】时返回 1，而「0 行」恰恰是 verify_charset 检查 ② 的
#       【通过】条件 → 通过的那一步反而被 set -e 杀掉
#     → 为不被误杀又加 `|| true` → 真错误（连不上、口令错、库不存在）一起被吞光
#   最上面那一环本来就不该有。去掉它，下面三层就都不需要了。
#
#   【顺带纠正一个流传很广、但本机实测不成立的理由】常见说法是「-p 会把口令
#   暴露给同机器上任何能 ps 的用户」。实测（本机 MariaDB 客户端 11.8.6）：
#       ps -eo args  →  mysql ... -u<user> -px xxxxxxxxxxxxx -e SELECT SLEEP(4);
#   客户端启动后会把 argv 里的口令【就地覆盖】，真口令在 ps 里出现 0 次
#   —— 新旧两种写法都是 0。所以本轮换掉 -p 的理由【不是】ps，是上面那条根因链。
#   MYSQL_PWD 仍然更好，但理由要说准：覆盖发生在 exec 之【后】，中间有一个窗口；
#   而且那是客户端的行为、不是可依赖的契约（换个客户端就未必）。
#   把理由写准是有代价的事 —— 拿一个假理由去改对的代码，下一个人会照着假理由
#   做出错的决定（本项目 F-100 就是「判断对、理由错」的那一次）。
#   【不用临时 defaults 文件】：那需要 trap 清理，而 trap 没触发（kill -9、断网）
#   就会在磁盘上留下一份明文口令。环境变量不落盘。
export MYSQL_PWD="$MYSQL_PASSWORD"
M="mysql $SSLFLAGS -h$DBHOST -P$DBPORT -u$MYSQL_USER"

# 基线第 77 行原文
$M -e "CREATE DATABASE IF NOT EXISTS \`edumatrix\` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;"

echo "--- 建库后的默认字符集（必须 utf8mb4 / utf8mb4_0900_ai_ci）---"
$M -N -B -e "SELECT DEFAULT_CHARACTER_SET_NAME, DEFAULT_COLLATION_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME='edumatrix';"
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
# TLS 与口令的处置逐字同 ensure_database，理由见那里的两段注释与 F-111。
# 【原来这里每条查询尾巴上的 `2>&1 | grep -v "Using a password" || true` 已全部去掉】。
# 那三层是连在一起的：-p 打警告 → grep 藏警告 → 管道吃掉 mysql 的退出码 →
# grep 无输出返回 1（而「0 行」正是检查 ② 的通过条件）→ 只好再加 || true 兜住 →
# 真错误一起被吞。去掉最上面的 -p，下面三层就都不需要了。
# 【当初那个 || true 不是多余的】：直接删它、而把 -p 留着，会修回第一版的 bug ——
# 脚本跑到检查 ② 就无声结束，③④⑤ 根本不执行。所以要动的是链条最上面那一环。
# 实测：口令走 MYSQL_PWD 之后，检查 ② 返回 0 行时 mysql 退出码是 0（不是 1）。
if mysql --version | grep -qi mariadb; then
  SSLFLAGS="--ssl --ssl-verify-server-cert=0"
else
  SSLFLAGS="--ssl-mode=REQUIRED"
fi
export MYSQL_PWD="$MYSQL_PASSWORD"
M="mysql $SSLFLAGS -h$DBHOST -P$DBPORT -u$MYSQL_USER -N -B"
echo "--- ① 库默认字符集（必须 utf8mb4 / utf8mb4_0900_ai_ci）---"
$M -e "SELECT DEFAULT_CHARACTER_SET_NAME, DEFAULT_COLLATION_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME='edumatrix';"
echo "--- ② 排序规则不一致的表（必须 0 行）---"
$M -e "SELECT TABLE_NAME, TABLE_COLLATION FROM information_schema.TABLES WHERE TABLE_SCHEMA='edumatrix' AND TABLE_COLLATION <> 'utf8mb4_0900_ai_ci';"
echo "--- ③ Flyway 迁移记录（每条都必须 success=1；条数以 db/migration 下的文件数为准，不写死）---"
$M -e "SELECT installed_rank, version, description, success FROM edumatrix.flyway_schema_history ORDER BY installed_rank;"
echo "--- ④ 连接级字符集（表对了，传输层未必对）---"
# 服务端默认是 utf8mb3，而 URL 写的是 characterEncoding=utf8 ——
# 要确认实际协商到 utf8mb4，否则 4 字节字符（emoji、部分生僻字）会在【传输层】
# 被截断，而表本身是对的，查起来非常难。若不是 utf8mb4，把 URL 的
# characterEncoding 显式改成 utf8mb4 再验。
$M -e "SHOW VARIABLES WHERE Variable_name IN ('character_set_client','character_set_connection','character_set_results','character_set_database','collation_connection');"
echo "--- ⑤ 表数量（应为 41 + flyway_schema_history = 42）---"
$M -e "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA='edumatrix';"
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
# 【不要写成 `curl ... | head -c 200`】—— 与 F-111 是同一个形状的第二处：
#   验证码响应体是一张 base64 图片（约 10KB），head 取够 200 字节就关掉管道，
#   curl 还在写 → SIGPIPE → curl 退 23（"Failed writing body"），
#   而 `set -euo pipefail` 里 pipefail 会把这个 23 变成整条管道的退出码 ——
#   于是【这一步明明成功了，却把整个 verify 杀掉】。
#   后果不是少打几个字：verify_actuator_not_public（下面那个断言 /actuator/*
#   不可从公网读取的安全检查）【根本不会执行】。2026-08-21 那次部署就是这样
#   停在这一行的，EXIT=23。
#   而且它是【race，不是必现】：实测同一命令连跑 8 次，6 次 23、2 次 0 ——
#   偶尔绿一次比每次都红更坏，因为没人会去查一个「有时候好的」步骤。
#   改成先收进变量再截断，压根不产生管道。
CAPTCHA_BODY=$(curl -fsS http://127.0.0.1/api/v1/auth/captcha)
printf '%s\n' "${CAPTCHA_BODY:0:200}"
REMOTE
  # 【外网冒烟打业务接口，不打 /actuator/health】
  #   /actuator/* 自本轮起对公网一律 404（见 deploy/prod/Caddyfile 头注释），
  #   继续用它做外网冒烟会永远打印一行 curl: (22) ... 404 —— 看着像故障、实际是设计，
  #   而「看着像故障的正常输出」会让人此后忽略这一整段输出。
  #   换成 §1.1 获取图形验证码：它在 00-通用约定 §2.3 的免登录白名单里，不需要 token，
  #   且它经过完整链路（Caddy → 应用 → Redis 写验证码），比读一个健康探针证明得更多。
  log "外网裸 IP 直连（打业务接口；/actuator/* 已对公网关闭）："
  curl -fsS --max-time 15 "http://$HOST/api/v1/auth/captcha" >/dev/null \
    && echo "  ✅ GET /api/v1/auth/captcha 200" \
    || die "外网裸 IP 打不通业务接口"
  log "对照：带 Host 头会被备案拦截（预期 403，不是服务的问题）"
  curl -s -o /dev/null -w "  带 Host: api.hqtw.cn → HTTP %{http_code}\n" \
    --max-time 15 -H "Host: api.hqtw.cn" "http://$HOST/actuator/health"

  verify_actuator_not_public
}

# ---------------------------------------------------------------------------
# /actuator/* 必须【不可从公网读取】—— 断言式检查，不满足即让 verify 非 0 退出。
#
# 【为什么这两条要写成脚本断言，而不是"改完配置手动 curl 一次"】
#   那道闸只存在于 deploy/prod/Caddyfile 的两行 import 里。将来谁编辑 Caddyfile
#   把其中一行弄丢，【没有任何东西会报警】—— caddy validate 照样通过、
#   部署照样成功、健康检查照样绿，只是指标又悄悄对外了。
#   这正是本项目反复点名的「不报错的故障」，只能靠每次部署都实测一遍来兜。
#
# 【第二条（伪造 X-Forwarded-For）不是多余的】它验的是 Caddyfile 里用的是
#   remote_ip 而不是 client_ip。client_ip 读的是 X-Forwarded-For ——
#   请求方可以随便写，用它等于任何人加一行头就绕过去，
#   而绕过时不报错、日志里看着像本机访问。所以必须有一条专门打这个头的用例。
#
# 【为什么只查 prometheus 不查 health】health 是同一段规则命中的同一条路径，
#   查一条足够；而 prometheus 是真正泄露内容的那一个（uri="/api/v1/..." 逐条列出
#   + 连接池 + 磁盘 + JVM 明细），断言写在它身上更贴近这道闸存在的理由。
# ---------------------------------------------------------------------------
verify_actuator_not_public() {
  log "⑦ /actuator/* 不可从公网读取（配置漂移守卫）"
  local code

  code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 15 \
    "http://$HOST/actuator/prometheus" || echo 000)
  if [ "$code" = "404" ]; then
    printf '  ✅ 外网 GET /actuator/prometheus → HTTP %s\n' "$code"
  else
    printf '  ❌ 外网 GET /actuator/prometheus → HTTP %s（期望 404）\n' "$code"
    die "/actuator/* 对公网可读 —— deploy/prod/Caddyfile 里的 import actuator_local_only 是不是丢了？"
  fi

  code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 15 \
    -H "X-Forwarded-For: 127.0.0.1" "http://$HOST/actuator/prometheus" || echo 000)
  if [ "$code" = "404" ]; then
    printf '  ✅ 伪造 X-Forwarded-For: 127.0.0.1 后仍 → HTTP %s\n' "$code"
  else
    printf '  ❌ 伪造 X-Forwarded-For: 127.0.0.1 → HTTP %s（期望 404）\n' "$code"
    die "用伪造头绕过了本机限制 —— Caddyfile 里应该是 remote_ip，不是 client_ip（client_ip 读的就是这个可伪造的头）"
  fi
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
  verify-actuator) verify_actuator_not_public ;;
  verify-charset) verify_charset ;;
  status)         status ;;
  all)            build; ship; ensure_database; start; verify; verify_charset ;;
  *) sed -n '3,20p' "$0"; exit 1 ;;
esac
