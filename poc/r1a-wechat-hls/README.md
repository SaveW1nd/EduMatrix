# R1a POC —— 微信内置浏览器 + AES-128 加密 HLS 可行性验证

> **这不是工程的一部分。** 一次性技术验证物：不进 Maven、不进 `scripts/check_consistency.py` 的
> `MD_FILES`、不进任何检查器、不被 `backend/` 引用。结论落进 `RESULT.md` 之后，
> 这个目录的历史使命就完了。

## 它要回答什么

`docs/DESIGN-CONTRACT.md` §1 定了「HLS 标准加密（AES-128），不用阿里云私有加密」，
理由是保住 **DPlayer 二开**（禁拖拽、跑马灯水印、10s 心跳）与 **微信内置浏览器兼容性**
两个一级约束。`docs/04-实施计划.md` §D 把这条理由登记为 **R1a**，并写明它是
「整份计划里**唯一能推翻已定案架构**的一项」，**必须在模块 01 之前做完**。

要验的四件事，**必须同时成立**：

| # | 验什么 | 不成立的后果（04 §D 原文） |
| --- | --- | --- |
| ① | 微信 X5/XWEB 能否用 hls.js 播 AES-128 加密 HLS | 契约 §1「HLS 标准加密 + DPlayer + hls.js」整条选型失效，回到阿里云私有加密 + 阿里云播放器，模块 12/13/14 全部重做 |
| ② | 能否拦到 key 请求并读到 URL 上的自定义参数 | `MtsHlsUriToken` 身份通道失效，密钥接口无法鉴权，契约 §2.5「撤销级联回收」的落地点整个消失 |
| ③ | 水印 DOM + MutationObserver 在同层渲染 / 全屏下是否有效 | PRD F2-6 降级为"仅 Android 有效"，须与需求方重新约定验收口径 |
| ④ | 进度条能否拦住（iOS 全屏是否被系统播放器接管） | PRD F2-5 同上 |

**② 为什么是核心**：真实链路里 `#EXT-X-KEY` 的 URI 是转码提交时写死的固定地址，
发起请求的是 hls.js 内核，**它不会带任何自定义请求头**（契约 §1 已写明"身份不能走请求头"）。
所以身份只能挂在 URL 参数上。POC 验的就是——**这个参数能不能活着到达服务端**。

## 文件

```
poc/r1a-wechat-hls/
├── README.md         本文件
├── make-hls.sh       ffmpeg 切一段 AES-128 加密 HLS；key URI 上带 ?token=
├── server.js         零依赖 node 服务：静态托管 + mock /key + /log 回收 + /admin/logs
├── index.html        测试页：hls.js + 水印 + MutationObserver + 禁拖拽 + 屏内诊断
├── deploy-server.sh  服务器一键部署（Ubuntu 24.04 + Caddy + systemd），幂等
└── RESULT.md         【空模板】真机测完由人填写，二选一，不允许"待观察"
```

**运行环境**：`server.js` 只用 `http` / `fs` / `path` / `url` 四个标准库加全局 `URL`，
没有用任何新语法，**Node 18 即可**。Ubuntu 24.04 的 `apt install nodejs` 装的是
**18.19.1**，已实测跑通全部端点，所以部署脚本默认走 apt，**不引入 NodeSource 源**
（这台机器后面要跑生产，少加一个第三方 apt 源就少一份残留）。
只有在 node 缺失或主版本 < 18 时，脚本才会退到 NodeSource。

产物（`media/`、`logs/`、`vendor/`、密钥）**一律不进 git**，见 `.gitignore`。

---

## 一、本机先跑通（可选，10 分钟，排除工具自身的问题）

> 赶时间可以直接跳到 §二 —— 部署脚本在服务器上会把同样的事再做一遍。
> 但本机跑一遍的好处是：真机上出问题时，你知道那不是工具坏了。

```bash
cd poc/r1a-wechat-hls
```

装依赖（macOS）：

```bash
brew install ffmpeg node
```

放一份 hls.js 到 `vendor/`（页面优先用本地，拿不到才回退 CDN；
**上真机务必用本地**——微信里 CDN 偶发被拦，那会伪装成"①不成立"）：

