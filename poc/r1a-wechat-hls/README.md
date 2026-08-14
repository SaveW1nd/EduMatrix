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
├── README.md        本文件
├── make-hls.sh      ffmpeg 切一段 AES-128 加密 HLS；key URI 上带 ?token=
├── server.js        零依赖 node 服务：静态托管 + mock /key + /log 回收 + /admin/logs
├── index.html       测试页：hls.js + 水印 + MutationObserver + 禁拖拽 + 屏内诊断
└── RESULT.md        【空模板】真机测完由人填写，二选一，不允许"待观察"
```

产物（`media/`、`logs/`、`vendor/`、密钥）**一律不进 git**，见 `.gitignore`。

---

## 一、本机先跑通（10 分钟，排除工具自身的问题）

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

## 二、部署到你的服务器

### 前提

- 一个已备案子域名，指到这台机器，**HTTPS 证书已就绪**
- 服务器上有 `node`（18+）与 `ffmpeg`

> **必须全站 HTTPS 且同源。** 页面走 https 而 m3u8 或 `/key` 走 http，
> 会被内核当混合内容直接 block —— 那会伪装成「①不成立」，把一次验证浪费掉。

### 步骤

假设子域名是 `poc.example.com`，代码放在 `/opt/r1a-poc`。

**1）传代码**（只传这四个文件 + `.gitignore`，`media/` `vendor/` `logs/` 都在服务器上生成）

```bash
rsync -av --exclude media --exclude logs --exclude vendor poc/r1a-wechat-hls/ root@your-server:/opt/r1a-poc/
```

**2）在服务器上准备 hls.js**

```bash
cd /opt/r1a-poc && mkdir -p vendor && curl -L -o vendor/hls.min.js https://cdn.jsdelivr.net/npm/hls.js@1/dist/hls.min.js
```

**3）切流 —— `BASE_URL` 必须是最终对外域名**

```bash
cd /opt/r1a-poc && chmod +x make-hls.sh && ./make-hls.sh https://poc.example.com
```

> **这一步换域名就要重切。** key URI 是**绝对地址**、被写死进 m3u8，
> 用 localhost 切出来的流传到服务器上，播放器会去请求 `http://localhost:8080/key`
> —— 手机上那是它自己，必然失败，且长得很像「②不成立」。

**4）常驻**

```bash
cd /opt/r1a-poc && nohup node server.js > /opt/r1a-poc/stdout.log 2>&1 &
```

或用 pm2：

```bash
pm2 start /opt/r1a-poc/server.js --name r1a-poc
```

**5）nginx 反代（关键是 `.m3u8`/`.ts` 不能被中间层改 Content-Type，且 query 要原样透传）**

```nginx
server {
    listen 443 ssl http2;
    server_name poc.example.com;

    ssl_certificate     /path/fullchain.pem;
    ssl_certificate_key /path/privkey.pem;

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Host              $host;
        proxy_set_header X-Real-IP         $remote_addr;
        proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        # 诊断日志要实时可见，别攒着
        proxy_buffering off;
    }
}
server {
    listen 80;
    server_name poc.example.com;
    return 301 https://$host$request_uri;
}
```

**6）验一遍再拿手机**

```bash
curl -sI https://poc.example.com/media/index.m3u8 | grep -i content-type
```

应为 `application/vnd.apple.mpegurl`。再验 query 有没有被中间层吃掉：

```bash
curl -s -o /dev/null -w '%{http_code}\n' 'https://poc.example.com/key?token=TESTTOKEN123'
```

回 200，且服务器 stdout 里那条 `[KEY ]` 显示 `token在? YES`。

**7）出二维码，手机扫**

```bash
qrencode -t ANSIUTF8 'https://poc.example.com/'
```

没有 `qrencode` 就用任意在线工具把 `https://poc.example.com/` 转成二维码，
**在微信里扫**（不是浏览器里打开——要的就是微信内置浏览器）。

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
https://poc.example.com/
```

等价于 `?engine=auto&player=raw&inline=on&x5=on&wm=on&seekguard=on`。

**第 2 轮 · 关掉同层渲染属性**（"不行"和"某个属性没加所以不行"是两回事）

```
https://poc.example.com/?x5=off&inline=off
```

**第 3 轮 · 叠 DPlayer**（第 1 轮通过之后再做，看会不会引入新问题）

```
https://poc.example.com/?player=dplayer
```

> DPlayer 自己造 video 元素，页面会把**属性装配、拖拽守卫、事件监听整套搬到它的 video 上**，
> 并把 DPlayer 放进 stage 里的独立盒子、水印仍覆盖在其上——所以第 3 轮 ③④ 同样可判。
> 但 DPlayer 有自己的控件与全屏实现，行为可能与裸 hls.js 不同：
> 第 3 轮回答的是"叠上去会不会引入新问题"，**四条判定一律以第 1 轮 `player=raw` 为准**。

iOS 上如果第 1 轮显示走了原生路径，再单独跑一次强制对照：

```
https://poc.example.com/?engine=hlsjs        # 强制 hls.js，看是不是直接不支持
https://poc.example.com/?engine=native       # 强制原生，看原生路径下 key 参数还在不在
```

### 四件事逐条怎么判定

| # | 在哪看 | 什么算**过** | 什么算**不过** |
| --- | --- | --- | --- |
| ① | 屏内日志 + 画面 | 出现 `MANIFEST_PARSED` → `KEY_LOADED` → `FRAG_LOADED`，且**画面真的动了、有声音** | 出现 `HLS ★ 致命错误`、`keyLoadError`、`video error code=3/4`，或 `Hls.isSupported()=false` 且原生也播不出来 |
| ② | **`/admin/logs` 的 `[KEY]` 条目** | 服务端收到 `/key` 请求，且 `query={"token":"TESTTOKEN123"}`、`token在? YES` | 服务端**根本没收到** `/key`；或收到了但 `query={}`（参数被内核/中间层削掉）→ 身份通道断 |
| ③ | 人眼 + 屏内日志 | 播放中水印**肉眼可见地浮在画面之上**并在漂移；点「删除水印」和「隐藏水印」**两个按钮各测一次**，每次日志都立刻出 `WM ★ 水印被破坏 → 立即暂停播放` 且视频真的停了；**进全屏后水印仍在画面上** | 水印被视频盖住看不见（层级失效）；或进全屏后水印消失（日志里通常伴随 `FS ★ webkitbeginfullscreen`）；或篡改后视频照播不误 |
| ④ | 屏内日志 + 进度条 | 手指把原生进度条往**右**拖，日志出 `SEEK ★ 拦截向前拖拽 ... → 拉回`，且进度条弹回原位；点「前跳 +30s」后 `实际落点` ≈ 拖之前的位置；**全屏下同样拦得住** | 拖过去就过去了，日志没有拦截记录或记录了但落点仍是新位置；或全屏被系统播放器接管后完全拦不住 |

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

- **手机上**：页面下半部分就是日志，`★` 开头的都是关键判据；「复制日志」按钮可整段拷走
- **电脑上**：<https://poc.example.com/admin/logs> 实时看手机打回来的日志，
  勾「只看 /key」就是 ② 的全部证据
- **服务器上**：`logs/key-requests.jsonl`（每次密钥请求的完整请求头）、`logs/client.jsonl`

```bash
tail -f /opt/r1a-poc/logs/key-requests.jsonl
```

---

## 五、已知会污染结论的坑

| 坑 | 表现 | 处理 |
| --- | --- | --- |
| 用 localhost 切的流传到服务器 | key 请求打不到，长得像「②不成立」 | 用真域名重跑 `make-hls.sh` |
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
