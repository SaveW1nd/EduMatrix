# 参考开源仓库（References）

> 本目录存放四个参考仓库的浅克隆（`--depth 1`，仅最新代码，无历史），供开发团队对照源码做二开与架构设计。
> 云厂商 VOD（阿里云）无开源仓库，以官方文档为准，见文末链接。

## 仓库清单与借鉴指引

### 1. RuoYi-Vue-Plus — 系统基座 & 权限控制
- 仓库：https://github.com/dromara/RuoYi-Vue-Plus
- 借鉴模块 → EduMatrix 对应：`sys_*` 全部表、登录认证、RBAC、多租户、数据权限
- 重点阅读路径：
  - `ruoyi-common/ruoyi-common-tenant/` — 多租户插件（MyBatis-Plus TenantLineHandler），对应契约 §2.1
  - `ruoyi-common/ruoyi-common-mybatis/.../DataPermissionHelper` 与 `@DataPermission` 注解 — 数据权限（DataScope），对应"教师仅看自己班级"
  - `ruoyi-common/ruoyi-common-satoken/` — Sa-Token 登录与权限校验
  - `ruoyi-modules/ruoyi-system/` — 用户/角色/菜单 CRUD 的 Controller-Service-Mapper 分层范式

### 2. roncoo-education（领课教育）— 教务与课程结构
- 仓库：https://github.com/roncoo/roncoo-education
- 借鉴模块 → EduMatrix 对应：`crs_*` 课程/章节/课时表结构、教务逻辑、学习看板
- 重点阅读路径：
  - `roncoo-education-course/` — 课程(course)、章节(chapter)、课时(period) 三层表结构与增删改查
  - 课程与视频关联方式（course_video）→ 对应我们的 `crs_lesson.video_id → vod_video`
  - 用户学习记录表设计 → 对应 `vod_watch_progress`

### 3. mindskip/xzs（学之思）— 作业分发与题库系统
- 仓库：https://github.com/mindskip/xzs
- 借鉴模块 → EduMatrix 对应：`qb_*` / `hw_*` 全部表、判卷、批改、错题本
- 重点阅读路径：
  - `source/xzs/src/main/java/.../domain/question/` — 题目 content JSON 结构（题干/选项/答案分离存储）
  - 试卷表 `t_exam_paper` 与题目快照 `t_text_content` — 对应我们的版本固化思路（我们改进为 question_version 显式版本表）
  - 自动判卷逻辑（客观题比对）与主观题人工批改流转
  - 错题本 `t_task_exam` 相关实现 → 对应 `hw_wrong_book`

### 4. DPlayer — 前端视频播放与防刷
- 仓库：https://github.com/DIYgod/DPlayer
- 借鉴模块 → EduMatrix 对应：学生端播放器二开
- 重点阅读路径：
  - `src/js/player.js` — `seek()` 方法：二开时在此拦截，`目标位置 > maxPosition` 时禁止（禁快进，只允许回看）
  - `src/js/controller.js` — 进度条拖拽事件，需屏蔽/改写
  - 事件系统（`timeupdate`、`play`、`pause`、`ended`）— 10 秒心跳定时器的挂载点
  - `danmaku` 弹幕层实现 — 跑马灯水印可复用其浮动渲染思路（随机位置渲染"姓名+手机号"）
  - HLS 支持：DPlayer + hls.js 播放 `.m3u8`，配合 VOD 加密流

### 5. 云端 VOD（无仓库，文档参考）
- 阿里云 VOD：https://help.aliyun.com/product/29932.html （PlayAuth 播放凭证模式）
- 对应契约表：`vod_video`（provider 字段区分厂商），接口 `POST /api/v1/vod/upload-token`、`POST /api/v1/vod/play-auth`、`POST /api/v1/vod/callback/{provider}`

## 克隆维护

```bash
# 重新拉取全部参考仓库（浅克隆）
cd references
for repo in dromara/RuoYi-Vue-Plus roncoo/roncoo-education mindskip/xzs DIYgod/DPlayer; do
  d=$(basename "$repo"); [ -d "$d" ] || git clone --depth 1 --single-branch "https://github.com/$repo.git"
done
```