```bash
mkdir -p vendor && curl -L -o vendor/hls.min.js https://cdn.jsdelivr.net/npm/hls.js@1/dist/hls.min.js
```

要测 `player=dplayer` 模式再加两份：

```bash
curl -L -o vendor/DPlayer.min.js https://cdn.jsdelivr.net/npm/dplayer@1/dist/DPlayer.min.js && curl -L -o vendor/DPlayer.min.css https://cdn.jsdelivr.net/npm/dplayer@1/dist/DPlayer.min.css
```

切一段加密 HLS（本机自检用 localhost，**上服务器要用真域名重切，见下节**）：

```bash
cd poc/r1a-wechat-hls && ./make-hls.sh http://localhost:8080
```

脚本末尾会自检三条：m3u8 里有没有 `#EXT-X-KEY:METHOD=AES-128`、
key URI 上的 `?token=` 在不在、分片首字节是不是真的不是 `0x47`（明文 TS 同步字节）。
**三条不全过就别往下走**——带着一个切错的流去测，测出来的结论是假的。

起服务：

```bash
cd poc/r1a-wechat-hls && node server.js
```

（8080 被占就 `PORT=8099 node server.js`，**换端口后要用新地址重跑 `make-hls.sh`**——
key URI 里的端口是写死进 m3u8 的。）

桌面 Chrome 打开 <http://localhost:8080/>，点「播放」。另开一个标签页看
<http://localhost:8080/admin/logs>，`/key` 那条日志里应能看到
`query={"token":"TESTTOKEN123"}`。**桌面能过只说明工具没坏，不说明结论成立**——
R1a 的四件事全在微信内置浏览器里，桌面 Chrome 一件都不代表。

**这套工具在桌面 Chrome（hls.js 1.7.0）上已逐条自检过**，`raw` 与 `dplayer` 两种模式各一遍：
加密流能播（`KEY_LOADED` + 分片解密）、`/key` 收到完整 `?token=`、
水印三种篡改（删外层 / 删内层 / `display:none`）均被抓到并立即暂停、
向前拖拽被拉回。所以真机上若某一条不成立，**那是机型的结论，不是工具的毛病**。

---

## 二、部署到服务器

已确认的环境（脚本按这个写死默认值）：

| 项 | 值 |
| --- | --- |
| 云厂商 | 阿里云**轻量应用服务器**（不是 ECS——防火墙在控制台的「防火墙」标签，没有安全组） |
| 地域 | 华东1（杭州） |
| 系统 | Ubuntu 24.04 · 2核 4G / 50G |
| 公网 IP | `114.215.196.24` |
| 域名 | `poc.hqtw.cn`（A 记录已指向上面那个 IP） |
| 登录密钥 | `~/.ssh/edumatrix.pem` |
| 反代 | Caddy（自动签 Let's Encrypt 证书，不用手动配证书） |

> **必须全站 HTTPS 且同源。** 页面走 https 而 m3u8 或 `/key` 走 http，
> 会被内核当混合内容直接 block——那会伪装成「①不成立」，把一次验证浪费掉。
> Caddy 会自动把 80 跳到 443，这一条自然满足。

### 0）前置：控制台放行 80 和 443（**必须先做，脚本管不了**）

阿里云控制台 →「轻量应用服务器」→ 选中这台实例 →「**安全**」→「**防火墙**」标签
（轻量应用服务器**没有安全组**，别去 ECS 那边找）→「添加规则」，加两条：

| 应用类型 | 端口 | 备注 |
| --- | --- | --- |
| HTTP | 80 | **不能省，理由见下** |
| HTTPS | 443 | 页面与所有资源走这条 |

**为什么 80 不能省**：Caddy 用 Let's Encrypt 的 **HTTP-01 挑战**签证书——
CA 会主动回访 `http://poc.hqtw.cn/.well-known/acme-challenge/...`，走的是 **80 端口**。
只开 443 的话，443 上还没有证书、而 80 又不通，**挑战永远完不成，证书永远签不下来**，
现象是 `curl https://poc.hqtw.cn` 一直连不上或报证书错误，
而你会以为是 Caddy 配错了——实际上是防火墙少开了一个端口。

证书签下来之后 80 仍然要留着：证书 90 天一续期，续期还走同一条挑战。

### 1）传代码到服务器

在**本机仓库根目录**执行（`media/` `logs/` `vendor/` 都在服务器上生成，不传）：

```bash
rsync -avz --no-owner --no-group -e "ssh -i ~/.ssh/edumatrix.pem" --exclude media --exclude logs --exclude vendor poc/r1a-wechat-hls/ root@114.215.196.24:/opt/r1a-poc/
```

> `--no-owner --no-group` 不能省。`rsync -a` 以 root 身份上传时会把**本机**的 UID/GID
> 一起搬过去（macOS 是 `501:staff`），服务器上没有这个用户，systemd unit 里就会写成
> `User=UNKNOWN`，服务报 `217/USER` 起不来。部署脚本里已经兜了一层（解析不出来就 chown
> 成 root），但从源头上别把它带过去更干净。

### 2）登上去跑部署脚本

```bash
ssh -i ~/.ssh/edumatrix.pem root@114.215.196.24
```

```bash
cd /opt/r1a-poc && chmod +x deploy-server.sh make-hls.sh && ./deploy-server.sh
```

脚本做六件事，**每步先检测再执行，重复跑不会出错**：

| 步 | 干什么 | 已经做过时 |
| --- | --- | --- |
| 1 | 装 ffmpeg / node / curl / git | 跳过 |
| 2 | 装 Caddy（官方 apt 源；国内取不到源时自动改从 GitHub 取 .deb） | 跳过 |
| 3 | 写 `/etc/caddy/Caddyfile` 并 reload | 内容一致则跳过；**内容不同会先备份成 `.bak.<时间戳>` 再写，不静默覆盖** |
| 4 | 下载 `vendor/hls.min.js` + DPlayer 两份 | 已存在则跳过 |
| 5 | `make-hls.sh https://poc.hqtw.cn` 切加密流 | key URI 已正确则跳过；要重切用 `FORCE_RECUT=1 ./deploy-server.sh` |
| 6 | 装成 systemd 服务 `r1a-poc` 并启动 | unit 一致则只 restart |

跑完它自己会做三条自检并把手机要扫的地址打出来。

**为什么第 4 步必须把播放器库下到本地**：微信里 CDN 偶发被拦，页面会打
`LOAD ★ hls.js 加载失败`，而在手机上它长得和「①加密 HLS 播不了」一模一样——
这是整个验证里最容易得出假结论的地方。

**为什么第 5 步的域名参数只能是 `https://poc.hqtw.cn`**：key URI 是**绝对地址**、
被写死进 m3u8 的 `#EXT-X-KEY`。写成 IP 或 localhost，手机上必然取不到密钥，
而那个现象和「②参数没活到服务端」长得一模一样。

**为什么用 systemd 而不是 `nohup`**：SSH 一断进程就没了，而真机测试要反复来回、
改一版看一版；systemd 还能在进程崩了之后自动拉起，日志统一进 journal。

### 3）三条验证，每条都给出预期输出

**① DNS**（在**本机**跑，验的是解析而不是服务器）

```bash
dig +short poc.hqtw.cn
```

预期**恰好**这一行：

```
114.215.196.24
```

出别的 IP 说明 A 记录改了没生效（TTL 10 分钟，等一会儿再试）；什么都不出说明记录没加上。

**② HTTPS 与证书**

```bash
curl -I https://poc.hqtw.cn/
```

预期：`HTTP/2 200`，且**没有任何证书告警**（curl 一旦报
`SSL certificate problem` 就是证书没签下来）。想看证书本身：

```bash
curl -sv https://poc.hqtw.cn/ -o /dev/null 2>&1 | grep -E "subject:|issuer:|expire"
```

预期 issuer 是 `Let's Encrypt`，subject 含 `poc.hqtw.cn`。
连不上就回去查 80 有没有放行（见 §二 0），以及 `journalctl -u caddy -n 50 --no-pager`。

**③ m3u8 里的 key URI —— 这条最关键**

```bash
curl -s https://poc.hqtw.cn/media/index.m3u8 | head
```

预期能看到这样一行：

```
#EXT-X-KEY:METHOD=AES-128,URI="https://poc.hqtw.cn/key?token=TESTTOKEN123",IV=0x...
```

**逐字对三件事**：`METHOD=AES-128` 在（不是明文流）、URI 里的域名是
`poc.hqtw.cn`（不是 IP、不是 localhost）、`?token=` 在。
**这三样决定了②能不能验**——URI 写错，播放器根本到不了你的服务端；
`token` 丢了，`MtsHlsUriToken` 那条身份通道就没有观测对象。
对不上就 `FORCE_RECUT=1 ./deploy-server.sh` 重切。

顺手再确认一次密钥端点本身通，且参数没被中间层吃掉：

```bash
curl -s -o /dev/null -w '%{http_code}\n' 'https://poc.hqtw.cn/key?token=TESTTOKEN123'
```

预期 `200`，同时服务端日志里那条 `[KEY ]` 显示 `token在? YES`。

### 4）出二维码，手机扫

```bash
qrencode -t ANSIUTF8 'https://poc.hqtw.cn/'
```

没有 `qrencode`（`brew install qrencode`）就用任意在线工具把
`https://poc.hqtw.cn/` 转成二维码。**必须在微信里扫**——
要验的就是微信内置浏览器，用手机自带浏览器打开等于没测。

---

## 三、怎么测

### 机型：至少三台，缺一不可

| 机型 | 它专门验什么 |
| --- | --- |
| **iOS 微信**（WKWebView） | ①③④ 最可能挂的地方：hls.js 可能因无 MSE 而退化为原生播放 HLS；全屏可能被系统播放器接管，DOM 水印与自定义守卫一起失效 |
| **Android 微信 · X5 内核** | 同层渲染、水印层级 |
| **Android 微信 · XWEB 内核** | 与 X5 是两套内核，行为可能不同，不能拿一台代另一台 |

内核怎么认：页面顶部 `ENV` 区打了 `xweb` / `tbs` 字段，也可看 UA 里的 `XWEB/` 或 `TBS/`。

### 每台机型走的三轮

**第 1 轮 · 基准（先用裸 hls.js 排除干扰）**

```
https://poc.hqtw.cn/
```

等价于 `?engine=auto&player=raw&inline=on&x5=on&wm=on&seekguard=on`。

**第 2 轮 · 关掉同层渲染属性**（"不行"和"某个属性没加所以不行"是两回事）

```
https://poc.hqtw.cn/?x5=off&inline=off
```

**第 3 轮 · 叠 DPlayer**（第 1 轮通过之后再做，看会不会引入新问题）

```
https://poc.hqtw.cn/?player=dplayer
```

> **④ 应以这一轮为准。** DPlayer 自绘控件（它的 `<video>` 没有 `controls`），拖动过程中
> 只改进度条视觉、不碰 `currentTime`，松手才提交一次 seek；进度条、快捷键 ←/→、切集
> 三个入口全部汇聚到 `player.seek(time)`（`references/DPlayer/src/js/player.js:194`、
> `controller.js:117-125`、`hotkey.js:30,38`），页面已经把这个函数包住 —— 这就是模块 14
> 二开要动的那一个点。第 1 轮 `player=raw` 用的是**原生 controls**，拖动期间浏览器持续
> 下发 seek、写回会被覆盖，是比产品形态**更难**的一条路，结论单独记、不代表产品行为。
>
> DPlayer 自己造 video 元素，页面会把**属性装配、拖拽守卫、事件监听整套搬到它的 video 上**，
> 并把 DPlayer 放进 stage 里的独立盒子、水印仍覆盖在其上——所以第 3 轮 ③④ 同样可判。
> 但 DPlayer 有自己的控件与全屏实现，行为可能与裸 hls.js 不同：
> 第 3 轮回答的是"叠上去会不会引入新问题"，**四条判定一律以第 1 轮 `player=raw` 为准**。

iOS 上如果第 1 轮显示走了原生路径，再单独跑一次强制对照：

```
https://poc.hqtw.cn/?engine=hlsjs        # 强制 hls.js，看是不是直接不支持
https://poc.hqtw.cn/?engine=native       # 强制原生，看原生路径下 key 参数还在不在
```

### 四件事逐条怎么判定

| # | 在哪看 | 什么算**过** | 什么算**不过** |
| --- | --- | --- | --- |
| ① | 屏内日志 + 画面 | 出现 `MANIFEST_PARSED` → `KEY_LOADED` → `FRAG_LOADED`，且**画面真的动了、有声音** | 出现 `HLS ★ 致命错误`、`keyLoadError`、`video error code=3/4`，或 `Hls.isSupported()=false` 且原生也播不出来 |
| ② | **`/admin/logs` 的 `[KEY]` 条目** | 服务端收到 `/key` 请求，且 `query={"token":"TESTTOKEN123"}`、`token在? YES` | 服务端**根本没收到** `/key`；或收到了但 `query={}`（参数被内核/中间层削掉）→ 身份通道断 |
| ③ | 人眼 + 屏内日志 | 播放中水印**肉眼可见地浮在画面之上**并在漂移；点「删除水印」和「隐藏水印」**两个按钮各测一次**，每次日志都立刻出 `WM ★ 水印被破坏 → 立即暂停播放` 且视频真的停了；**进全屏后水印仍在画面上** | 水印被视频盖住看不见（层级失效）；或进全屏后水印消失（日志里通常伴随 `FS ★ webkitbeginfullscreen`）；或篡改后视频照播不误 |
| ④ | 屏内日志 + `vitals` 的 `拦截=` 计数 | 手指把进度条往**右**拖，日志出 `SEEK ★ 拦截 #n`，**最后一行 `seeked 落点` 不超过 `maxPosition`**，进度条弹回原位；往**左**拖（回看）和跳到**已看过**的位置要放行、不能误伤；**全屏下同样拦得住** | 落点仍大于 `maxPosition`；或全程 `拦截=0` 却明显跳过去了；或全屏被系统播放器接管后完全拦不住 |

**每条都要截图。** 截图至少要拍到：画面 + 屏内日志里对应的那几行。
②的截图拍电脑上的 `/admin/logs`。

### 关于 ③ 的一个说明

页面用一个**防篡改锚点** `#wm`（铺满播放区、样式永不由脚本改动）+ 一个内层 `#wmInner`
（负责漂移动画）的结构。漂移只写内层，所以水印的动画不会触发自己的观察器——
两者合一会让水印每 2.8 秒把自己判成"被篡改"并暂停播放。

MutationObserver 只抓得到"节点被删 / 锚点属性被改"。抓不到的两类，页面用别的手段补：

- **节点还在但看不见**（display/opacity/尺寸被压）→ 每 2s 一次的 `WM 自检` 会打出来
- **节点可见但被视频盖住**（同层渲染失败时的典型表现）→ **只能靠人眼看截图**，
  代码测不出来。判 ③ 时不要只看日志，一定要看画面。

---

## 四、看结果

**微信里打不开开发者工具，日志是唯一的观察窗口。** 三个入口，测的时候至少开着后两个：

- **手机上**：页面下半部分就是日志，`★` 开头的都是关键判据；「复制日志」按钮可整段拷走
- **电脑浏览器**：<https://poc.hqtw.cn/admin/logs> 实时看手机打回来的日志，
  勾「只看 /key」就是 ② 的全部证据
- **服务器上**：跟着 systemd 的日志走

```bash
journalctl -u r1a-poc -f
```

每次密钥请求会打印完整 URL、query、**全部请求头**与 UA；页面回传的诊断以 `[CLNT]` 开头。
落盘的两份原始记录（重启不丢，便于事后贴进 `RESULT.md`）：

```bash
tail -f /opt/r1a-poc/logs/key-requests.jsonl
```

```bash
tail -f /opt/r1a-poc/logs/client.jsonl
```

服务本身出问题（页面 502、`/health` 不通）看这个：

```bash
systemctl status r1a-poc --no-pager -l
```

Caddy 出问题（证书签不下来、443 连不上）看这个：

```bash
journalctl -u caddy -n 50 --no-pager
```

---

## 五、已知会污染结论的坑

| 坑 | 表现 | 处理 |
| --- | --- | --- |
| 控制台只放行了 443、没放行 80 | 证书永远签不下来，`curl https://` 一直报错，看起来像 Caddy 配错了 | 轻量的「防火墙」标签里补上 80（HTTP-01 挑战走 80，见 §二 0） |
| 用 localhost 或 IP 切的流传到服务器 | key 请求打不到，长得像「②不成立」 | `FORCE_RECUT=1 ./deploy-server.sh` 用真域名重切 |
| 页面 https、资源 http | 内核直接 block，长得像「①不成立」 | 全站同源 HTTPS |
| hls.js 走 CDN 被微信拦 | `LOAD ★ hls.js 加载失败` | 放 `vendor/hls.min.js` |
| 微信 X5 缓存旧页面 | 改了代码手机上没变 | URL 后加 `&v=<随便一个数>`；或微信「设置-通用-存储空间-清理缓存」 |
| 只测了一台 Android | X5 与 XWEB 是两套内核 | 两台都要，或在同一台上切内核后各测一遍 |
| 判 ③ 只看日志 | 水印节点在、日志全绿，但画面上被视频盖住 | 必须看截图 |

---

## 六、写结论

填 `RESULT.md`。**二选一，不允许"待观察"**——
留一个"待观察"，就等于让模块 12/13/14 建立在一个仍未验证的假设上，
而 04 §D 写明这一项「越晚做越贵：模块 12 与 14 写完再撞车，付出的是删代码，不是改计划」。

若是"部分可行"（例如 Android 全过、iOS 水印在全屏时失效），也要落成**明确结论 + 处置建议**，
不能停在描述。**该决定要写进契约 §1，不能留在某个人的记忆里。**

---

## 七、测完收尾

**这台机器接下来要跑生产，POC 不该在上面留任何东西。** 结论填进 `RESULT.md` 之后就清掉。

**先把证据取回本地**（日志删了就没了，`RESULT.md` 要靠它填）：

```bash
scp -i ~/.ssh/edumatrix.pem -r root@114.215.196.24:/opt/r1a-poc/logs ./r1a-logs
```

**1）停服务、卸 unit**

```bash
systemctl disable --now r1a-poc && rm -f /etc/systemd/system/r1a-poc.service && systemctl daemon-reload
```

**2）删代码与数据**（含密钥、测试视频、日志）

```bash
rm -rf /opt/r1a-poc
```

**3）Caddy 怎么处理，二选一**

生产也打算用 Caddy —— 只把 POC 那段配置去掉，把之前备份的配置还原回去：

```bash
ls -1 /etc/caddy/Caddyfile.bak.* && systemctl reload caddy
```

（部署脚本若备份过原配置，文件名形如 `/etc/caddy/Caddyfile.bak.20260814145933`；
确认内容后 `cp` 回 `/etc/caddy/Caddyfile` 再 reload。没有备份文件说明这台机器上
Caddy 本来就是为 POC 装的，走下面那条。）

生产不用 Caddy —— 连 Caddy 一起卸掉，包括它自己加的 apt 源：

```bash
systemctl disable --now caddy && apt-get purge -y caddy && rm -f /etc/apt/sources.list.d/caddy-stable.list /usr/share/keyrings/caddy-stable-archive-keyring.gpg && apt-get autoremove -y
```

**4）ffmpeg / node 要不要卸**：生产用不上就一起卸，用得上就留着——
它们是 apt 装的正常包，不是 POC 特有的残留：

```bash
apt-get purge -y ffmpeg && apt-get autoremove -y
```

**5）控制台防火墙**：POC 结束后 80/443 如果生产还不需要，回控制台把这两条规则删掉。

**6）本地**：这个分支与 `poc/` 目录在结论签字后即可删除——
它是一次性验证物，不是工程的一部分。
