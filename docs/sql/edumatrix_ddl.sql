-- ============================================================================
-- EduMatrix ToB 在线教育管理平台 - 全量数据库 DDL
-- ----------------------------------------------------------------------------
-- 日期        : 2026-08-12
-- 数据库      : MySQL 8.0+
-- 表总数      : 41 张（sys_10 + org_10 + crs_4 + vod_4 + qb_3 + hw_6 + stat_4，见契约 §9.1）
-- 核心模型     : 一棵树到底——平台超管/管理员/教师/学生全部是 org_node 上的节点
--               （node_type 0/1/2/3，取值与 sys_user.user_type 完全一致）；每个节点都是一个人，
--               不设不绑账号的组织单元节点，组织层级由管理员节点的嵌套表达。
--               树顶是唯一一行 id=0 的虚拟平台根（parent_id=-1、ancestors=''、tenant_id=0），
--               平台超管 sys_user.node_id=0，故"子树"规则对超管亦成立，无需特例分支（契约 §2.1）。
--               数据权限只有一条规则：你能看到的数据 = 你所在节点的子树（契约 §2.4）；
--               资源（课程/题目/视频）逐级显式下发、无继承，授权表 org_resource_grant（契约 §2.5）。
-- 统计口径     : stat_ 表一律落库【分子列 + 分母列】而非只存比率（比率不可跨天平均）；
--               统计列分两类：累计状态型（finished/should_lesson_count，区间取末日行、
--               严禁跨天求和）与当日流量型（*_submit_count、watch_seconds，区间求和）；
--               stat_node_daily 每行 = 该节点整棵子树的聚合（读大屏 1 行命中，
--               但父子行不可相加）。详见区块 7/7 块注释与 02-数据库设计.md §3.6。
-- 组织树结构约束（服务层必须强制校验，DDL 层无法表达，见 02-数据库设计.md 设计要点专栏）:
--   1) 教师节点(node_type=2)下只能挂学生节点(3)；学生节点必须是叶子；
--   2) 移动节点禁止成环：targetParentId != movingNodeId
--      AND FIND_IN_SET(movingNodeId, targetParent.ancestors) = 0；
--   3) 移动节点后必须在同一事务内递归重算整棵子树的 ancestors；
--   4) 树深度不设上限（ancestors 预留 VARCHAR(1000)，约可容纳 50 级）。
-- 子树查询性能策略（org_node.ancestors，重要）:
--   FIND_IN_SET(#{nodeId}, ancestors) 语义最准但【无法走索引】，全表扫描。
--   本 DDL 为此提供三条互补路径，服务层按场景选择：
--   a) 逐层展开（推荐，默认）：idx_tenant_parent_sort(tenant_id,parent_id,sort) 支撑
--      按 parent_id 逐层 IN 查询（树懒加载、面包屑、子树 BFS，单层毫秒级）；
--   b) 前缀 LIKE（子树一次性取全量时使用）：因 ancestors 为根在前的有序路径，
--      节点 X 的子树 = WHERE ancestors LIKE CONCAT(X.ancestors, ',', X.id, ',%')，
--      可命中 idx_ancestors(ancestors(255)) 前缀索引（左前缀匹配，非全表扫描）；
--   c) FIND_IN_SET 仅在已被 tenant_id 收敛到小结果集、或离线任务/巡检中使用。
--   移动节点批量重算 ancestors 同样走 b) 的前缀 LIKE 定位子树。
-- 字符集约定  : 库与全部表统一 utf8mb4 / utf8mb4_0900_ai_ci，ENGINE=InnoDB
-- 命名规范    : 表名/字段名全小写下划线；表前缀 sys_(系统基座)/org_(组织人员)/
--               crs_(课程)/vod_(视频进度)/qb_(题库)/hw_(作业)/stat_(数据中心)；
--               主键统一 id BIGINT（雪花算法，Java 侧生成，非自增，含日志/心跳明细表；
--               vod_heartbeat_log 为满足分区键约束采用 (id, created_time) 联合主键）；
--               唯一索引 uk_ 前缀，普通索引 idx_ 前缀。
-- 通用字段    : 所有业务表表尾统一携带 tenant_id / create_by / create_time /
--               update_by / update_time / deleted_at / remark（契约 2.2）；
--               sys_tenant / sys_menu 为平台级表不带 tenant_id；
--               日志/心跳明细表（sys_login_log / sys_oper_log / vod_play_auth_log /
--               vod_heartbeat_log）按契约 2.2 例外精简 create_by / update_by / remark，
--               允许按时间/分区物理归档清理；其中 sys_login_log / sys_oper_log 另以业务
--               时间列（login_time / oper_time）取代 create_time，vod_play_auth_log /
--               vod_heartbeat_log 保留 create_time / created_time 作为业务时间；
--               仅 vod_heartbeat_log 按契约第 4 节标注不带 deleted_at，
--               其余日志表仍携带 deleted_at（业务上恒为 0，清理走物理归档）。
-- 逻辑删除与唯一索引冲突处理（本文件统一采用【方案A：唯一索引末尾追加 deleted_at】）:
--   业务表禁止物理删除：deleted_at 为 0 表示未删除，删除时写入当前毫秒时间戳
--   （UNIX_TIMESTAMP(NOW(3))*1000），不是 0/1 布尔标志。若唯一索引不含 deleted_at，
--   软删后再新建同键记录会触发唯一冲突，故本文件所有含 deleted_at 表的唯一索引
--   一律在末尾追加 deleted_at 列。
--   用时间戳而非 0/1 的原因：0/1 方案下同一业务键最多只能容纳一条已删除行，
--   "打标→去标→再打标→再去标"到第二次去标即撞唯一键，而这类反复增删在
--   org_student_tag / sys_user_role / hw_answer_detail 上是常规操作；时间戳每次
--   取值不同，可容纳任意多条已删除行，并白得一个删除时间用于审计。
--   MyBatis-Plus 配置：@TableLogic(value="0", delval="UNIX_TIMESTAMP(NOW(3))*1000")。
-- vod_heartbeat_log 分区维护策略:
--   按月 RANGE(TO_DAYS(created_time)) 分区，预建 2026-08 ~ 2027-01 共 6 个分区 + pmax。
--   运维需配置每月定时任务（XXL-Job / 事件调度）：
--   1) 提前 1 个月执行 ALTER TABLE ... REORGANIZE PARTITION pmax INTO (新月分区, pmax) 追加新分区；
--   2) 数据保留 6 个月，过期分区先经 stat_ 汇总确认后执行 ALTER TABLE ... DROP PARTITION 秒级清理；
--   3) 严禁对该表执行 DELETE 大批量删除，一律走分区裁剪。
-- 执行说明    : 本文件可在 MySQL 8.0 中自上而下直接顺序执行；
--               所有 DATETIME 缺省值使用 CURRENT_TIMESTAMP 语法；
--               未使用任何保留字作为字段名（心跳当前位置字段按契约命名 current_time_sec）。
-- ============================================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

CREATE DATABASE IF NOT EXISTS `edumatrix` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
USE `edumatrix`;

-- ============================================================================
-- 区块 1/7：sys_ 系统基座（10 张）
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1. sys_tenant 机构（租户）表：机构=租户，本表 id 即全系统 tenant_id（平台级表，不带 tenant_id）
-- ----------------------------------------------------------------------------
DROP TABLE IF EXISTS `sys_tenant`;
CREATE TABLE `sys_tenant` (
  `id`                BIGINT       NOT NULL                COMMENT '租户ID（雪花算法，即全系统 tenant_id；契约 2.1：其值 = 该机构在 org_node 上的根节点 id）',
  `root_node_id`      BIGINT       NULL DEFAULT NULL       COMMENT '机构根节点ID（→org_node.id，值与本表 id 相同）。【必须允许 NULL】：开通租户存在循环依赖（根节点的 tenant_id 来自租户行 id，租户行的 root_node_id 来自根节点 id），落库顺序固定为「插租户行(root_node_id 暂空) → 插机构根节点 → 回写 root_node_id」，三步同一事务（契约 2.1）',
  `name`              VARCHAR(100) NOT NULL                COMMENT '机构名称',
  `contact_name`      VARCHAR(50)  NULL DEFAULT NULL       COMMENT '联系人姓名',
  `contact_phone`     VARCHAR(20)  NULL DEFAULT NULL       COMMENT '联系人手机号',
  `expire_time`       DATETIME     NULL DEFAULT NULL       COMMENT '服务到期时间（NULL=永久）',
  `status`            TINYINT      NOT NULL DEFAULT 0      COMMENT '租户状态：0正常 1停用',
  `max_student_count` INT          NOT NULL DEFAULT 0      COMMENT '学生账号数上限（0=不限制）',
  `create_by`         BIGINT       NULL DEFAULT NULL       COMMENT '创建人 user_id',
  `create_time`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by`         BIGINT       NULL DEFAULT NULL       COMMENT '更新人 user_id',
  `update_time`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted_at`        BIGINT      NOT NULL DEFAULT 0      COMMENT '逻辑删除标记：0=未删除；删除时写入毫秒时间戳。用时间戳而非 0/1，使同一业务键可容纳任意多条已删除行（唯一索引末尾追加本列）',
  `remark`            VARCHAR(500) NULL DEFAULT NULL       COMMENT '备注',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_name` (`name`, `deleted_at`) COMMENT '机构名称唯一（追加 deleted_at 兼容逻辑删除）',
  KEY `idx_status_expire` (`status`, `expire_time`) COMMENT '平台超管租户列表按状态/到期时间筛选',
  KEY `idx_root_node_id` (`root_node_id`) COMMENT '由机构根节点反查租户（组织树自上而下渲染时定位租户信息）'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '机构（租户）表';

-- ----------------------------------------------------------------------------
-- 2. sys_user 统一账号表：管理员/教师/学生共用登录体系（平台超管 tenant_id=0）
-- ----------------------------------------------------------------------------
DROP TABLE IF EXISTS `sys_user`;
CREATE TABLE `sys_user` (
  `id`              BIGINT       NOT NULL                COMMENT '用户ID（雪花算法）',
  `username`        VARCHAR(50)  NOT NULL                COMMENT '登录账号（全平台唯一）',
  `password`        VARCHAR(100) NOT NULL                COMMENT '登录密码（BCrypt 密文，永不明文存储）',
  `user_type`       TINYINT      NOT NULL                COMMENT '用户类型：0平台超管 1管理员 2教师 3学生',
  `real_name`       VARCHAR(50)  NOT NULL                COMMENT '真实姓名',
  `phone`           VARCHAR(20)  NULL DEFAULT NULL       COMMENT '手机号（视频跑马灯水印展示用）',
  `avatar`          VARCHAR(500) NULL DEFAULT NULL       COMMENT '头像 URL',
  `node_id`         BIGINT       NOT NULL                COMMENT '所在节点ID（→org_node.id，与 org_node.ref_user_id 互为反向引用）：契约 2.4 数据权限唯一起点——可见范围 = 本节点子树；每个人（含平台超管）都在树上，建号即建节点',
  `status`          TINYINT      NOT NULL DEFAULT 0      COMMENT '账号状态：0正常 1停用',
  `pwd_reset_flag`  TINYINT      NOT NULL DEFAULT 0      COMMENT '是否需强制修改密码：0否 1是（管理员重置密码/批量导入初始密码后置 1，登录后强制改密）',
  `last_login_time` DATETIME     NULL DEFAULT NULL       COMMENT '最后登录时间',
  `tenant_id`       BIGINT       NOT NULL DEFAULT 0      COMMENT '租户（机构）ID，平台超管为 0',
  `create_by`       BIGINT       NULL DEFAULT NULL       COMMENT '创建人 user_id',
  `create_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by`       BIGINT       NULL DEFAULT NULL       COMMENT '更新人 user_id',
  `update_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted_at`      BIGINT      NOT NULL DEFAULT 0      COMMENT '逻辑删除标记：0=未删除；删除时写入毫秒时间戳。用时间戳而非 0/1，使同一业务键可容纳任意多条已删除行（唯一索引末尾追加本列）',
  `remark`          VARCHAR(500) NULL DEFAULT NULL       COMMENT '备注',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_username` (`username`, `deleted_at`) COMMENT '登录账号全平台唯一（错误码 10001 依据）',
  KEY `idx_tenant_type_status` (`tenant_id`, `user_type`, `status`) COMMENT '机构内按角色类型分页/统计（管理员列表、教师总数、停用账号巡检）',
  KEY `idx_node_id` (`node_id`) COMMENT '按节点反查账号（节点删除/移动前校验、登录时装配数据权限起点）',
  UNIQUE KEY `uk_tenant_phone` (`tenant_id`, `phone`, `deleted_at`) COMMENT '手机号租户内唯一（错误码 10013 依据）。phone 可为 NULL（管理员可不填），MySQL 唯一索引不约束 NULL，正好符合需求；缺此约束时并发创建必产生重复手机号，而学生 username 默认取手机号会导致后插者撞 uk_username 报 10001，提示与实际原因不符'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '统一账号表（管理员/教师/学生共用登录体系）';

-- ----------------------------------------------------------------------------
-- 3. sys_role 角色表（tenant_id=0 的四条内置角色为平台预置，随租户开通引用）
--     无 data_scope 字段——契约 §3「操作权限由角色定、数据范围由树定」，
--     全系统只有一条数据权限规则（本节点子树），不存在角色级数据档位。
-- ----------------------------------------------------------------------------
DROP TABLE IF EXISTS `sys_role`;
CREATE TABLE `sys_role` (
  `id`          BIGINT       NOT NULL                COMMENT '角色ID（雪花算法）',
  `role_name`   VARCHAR(50)  NOT NULL                COMMENT '角色名称',
  `role_key`    VARCHAR(50)  NOT NULL                COMMENT '角色标识（仅决定操作权限，不决定数据范围）：super_admin平台超管 org_admin管理员 teacher教师/导师 student学生',
  `status`      TINYINT      NOT NULL DEFAULT 0      COMMENT '角色状态：0正常 1停用',
  `sort`        INT          NOT NULL DEFAULT 0      COMMENT '显示顺序（升序）',
  `tenant_id`   BIGINT       NOT NULL DEFAULT 0      COMMENT '租户（机构）ID，平台内置角色为 0，租户自建角色为其 tenant_id。【契约 §2.9】本表的租户插件注入条件必须是 (tenant_id = ? OR tenant_id = 0)——租户用户加载 roles/perms 走 sys_user_role→sys_role→sys_role_menu，只按等式过滤会命中 0 行、全员零权限；但不可改用 ignoreTable 整表忽略，否则租户 A 能列出并改删租户 B 自建的角色',
  `create_by`   BIGINT       NULL DEFAULT NULL       COMMENT '创建人 user_id',
  `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by`   BIGINT       NULL DEFAULT NULL       COMMENT '更新人 user_id',
  `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted_at`  BIGINT      NOT NULL DEFAULT 0      COMMENT '逻辑删除标记：0=未删除；删除时写入毫秒时间戳。用时间戳而非 0/1，使同一业务键可容纳任意多条已删除行（唯一索引末尾追加本列）',
  `remark`      VARCHAR(500) NULL DEFAULT NULL       COMMENT '备注',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_role_key` (`tenant_id`, `role_key`, `deleted_at`) COMMENT '同租户内角色标识唯一（追加 deleted_at 兼容逻辑删除）'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '角色表（RBAC）';

-- ----------------------------------------------------------------------------
-- 4. sys_user_role 用户-角色关联表
-- ----------------------------------------------------------------------------
DROP TABLE IF EXISTS `sys_user_role`;
CREATE TABLE `sys_user_role` (
  `id`          BIGINT       NOT NULL                COMMENT '主键ID（雪花算法）',
  `user_id`     BIGINT       NOT NULL                COMMENT '用户ID（→sys_user.id）',
  `role_id`     BIGINT       NOT NULL                COMMENT '角色ID（→sys_role.id）',
  `tenant_id`   BIGINT       NOT NULL DEFAULT 0      COMMENT '租户（机构）ID，平台超管绑定记录为 0',
  `create_by`   BIGINT       NULL DEFAULT NULL       COMMENT '创建人 user_id',
  `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by`   BIGINT       NULL DEFAULT NULL       COMMENT '更新人 user_id',
  `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted_at`  BIGINT      NOT NULL DEFAULT 0      COMMENT '逻辑删除标记：0=未删除；删除时写入毫秒时间戳。用时间戳而非 0/1，使同一业务键可容纳任意多条已删除行（唯一索引末尾追加本列）',
  `remark`      VARCHAR(500) NULL DEFAULT NULL       COMMENT '备注',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_role` (`user_id`, `role_id`, `deleted_at`) COMMENT '同一用户不可重复绑定同一角色（追加 deleted_at 兼容逻辑删除）',
  KEY `idx_role_id` (`role_id`) COMMENT '按角色反查用户列表'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '用户-角色关联表';

-- ----------------------------------------------------------------------------
-- 5. sys_menu 菜单/按钮权限表（平台级表，无 tenant_id）
-- ----------------------------------------------------------------------------
DROP TABLE IF EXISTS `sys_menu`;
CREATE TABLE `sys_menu` (
  `id`          BIGINT       NOT NULL                COMMENT '菜单ID（雪花算法）',
  `parent_id`   BIGINT       NOT NULL DEFAULT 0      COMMENT '父菜单ID（0=顶级）',
  `menu_name`   VARCHAR(50)  NOT NULL                COMMENT '菜单/按钮名称',
  `menu_type`   CHAR(1)      NOT NULL                COMMENT '类型：M目录 C菜单 F按钮',
  `perms`       VARCHAR(100) NULL DEFAULT NULL       COMMENT '权限标识（如 org:student:import）',
  `path`        VARCHAR(200) NULL DEFAULT NULL       COMMENT '前端路由地址（按钮类型为空）',
  `icon`        VARCHAR(100) NULL DEFAULT NULL       COMMENT '菜单图标',
  `sort`        INT          NOT NULL DEFAULT 0      COMMENT '显示顺序（升序）',
  `visible`     TINYINT      NOT NULL DEFAULT 1      COMMENT '是否显示：0隐藏 1显示',
  `status`      TINYINT      NOT NULL DEFAULT 0      COMMENT '菜单状态：0正常 1停用',
  `create_by`   BIGINT       NULL DEFAULT NULL       COMMENT '创建人 user_id',
  `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by`   BIGINT       NULL DEFAULT NULL       COMMENT '更新人 user_id',
  `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted_at`  BIGINT      NOT NULL DEFAULT 0      COMMENT '逻辑删除标记：0=未删除；删除时写入毫秒时间戳。用时间戳而非 0/1，使同一业务键可容纳任意多条已删除行（唯一索引末尾追加本列）',
  `remark`      VARCHAR(500) NULL DEFAULT NULL       COMMENT '备注',
  PRIMARY KEY (`id`),
  KEY `idx_parent_id` (`parent_id`, `sort`) COMMENT '构建菜单树按父节点取子节点'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '菜单/按钮权限表（平台级，无 tenant_id）';

-- ----------------------------------------------------------------------------
-- 6. sys_role_menu 角色-菜单关联表
-- ----------------------------------------------------------------------------
DROP TABLE IF EXISTS `sys_role_menu`;
CREATE TABLE `sys_role_menu` (
  `id`          BIGINT       NOT NULL                COMMENT '主键ID（雪花算法）',
  `role_id`     BIGINT       NOT NULL                COMMENT '角色ID（→sys_role.id）',
  `menu_id`     BIGINT       NOT NULL                COMMENT '菜单ID（→sys_menu.id）',
  `tenant_id`   BIGINT       NOT NULL DEFAULT 0      COMMENT '租户（机构）ID，平台内置角色的菜单绑定为 0。【契约 §2.9】本表的租户插件注入条件必须是 (tenant_id = ? OR tenant_id = 0)——否则租户用户加载 perms 时命中 0 行，所有权限校验 403，系统开箱不可用；但不可改用 ignoreTable 整表忽略，本表同时存租户自建角色的绑定',
  `create_by`   BIGINT       NULL DEFAULT NULL       COMMENT '创建人 user_id',
  `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by`   BIGINT       NULL DEFAULT NULL       COMMENT '更新人 user_id',
  `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted_at`  BIGINT      NOT NULL DEFAULT 0      COMMENT '逻辑删除标记：0=未删除；删除时写入毫秒时间戳。用时间戳而非 0/1，使同一业务键可容纳任意多条已删除行（唯一索引末尾追加本列）',
  `remark`      VARCHAR(500) NULL DEFAULT NULL       COMMENT '备注',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_role_menu` (`role_id`, `menu_id`, `deleted_at`) COMMENT '同一角色不可重复绑定同一菜单（追加 deleted_at 兼容逻辑删除）。【勿加 tenant_id】role_id 是雪花 ID、全局唯一，一个角色只属于一个租户，(role_id,menu_id) 跨租户不可能碰撞；加上 tenant_id 只会让同一角色在不同 tenant_id 下重复绑定同一菜单成为合法，反而削弱约束',
  KEY `idx_menu_id` (`menu_id`) COMMENT '按菜单反查授权角色'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '角色-菜单关联表';

-- ----------------------------------------------------------------------------
-- 7. sys_file 文件表（附件/图文资料图片/导入导出文件）
-- ----------------------------------------------------------------------------
DROP TABLE IF EXISTS `sys_file`;
CREATE TABLE `sys_file` (
  `id`          BIGINT       NOT NULL                COMMENT '文件ID（雪花算法）',
  `file_name`   VARCHAR(255) NOT NULL                COMMENT '原始文件名',
  `file_url`    VARCHAR(500) NOT NULL                COMMENT '访问 URL / 存储路径',
  `file_size`   BIGINT       NOT NULL DEFAULT 0      COMMENT '文件大小（字节）',
  `file_type`   VARCHAR(50)  NULL DEFAULT NULL       COMMENT '文件扩展名/MIME 类型（如 xlsx、image/png）',
  `storage`     TINYINT      NOT NULL DEFAULT 2      COMMENT '存储位置：1本地 2OSS',
  `biz_type`    VARCHAR(50)  NULL DEFAULT NULL       COMMENT '业务类型（course_cover课程封面 material_image图文图片 material_attach图文附件 import_excel导入文件 fail_report导入失败报告 credential_sheet导入账号密码表 export_report导出报表 avatar头像 answer作答附件 common其他）',
  `tenant_id`   BIGINT       NOT NULL DEFAULT 0      COMMENT '租户（机构）ID',
  `create_by`   BIGINT       NULL DEFAULT NULL       COMMENT '创建人（上传人）user_id',
  `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by`   BIGINT       NULL DEFAULT NULL       COMMENT '更新人 user_id',
  `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted_at`  BIGINT      NOT NULL DEFAULT 0      COMMENT '逻辑删除标记：0=未删除；删除时写入毫秒时间戳。用时间戳而非 0/1，使同一业务键可容纳任意多条已删除行（唯一索引末尾追加本列）',
  `remark`      VARCHAR(500) NULL DEFAULT NULL       COMMENT '备注',
  PRIMARY KEY (`id`),
  KEY `idx_tenant_biz` (`tenant_id`, `biz_type`, `create_time`) COMMENT '机构内按业务类型检索文件'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '文件表（附件/图文资料图片/导入导出文件）';

-- ----------------------------------------------------------------------------
-- 8. sys_login_log 登录日志表（日志表：雪花主键，允许物理归档清理，按契约例外精简 update_by/remark）
-- ----------------------------------------------------------------------------
DROP TABLE IF EXISTS `sys_login_log`;
CREATE TABLE `sys_login_log` (
  `id`          BIGINT       NOT NULL                COMMENT '日志ID（雪花算法）',
  `user_id`     BIGINT       NULL DEFAULT NULL       COMMENT '用户ID（登录失败且账号不存在时为 NULL）',
  `username`    VARCHAR(50)  NULL DEFAULT NULL       COMMENT '尝试登录的账号',
  `ip`          VARCHAR(64)  NULL DEFAULT NULL       COMMENT '登录 IP',
  `user_agent`  VARCHAR(500) NULL DEFAULT NULL       COMMENT '浏览器 UA',
  `status`      TINYINT      NOT NULL DEFAULT 0      COMMENT '登录结果：0成功 1失败',
  `msg`         VARCHAR(255) NULL DEFAULT NULL       COMMENT '结果描述（如 密码错误/验证码错误）',
  `login_time`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '登录时间',
  `tenant_id`   BIGINT       NOT NULL DEFAULT 0      COMMENT '租户（机构）ID',
  `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted_at`  BIGINT      NOT NULL DEFAULT 0      COMMENT '逻辑删除：0=未删除，删除时写入毫秒时间戳（日志业务恒为 0，清理走物理归档）',
  PRIMARY KEY (`id`),
  KEY `idx_user_time` (`user_id`, `login_time`) COMMENT '查询某用户登录轨迹',
  KEY `idx_tenant_time` (`tenant_id`, `login_time`) COMMENT '机构维度登录日志分页与按时间归档清理'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '登录日志表（可按时间归档清理）';

-- ----------------------------------------------------------------------------
-- 9. sys_oper_log 操作日志表（日志表：雪花主键，允许物理归档清理，按契约例外精简 update_by/remark）
-- ----------------------------------------------------------------------------
DROP TABLE IF EXISTS `sys_oper_log`;
CREATE TABLE `sys_oper_log` (
  `id`          BIGINT        NOT NULL                COMMENT '日志ID（雪花算法）',
  `user_id`     BIGINT        NULL DEFAULT NULL       COMMENT '操作人 user_id',
  `module`      VARCHAR(50)   NULL DEFAULT NULL       COMMENT '业务模块（如 学生管理/作业管理）',
  `action`      VARCHAR(50)   NULL DEFAULT NULL       COMMENT '操作动作（如 新增/修改/删除/导出）',
  `method`      VARCHAR(200)  NULL DEFAULT NULL       COMMENT '请求方法（HTTP 方法 + 路径或 Java 方法签名）',
  `params`      TEXT          NULL                    COMMENT '请求参数（JSON 文本，敏感字段已脱敏）',
  `ip`          VARCHAR(64)   NULL DEFAULT NULL       COMMENT '操作 IP',
  `status`      TINYINT       NOT NULL DEFAULT 0      COMMENT '执行结果：0成功 1失败',
  `error_msg`   VARCHAR(2000) NULL DEFAULT NULL       COMMENT '失败异常信息',
  `cost_ms`     INT           NOT NULL DEFAULT 0      COMMENT '执行耗时（毫秒）',
  `oper_time`   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',
  `tenant_id`   BIGINT        NOT NULL DEFAULT 0      COMMENT '租户（机构）ID',
  `update_time` DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted_at`  BIGINT       NOT NULL DEFAULT 0      COMMENT '逻辑删除：0=未删除，删除时写入毫秒时间戳（日志业务恒为 0，清理走物理归档）',
  PRIMARY KEY (`id`),
  KEY `idx_user_time` (`user_id`, `oper_time`) COMMENT '查询某用户操作轨迹',
  KEY `idx_tenant_module_time` (`tenant_id`, `module`, `oper_time`) COMMENT '机构维度按模块/时间检索操作日志'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '操作日志表（可按时间归档清理）';

-- ----------------------------------------------------------------------------
-- 10. sys_tenant_config 租户配置表（承载"租户可配"业务参数：complete_rate_threshold 等；
--     服务层读取时优先取本租户配置，无记录则回落平台默认值）
-- ----------------------------------------------------------------------------
DROP TABLE IF EXISTS `sys_tenant_config`;
CREATE TABLE `sys_tenant_config` (
  `id`           BIGINT       NOT NULL                COMMENT '配置ID（雪花算法）',
  `config_key`   VARCHAR(50)  NOT NULL                COMMENT '配置键（如 complete_rate_threshold）',
  `config_value` VARCHAR(200) NOT NULL                COMMENT '配置值（字符串存储，服务层按键解析类型与取值范围）',
  `tenant_id`    BIGINT       NOT NULL                COMMENT '租户（机构）ID',
  `create_by`    BIGINT       NULL DEFAULT NULL       COMMENT '创建人 user_id',
  `create_time`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by`    BIGINT       NULL DEFAULT NULL       COMMENT '更新人 user_id',
  `update_time`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted_at`   BIGINT      NOT NULL DEFAULT 0      COMMENT '逻辑删除标记：0=未删除；删除时写入毫秒时间戳。用时间戳而非 0/1，使同一业务键可容纳任意多条已删除行（唯一索引末尾追加本列）',
  `remark`       VARCHAR(500) NULL DEFAULT NULL       COMMENT '备注',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_config_key` (`tenant_id`, `config_key`, `deleted_at`) COMMENT '同租户同配置键唯一（配置读写按此点查/UPSERT，追加 deleted_at 兼容逻辑删除）'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '租户配置表（租户级可配业务参数）';

-- ============================================================================
-- 区块 2/7：org_ 组织与人员（10 张）
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 11. org_node 统一组织树表【全系统核心表】
--     一棵树到底：平台超管、管理员、教师、学生全部是本表的节点（每个节点都是一个人）
--     数据权限唯一规则（契约 2.4）：你能看到的数据 = 你所在节点的子树
--       id = #{myNodeId} OR FIND_IN_SET(#{myNodeId}, ancestors)
--     平台根节点（契约 2.1）：全表唯一一行 id=0 的平台超管节点（node_type=0、tenant_id=0、
--       parent_id=-1、ancestors=''）。平台超管 sys_user.node_id=0，其子树 = 全平台，
--       因此上面那条规则对超管同样成立，全系统无需为超管写特例分支。
--     结构约束（服务层强制，DDL 无法表达）：
--       · node_type=0(平台超管) 的子节点只能是 node_type=1(管理员)，即各机构的最高管理员；
--       · node_type=1(管理员) 可挂 1/2/3（下级管理员、教师、学生）；
--       · node_type=2(教师) 的子节点只能是 node_type=3(学生)；
--       · node_type=3(学生) 必须是叶子，不得有任何子节点；
--       · 移动前防成环：targetParentId != movingNodeId
--         AND FIND_IN_SET(movingNodeId, targetParent.ancestors) = 0；
--       · 移动后同一事务内递归重算整棵子树 ancestors。
--     子树查询性能策略（见文件头「子树查询性能策略」详述）：
--       FIND_IN_SET 无法走索引 → 默认走 idx_tenant_parent_sort 逐层展开；
--       需一次性取全子树时走 idx_ancestors 前缀 LIKE：
--       WHERE ancestors LIKE CONCAT(X.ancestors, ',', X.id, ',%')。
-- ----------------------------------------------------------------------------
DROP TABLE IF EXISTS `org_node`;
CREATE TABLE `org_node` (
  `id`             BIGINT        NOT NULL                COMMENT '节点ID（雪花算法；两个特例：平台根节点固定 id=0；机构根节点的 id 即该租户的 tenant_id，契约 2.1）',
  `parent_id`      BIGINT        NOT NULL DEFAULT 0      COMMENT '父节点ID（-1=平台根节点自身，全表唯一；0=其父为平台根，即机构最高管理员节点；其余为上级节点 id：管理员的父为上级管理员，教师的父为管理员，学生的父为管理员或教师）',
  `ancestors`      VARCHAR(1000) NOT NULL DEFAULT '0'    COMMENT '祖级路径（逗号串，根在前、不含本节点，如 0,100,101,205；平台根节点自身为空串 ''''）：子树判定 FIND_IN_SET(#{nodeId},ancestors)；批量取子树用 LIKE CONCAT(ancestors,'','',id,'',%'') 走前缀索引；深度不设上限，1000 字符约容纳 50 级',
  `node_name`      VARCHAR(100)  NOT NULL                COMMENT '节点名称（管理员节点可命名为机构名/校区名以表达组织层级；管理员/教师/学生节点填其真实姓名，与 sys_user.real_name 同步）',
  `node_type`      TINYINT       NOT NULL                COMMENT '节点类型：0平台超管 1管理员 2教师 3学生（契约 §5，取值与 sys_user.user_type 完全一致）。承载规则：0只挂1；1可挂1/2/3；2只挂3；3为叶子。不设独立于人的组织单元节点——组织层级由管理员节点的嵌套表达',
  `ref_user_id`    BIGINT        NOT NULL                COMMENT '关联账号 user_id（→sys_user.id）：每个节点都是一个人，故全部非空，与 sys_user.node_id 互为反向引用',
  `sort`           INT           NOT NULL DEFAULT 0      COMMENT '同级显示顺序（升序）',
  `status`         TINYINT       NOT NULL DEFAULT 0      COMMENT '节点状态：0正常 1停用（停用不改变树结构，仅禁止其账号登录与被分配）',
  `child_count`    INT           NOT NULL DEFAULT 0      COMMENT '直接子节点数（冗余计数，增删/移动子节点时同步维护；>0 时禁止删除本节点）',
  `student_count`  INT           NOT NULL DEFAULT 0      COMMENT '子树内在读学生节点总数（冗余计数：教师节点=名下学员数，管理员节点=其子树学员总数；异动时自底向上逐级维护）',
  `tenant_id`      BIGINT        NOT NULL                COMMENT '租户（机构）ID，平台根节点为 0',
  `create_by`      BIGINT        NULL DEFAULT NULL       COMMENT '创建人 user_id',
  `create_time`    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by`      BIGINT        NULL DEFAULT NULL       COMMENT '更新人 user_id',
  `update_time`    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted_at`     BIGINT       NOT NULL DEFAULT 0      COMMENT '逻辑删除：0=未删除，删除时写入毫秒时间戳（child_count>0 时禁止删除，须先移走子节点）',
  `remark`         VARCHAR(500)  NULL DEFAULT NULL       COMMENT '备注',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ref_user_id` (`ref_user_id`, `deleted_at`) COMMENT '一个账号在树上仅占一个节点（用户→节点反查亦走此索引最左前缀；ref_user_id 已收敛为 NOT NULL，本索引因此是强约束，不存在 NULL 绕过）',
  KEY `idx_tenant_parent_sort` (`tenant_id`, `parent_id`, `sort`) COMMENT '【子树查询主路径】按父节点逐层展开：组织树懒加载、子树 BFS 遍历、机构内定位根节点（parent_id=0）；FIND_IN_SET 无法走索引，逐层查询是默认策略',
  KEY `idx_parent_type` (`parent_id`, `node_type`) COMMENT '某节点下按类型取人：管理员页"我下面的教师列表"、教师页"我名下的学员列表"（高频）',
  KEY `idx_ancestors` (`ancestors`(255)) COMMENT '【子树查询备选路径】ancestors 前缀 LIKE 一次性取全子树（LIKE ''0,100,101,%'' 可命中左前缀）；亦用于移动节点时按路径前缀批量重算子树 ancestors',
  KEY `idx_tenant_type_status` (`tenant_id`, `node_type`, `status`) COMMENT '机构内按类型统计/分页（教师总数、学生总数、停用节点巡检）；登录时判定祖先链是否有 node_type=1 且 status=1 的管理员（分支冻结）亦用本索引'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '统一组织树表（平台超管/管理员/教师/学生同树，每个节点都是一个人，数据权限唯一依据）';

-- ----------------------------------------------------------------------------
-- 12. org_teacher 教师档案表（与 org_node 节点 1:1、与 sys_user 1:1）
--     教师即一个 node_type=2 的树节点，其直接子节点就是"名下学员"，
--     师生关系只由树的父子结构表达，不设独立的师生关系表。
--     本表只承载"档案属性"（工号/科目/职称），不承载任何权限或归属语义。
-- ----------------------------------------------------------------------------
DROP TABLE IF EXISTS `org_teacher`;
CREATE TABLE `org_teacher` (
  `id`            BIGINT       NOT NULL                COMMENT '教师ID（雪花算法）',
  `node_id`       BIGINT       NOT NULL                COMMENT '教师节点ID（→org_node.id，node_type=2，1:1；名下学员 = 该节点的直接子节点）',
  `user_id`       BIGINT       NOT NULL                COMMENT '账号ID（→sys_user.id，1:1）',
  `teacher_no`    VARCHAR(50)  NULL DEFAULT NULL       COMMENT '教师工号（机构内编号）',
  `subject`       VARCHAR(50)  NULL DEFAULT NULL       COMMENT '任教科目（如 数学/英语）',
  `title`         VARCHAR(50)  NULL DEFAULT NULL       COMMENT '职称（如 高级教师）',
  `entry_date`    DATE         NULL DEFAULT NULL       COMMENT '入职日期',
  `student_count` INT          NOT NULL DEFAULT 0      COMMENT '名下在读学员数（冗余计数，与 org_node.student_count 同源同步；分配/转交/调岗/归档时维护）',
  `tenant_id`     BIGINT       NOT NULL                COMMENT '租户（机构）ID',
  `create_by`     BIGINT       NULL DEFAULT NULL       COMMENT '创建人 user_id',
  `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by`     BIGINT       NULL DEFAULT NULL       COMMENT '更新人 user_id',
  `update_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted_at`    BIGINT      NOT NULL DEFAULT 0      COMMENT '逻辑删除：0=未删除，删除时写入毫秒时间戳（节点仍有子节点即名下仍有学员时禁止删除，须先移走学员）',
  `remark`        VARCHAR(500) NULL DEFAULT NULL       COMMENT '备注',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_node_id` (`node_id`, `deleted_at`) COMMENT '契约 UK(node_id)：一个教师节点仅一份档案（追加 deleted_at 兼容逻辑删除）',
  UNIQUE KEY `uk_user_id` (`user_id`, `deleted_at`) COMMENT '契约 UK(user_id)：一个账号仅对应一份教师档案（追加 deleted_at 兼容逻辑删除）',
  KEY `idx_tenant_teacher_no` (`tenant_id`, `teacher_no`) COMMENT '机构内按工号检索教师',
  KEY `idx_tenant_subject` (`tenant_id`, `subject`) COMMENT '机构内按任教科目筛选教师（分配学员时的候选导师列表）'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '教师档案表（1:1 org_node 教师节点 / 1:1 sys_user）';

-- ----------------------------------------------------------------------------
-- 13. org_student 学生档案表（与 org_node 节点 1:1、与 sys_user 1:1）
--     学生即一个 node_type=3 的叶子节点，归属完全由树的位置表达——
--     挂在管理员节点下 = 已归属该管理员但尚未分配导师；挂在教师节点下 = 该导师名下学员。
--     本表没有 node_id 之外的第二个归属字段：
--     "谁是我的导师" = 查 org_node.parent_id 且该父节点 node_type=2。
-- ----------------------------------------------------------------------------
DROP TABLE IF EXISTS `org_student`;
CREATE TABLE `org_student` (
  `id`             BIGINT       NOT NULL                COMMENT '学生ID（雪花算法）',
  `node_id`        BIGINT       NOT NULL                COMMENT '学生节点ID（→org_node.id，node_type=3，1:1；父节点即当前归属的管理员或导师）',
  `user_id`        BIGINT       NOT NULL                COMMENT '账号ID（→sys_user.id，1:1）',
  `student_no`     VARCHAR(50)  NULL DEFAULT NULL       COMMENT '学号（机构内编号）',
  `guardian_name`  VARCHAR(50)  NULL DEFAULT NULL       COMMENT '监护人姓名',
  `guardian_phone` VARCHAR(20)  NULL DEFAULT NULL       COMMENT '监护人手机号',
  `status`         TINYINT      NOT NULL DEFAULT 0      COMMENT '学籍状态：0在读 1已退课(流失) 2毕业归档（契约 §5 student_status）',
  `quit_time`      DATETIME     NULL DEFAULT NULL       COMMENT '退课时间（status=1 时写入）',
  `quit_reason`    VARCHAR(500) NULL DEFAULT NULL       COMMENT '退课原因（status=1 时写入，流失分析依据）',
  `archive_time`   DATETIME     NULL DEFAULT NULL       COMMENT '毕业归档时间（status=2 时写入）',
  `tenant_id`      BIGINT       NOT NULL                COMMENT '租户（机构）ID',
  `create_by`      BIGINT       NULL DEFAULT NULL       COMMENT '创建人 user_id',
  `create_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by`      BIGINT       NULL DEFAULT NULL       COMMENT '更新人 user_id',
  `update_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted_at`     BIGINT      NOT NULL DEFAULT 0      COMMENT '逻辑删除：0=未删除，删除时写入毫秒时间戳（学习记录禁止随之删除）',
  `remark`         VARCHAR(500) NULL DEFAULT NULL       COMMENT '备注',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_node_id` (`node_id`, `deleted_at`) COMMENT '契约 UK(node_id)：一个学生节点仅一份档案（组织树 → 学员档案的点查入口，追加 deleted_at 兼容逻辑删除）',
  UNIQUE KEY `uk_user_id` (`user_id`, `deleted_at`) COMMENT '契约 UK(user_id)：一个账号仅对应一份学生档案（追加 deleted_at 兼容逻辑删除）',
  KEY `idx_tenant_status` (`tenant_id`, `status`) COMMENT '机构内按学籍状态分页（在读/已退课/已毕业统计与列表）',
  KEY `idx_tenant_student_no` (`tenant_id`, `student_no`) COMMENT '机构内按学号检索学生'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '学生档案表（1:1 org_node 学生节点 / 1:1 sys_user，归属由树表达）';

-- ----------------------------------------------------------------------------
-- 14. org_node_change_log 节点异动轨迹表
--     所有异动都统一为"节点在树上的移动"，因此只需记录 from_parent_id → to_parent_id，
--     无需再区分"转导师/转节点"两套字段。是"这个人为什么归他管"的唯一追溯依据。
-- ----------------------------------------------------------------------------
DROP TABLE IF EXISTS `org_node_change_log`;
CREATE TABLE `org_node_change_log` (
  `id`             BIGINT       NOT NULL                COMMENT '主键ID（雪花算法）',
  `node_id`        BIGINT       NOT NULL                COMMENT '发生异动的节点ID（→org_node.id；教师调岗时为教师节点，其学员子树跟随移动但不逐个记录）',
  `change_type`    TINYINT      NOT NULL                COMMENT '异动类型：1建档 2分配导师 3转交管理员 4教师调岗 5毕业归档 6归档恢复 7退课 8节点移动（管理员节点自身改父）（契约 §5 change_type）',
  `from_parent_id` BIGINT       NULL DEFAULT NULL       COMMENT '原父节点ID（→org_node.id；change_type=1 建档时为 NULL）',
  `to_parent_id`   BIGINT       NULL DEFAULT NULL       COMMENT '新父节点ID（→org_node.id；仅状态类异动 5/6/7 且树位置未变时为 NULL）',
  `change_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '异动时间（归属结算以自然日切分，见契约 2.6：转导师当日归新导师）',
  `operator_id`    BIGINT       NULL DEFAULT NULL       COMMENT '操作人 user_id',
  `reason`         VARCHAR(500) NULL DEFAULT NULL       COMMENT '异动原因（如 导师离职/学员申请更换/校区调整）',
  `tenant_id`      BIGINT       NOT NULL                COMMENT '租户（机构）ID',
  `create_by`      BIGINT       NULL DEFAULT NULL       COMMENT '创建人 user_id',
  `create_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by`      BIGINT       NULL DEFAULT NULL       COMMENT '更新人 user_id',
  `update_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted_at`     BIGINT      NOT NULL DEFAULT 0      COMMENT '逻辑删除：0=未删除，删除时写入毫秒时间戳（轨迹业务上禁止删除，恒为 0）',
  `remark`         VARCHAR(500) NULL DEFAULT NULL       COMMENT '备注',
  PRIMARY KEY (`id`),
  KEY `idx_node_time` (`node_id`, `change_time`) COMMENT '学员/教师档案页展示异动时间线（高频）',
  KEY `idx_to_parent_time` (`to_parent_id`, `change_time`) COMMENT '按接收方查询期间转入的下级（导师/管理员业绩口径核对）',
  KEY `idx_from_parent_time` (`from_parent_id`, `change_time`) COMMENT '按转出方查询期间转出的下级（流失与人员流动分析）',
  KEY `idx_tenant_type_time` (`tenant_id`, `change_type`, `change_time`) COMMENT '机构内按异动类型统计（如当月退课数/转交数）'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '节点异动轨迹表（移动/分配/转交/归档统一记录）';

-- ----------------------------------------------------------------------------
-- 15. org_resource_grant 资源逐级下发授权表【全系统最高频鉴权表】
--     契约 2.5：受管资源 = 课程/题目/视频，每一级都必须显式授权，【不向下继承】。
--     判定"某节点能否使用某资源"只需一条命中、不回溯祖先链：
--       SELECT 1 FROM org_resource_grant
--        WHERE target_node_id = #{myNodeId} AND resource_type = 1 AND resource_id = #{id}
--          AND deleted_at = 0
--          AND (valid_start IS NULL OR valid_start <= NOW())
--          AND (valid_end   IS NULL OR valid_end   >= NOW())
--     该查询由 idx_target_type_valid 单索引命中（无回表回溯、无递归）。
--     授权约束（服务层强制）：
--       · 授权人必须已拥有该资源（owner_node_id 是自己 或 自己已被有效授权）；
--       · 目标节点必须在授权人子树内；
--       · 撤销必须【级联回收】：递归撤销目标节点整棵子树内该资源的全部授权行，
--         否则出现"父级已无权、子级仍持有"的悬挂授权（走 idx_resource_type_id 定位）；
--       · 学习记录（vod_watch_progress / hw_answer_sheet / hw_wrong_book）一律保留不删。
-- ----------------------------------------------------------------------------
DROP TABLE IF EXISTS `org_resource_grant`;
CREATE TABLE `org_resource_grant` (
  `id`             BIGINT       NOT NULL                COMMENT '主键ID（雪花算法）',
  `resource_type`  TINYINT      NOT NULL                COMMENT '受管资源类型：1课程 2题目 3视频（契约 §5 resource_type）',
  `resource_id`    BIGINT       NOT NULL                COMMENT '资源ID（resource_type=1→crs_course.id，=2→qb_question.id，=3→vod_video.id）',
  `target_node_id` BIGINT       NOT NULL                COMMENT '被授权的目标节点ID（→org_node.id；可以是管理员/教师/学生节点，逐级显式授权，无继承）',
  `valid_start`    DATETIME     NULL DEFAULT NULL       COMMENT '授权生效时间（NULL=立即生效）',
  `valid_end`      DATETIME     NULL DEFAULT NULL       COMMENT '授权失效时间（NULL=永久有效）',
  `grant_source`   TINYINT      NOT NULL DEFAULT 1      COMMENT '授权来源：1手动选择 2按节点批量 3按标签批量 4按名下全体 5按权限模板（契约 §5 grant_source）',
  `source_ref_id`  BIGINT       NULL DEFAULT NULL       COMMENT '来源对象ID（grant_source=2→org_node.id，=3→org_tag.id，=4→org_node.id(授权人节点)，=5→org_perm_template.id；=1 时为 NULL）',
  `grant_by`       BIGINT       NULL DEFAULT NULL       COMMENT '授权人 user_id',
  `grant_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '授权时间',
  `tenant_id`      BIGINT       NOT NULL                COMMENT '租户（机构）ID',
  `create_by`      BIGINT       NULL DEFAULT NULL       COMMENT '创建人 user_id',
  `create_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by`      BIGINT       NULL DEFAULT NULL       COMMENT '更新人 user_id',
  `update_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted_at`     BIGINT      NOT NULL DEFAULT 0      COMMENT '逻辑删除：0=未删除，删除时写入毫秒时间戳（撤销授权=置 1，级联回收对子树内同资源行批量置 1）',
  `remark`         VARCHAR(500) NULL DEFAULT NULL       COMMENT '备注',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_resource_target` (`resource_type`, `resource_id`, `target_node_id`, `deleted_at`) COMMENT '同一资源对同一节点仅一条有效授权。deleted_at 方案下反复授予/撤销不会撞键；重新授权仍建议 UPSERT 复活未删行以免脏行累积',
  KEY `idx_target_resource` (`target_node_id`, `resource_type`, `resource_id`, `deleted_at`, `valid_end`) COMMENT '【全系统最高频鉴权索引】点查（节点X对资源R有权吗）走前四列精确匹配+valid_end范围，1行命中；列表查（我能用哪些课程）走前三列前缀。原索引缺 resource_id，点查需扫该节点全部授权行才能回答一个布尔',
  KEY `idx_resource_type_id` (`resource_type`, `resource_id`) COMMENT '按资源反查全部被授权节点：级联回收（撤销时定位子树内同资源授权）、悬挂授权巡检、资源删除前影响面评估',
  KEY `idx_source` (`grant_source`, `source_ref_id`) COMMENT '按来源整批撤销/追溯（模板应用批次回滚、按标签批量授权回收）',
  KEY `idx_tenant_grant_time` (`tenant_id`, `grant_time`) COMMENT '机构内授权流水按时间倒序分页（授权审计）'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '资源逐级下发授权表（课程/题目/视频 → 节点，无继承）';

-- ----------------------------------------------------------------------------
-- 16. org_perm_template 权限模板表（抵消逐级显式授权的操作成本，契约 2.5）
--     模板【只管资源、不含功能权限】；应用时实际授权 = 模板清单 ∩ 授权人当前拥有的资源，
--     绝不放大权限（差集在响应中列出）。模板可见范围 = owner_node_id 的子树。
-- ----------------------------------------------------------------------------
DROP TABLE IF EXISTS `org_perm_template`;
CREATE TABLE `org_perm_template` (
  `id`             BIGINT        NOT NULL                COMMENT '模板ID（雪花算法）',
  `template_name`  VARCHAR(100)  NOT NULL                COMMENT '模板名称（如 高三数学包）',
  `owner_node_id`  BIGINT        NOT NULL                COMMENT '归属节点ID（→org_node.id，创建者所在节点）。可见范围=自己建的 ∪ 祖先节点建的 ∪ 子树内节点建的（整条根到叶路径 ∪ 子树，契约 §2.5：铁律 2 的唯一显式例外，因套用时取交集不放大权限）',
  `description`    VARCHAR(500)  NULL DEFAULT NULL       COMMENT '模板说明',
  `item_count`     INT           NOT NULL DEFAULT 0      COMMENT '模板资源条目数（冗余计数，明细增删时同步维护）',
  `status`         TINYINT       NOT NULL DEFAULT 0      COMMENT '模板状态：0启用 1停用（方向与 sys_user/sys_tenant/org_node 一致）',
  `tenant_id`      BIGINT        NOT NULL                COMMENT '租户（机构）ID',
  `create_by`      BIGINT        NULL DEFAULT NULL       COMMENT '创建人 user_id',
  `create_time`    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by`      BIGINT        NULL DEFAULT NULL       COMMENT '更新人 user_id',
  `update_time`    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted_at`     BIGINT       NOT NULL DEFAULT 0      COMMENT '逻辑删除：0=未删除，删除时写入毫秒时间戳（删除模板不影响已生成的授权行）',
  `remark`         VARCHAR(500)  NULL DEFAULT NULL       COMMENT '备注',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_template_name` (`tenant_id`, `template_name`, `deleted_at`) COMMENT '机构内模板名唯一（追加 deleted_at 兼容逻辑删除）',
  KEY `idx_owner_status` (`owner_node_id`, `status`) COMMENT '模板可见性：按归属节点取模板（配合子树条件判断下级是否可用）'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '权限模板表（资源清单，应用时取交集不放大权限）';

-- ----------------------------------------------------------------------------
-- 17. org_perm_template_item 模板资源明细表
-- ----------------------------------------------------------------------------
DROP TABLE IF EXISTS `org_perm_template_item`;
CREATE TABLE `org_perm_template_item` (
  `id`            BIGINT       NOT NULL                COMMENT '主键ID（雪花算法）',
  `template_id`   BIGINT       NOT NULL                COMMENT '模板ID（→org_perm_template.id）',
  `resource_type` TINYINT      NOT NULL                COMMENT '受管资源类型：1课程 2题目 3视频（契约 §5 resource_type）',
  `resource_id`   BIGINT       NOT NULL                COMMENT '资源ID（resource_type=1→crs_course.id，=2→qb_question.id，=3→vod_video.id）',
  `tenant_id`     BIGINT       NOT NULL                COMMENT '租户（机构）ID',
  `create_by`     BIGINT       NULL DEFAULT NULL       COMMENT '创建人 user_id',
  `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by`     BIGINT       NULL DEFAULT NULL       COMMENT '更新人 user_id',
  `update_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted_at`    BIGINT      NOT NULL DEFAULT 0      COMMENT '逻辑删除标记：0=未删除；删除时写入毫秒时间戳。用时间戳而非 0/1，使同一业务键可容纳任意多条已删除行（唯一索引末尾追加本列）',
  `remark`        VARCHAR(500) NULL DEFAULT NULL       COMMENT '备注',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_template_resource` (`template_id`, `resource_type`, `resource_id`, `deleted_at`) COMMENT '契约 UK(template_id,resource_type,resource_id)：同一模板不可重复收录同一资源（追加 deleted_at 兼容逻辑删除）',
  KEY `idx_resource_type_id` (`resource_type`, `resource_id`) COMMENT '按资源反查引用它的模板（资源下架前提示受影响模板）'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '权限模板资源明细表';

-- ----------------------------------------------------------------------------
-- 18. org_tag 标签定义表（年级/科目/能力层级等横切属性）
--     标签【仅用于筛选与批量操作，不参与任何权限判断】（契约 §4 org_tag）：
--     横切属性跨导师、跨节点，做成树节点会导致同一年级散落在树的不同深度而无法聚合；
--     按标签批量授权/分发时仍须逐个校验目标是否在自己子树内，标签不得扩大可见范围（契约 8.2 铁律2）。
-- ----------------------------------------------------------------------------
DROP TABLE IF EXISTS `org_tag`;
CREATE TABLE `org_tag` (
  `id`          BIGINT       NOT NULL                COMMENT '标签ID（雪花算法）',
  `tag_name`    VARCHAR(50)  NOT NULL                COMMENT '标签名称（如 高三/数学/冲刺班）',
  `tag_group`   VARCHAR(50)  NULL DEFAULT NULL       COMMENT '标签分组名（如 年级/科目/能力层级；同组标签在筛选器内互斥展示）',
  `color`       VARCHAR(20)  NULL DEFAULT NULL       COMMENT '展示色值（如 #409EFF）',
  `sort`        INT          NOT NULL DEFAULT 0      COMMENT '显示顺序（升序）',
  `tenant_id`   BIGINT       NOT NULL                COMMENT '租户（机构）ID',
  `create_by`   BIGINT       NULL DEFAULT NULL       COMMENT '创建人 user_id',
  `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by`   BIGINT       NULL DEFAULT NULL       COMMENT '更新人 user_id',
  `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted_at`  BIGINT      NOT NULL DEFAULT 0      COMMENT '逻辑删除标记：0=未删除；删除时写入毫秒时间戳。用时间戳而非 0/1，使同一业务键可容纳任意多条已删除行（唯一索引末尾追加本列）',
  `remark`      VARCHAR(500) NULL DEFAULT NULL       COMMENT '备注',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_tag_name` (`tenant_id`, `tag_name`, `deleted_at`) COMMENT '同机构内标签名唯一（追加 deleted_at 兼容逻辑删除）',
  KEY `idx_tenant_group_sort` (`tenant_id`, `tag_group`, `sort`) COMMENT '标签选择器按分组分区展示'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '标签定义表（横切属性）';

-- ----------------------------------------------------------------------------
-- 19. org_student_tag 学生-标签关联表（M:N）
-- ----------------------------------------------------------------------------
DROP TABLE IF EXISTS `org_student_tag`;
CREATE TABLE `org_student_tag` (
  `id`          BIGINT       NOT NULL                COMMENT '主键ID（雪花算法）',
  `student_id`  BIGINT       NOT NULL                COMMENT '学生ID（→org_student.id）',
  `tag_id`      BIGINT       NOT NULL                COMMENT '标签ID（→org_tag.id）',
  `tenant_id`   BIGINT       NOT NULL                COMMENT '租户（机构）ID',
  `create_by`   BIGINT       NULL DEFAULT NULL       COMMENT '创建人 user_id',
  `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by`   BIGINT       NULL DEFAULT NULL       COMMENT '更新人 user_id',
  `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted_at`  BIGINT      NOT NULL DEFAULT 0      COMMENT '逻辑删除标记：0=未删除；删除时写入毫秒时间戳。用时间戳而非 0/1，使同一业务键可容纳任意多条已删除行（唯一索引末尾追加本列）',
  `remark`      VARCHAR(500) NULL DEFAULT NULL       COMMENT '备注',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_student_tag` (`student_id`, `tag_id`, `deleted_at`) COMMENT '契约 UK(student_id,tag_id)：同一学生同一标签仅一条（追加 deleted_at 兼容逻辑删除）',
  KEY `idx_tag_id` (`tag_id`) COMMENT '按标签批量拉取学员（grant_source=3 按标签批量授权/分发依据，高频）'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '学生-标签关联表（M:N）';

-- ----------------------------------------------------------------------------
-- 20. org_import_task Excel 导入任务表
-- ----------------------------------------------------------------------------
DROP TABLE IF EXISTS `org_import_task`;
CREATE TABLE `org_import_task` (
  `id`                  BIGINT       NOT NULL                COMMENT '任务ID（雪花算法）',
  `biz_type`            TINYINT      NOT NULL DEFAULT 1      COMMENT '导入业务类型：1学生导入',
  `file_id`             BIGINT       NOT NULL                COMMENT '导入源文件ID（→sys_file.id）',
  `total_count`         INT          NOT NULL DEFAULT 0      COMMENT '总行数',
  `success_count`       INT          NOT NULL DEFAULT 0      COMMENT '成功行数',
  `fail_count`          INT          NOT NULL DEFAULT 0      COMMENT '失败行数',
  `status`              TINYINT      NOT NULL DEFAULT 0      COMMENT '任务状态：0处理中 1成功 2部分失败 3失败',
  `target_node_id`      BIGINT       NOT NULL                COMMENT '导入目标父节点ID（→org_node.id）：后台 Worker 的全部输入就是本行，据此决定学员挂在哪个节点下。缺此列则导入任务根本无法执行',
  `template_id`         BIGINT       NULL DEFAULT NULL       COMMENT '套用的权限模板ID（→org_perm_template.id，可空；套用时取交集，见契约 §2.5）',
  `credential_file_id`  BIGINT       NULL DEFAULT NULL       COMMENT '账号密码表文件ID（→sys_file.id，biz_type=credential_sheet）：仅当本次导入存在服务端随机生成初始密码的行时有值，保留 7 天后物理清理',
  `fail_report_file_id` BIGINT       NULL DEFAULT NULL       COMMENT '失败明细报告文件ID（→sys_file.id，status=2/3 时生成）',
  `tenant_id`           BIGINT       NOT NULL                COMMENT '租户（机构）ID',
  `create_by`           BIGINT       NULL DEFAULT NULL       COMMENT '创建人（发起导入者）user_id',
  `create_time`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by`           BIGINT       NULL DEFAULT NULL       COMMENT '更新人 user_id',
  `update_time`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted_at`          BIGINT      NOT NULL DEFAULT 0      COMMENT '逻辑删除标记：0=未删除；删除时写入毫秒时间戳。用时间戳而非 0/1，使同一业务键可容纳任意多条已删除行（唯一索引末尾追加本列）',
  `remark`              VARCHAR(500) NULL DEFAULT NULL       COMMENT '备注',
  PRIMARY KEY (`id`),
  KEY `idx_tenant_time` (`tenant_id`, `create_time`) COMMENT '机构内导入任务列表按时间倒序分页',
  KEY `idx_status` (`status`) COMMENT '异步 Worker 扫描处理中任务',
  KEY `idx_target_node` (`target_node_id`) COMMENT '按导入目标节点追溯历史导入批次'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'Excel 导入任务表';

-- ============================================================================
-- 区块 3/7：crs_ 课程（4 张）
-- ----------------------------------------------------------------------------
-- 课程对节点/学生的授权统一走 org_resource_grant（resource_type=1），本区块不设授权表。
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 21. crs_course 课程表
-- ----------------------------------------------------------------------------
DROP TABLE IF EXISTS `crs_course`;
CREATE TABLE `crs_course` (
  `id`             BIGINT       NOT NULL                COMMENT '课程ID（雪花算法）',
  `course_name`    VARCHAR(100) NOT NULL                COMMENT '课程名称',
  `owner_node_id`  BIGINT       NOT NULL                COMMENT '归属节点ID（→org_node.id，创建时写入创建者所在节点）：契约 2.5 授权前提——只有 owner 或被显式授权者才能再向下授权',
  `cover_file_id`  BIGINT       NULL DEFAULT NULL       COMMENT '封面文件ID（→sys_file.id）',
  `subject`        VARCHAR(50)  NULL DEFAULT NULL       COMMENT '所属科目（如 数学/英语）',
  `description`    VARCHAR(2000) NULL DEFAULT NULL      COMMENT '课程简介（与 03-03 §1.3 接口声明的 2000 字符对齐）',
  `status`         TINYINT      NOT NULL DEFAULT 0      COMMENT '课程状态：0草稿 1已上架 2已下架',
  `lesson_count`   INT          NOT NULL DEFAULT 0      COMMENT '课时总数（冗余计数，课时增删时同步维护）',
  `total_duration` INT          NOT NULL DEFAULT 0      COMMENT '视频总时长（冗余，秒）',
  `tenant_id`      BIGINT       NOT NULL                COMMENT '租户（机构）ID',
  `create_by`      BIGINT       NULL DEFAULT NULL       COMMENT '创建人 user_id',
  `create_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by`      BIGINT       NULL DEFAULT NULL       COMMENT '更新人 user_id',
  `update_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted_at`     BIGINT      NOT NULL DEFAULT 0      COMMENT '逻辑删除：0=未删除，删除时写入毫秒时间戳（课程禁止物理删除）',
  `remark`         VARCHAR(500) NULL DEFAULT NULL       COMMENT '备注',
  PRIMARY KEY (`id`),
  KEY `idx_tenant_status` (`tenant_id`, `status`) COMMENT '机构内课程列表按上架状态筛选',
  KEY `idx_owner_node_status` (`owner_node_id`, `status`) COMMENT '"我创建的课程"列表 / 授权校验时确认自己是否为 owner（高频）'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '课程表（授权见 org_resource_grant，resource_type=1）';

-- ----------------------------------------------------------------------------
-- 22. crs_chapter 章/节表（两级树：parent_id=0 为章，否则为节）
-- ----------------------------------------------------------------------------
DROP TABLE IF EXISTS `crs_chapter`;
CREATE TABLE `crs_chapter` (
  `id`           BIGINT       NOT NULL                COMMENT '章节ID（雪花算法）',
  `course_id`    BIGINT       NOT NULL                COMMENT '所属课程ID（→crs_course.id）',
  `parent_id`    BIGINT       NOT NULL DEFAULT 0      COMMENT '父节点ID：0=章，否则为该章下的节',
  `chapter_name` VARCHAR(100) NOT NULL                COMMENT '章/节名称',
  `sort`         INT          NOT NULL DEFAULT 0      COMMENT '同级显示顺序（升序）',
  `tenant_id`    BIGINT       NOT NULL                COMMENT '租户（机构）ID',
  `create_by`    BIGINT       NULL DEFAULT NULL       COMMENT '创建人 user_id',
  `create_time`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by`    BIGINT       NULL DEFAULT NULL       COMMENT '更新人 user_id',
  `update_time`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted_at`   BIGINT      NOT NULL DEFAULT 0      COMMENT '逻辑删除标记：0=未删除；删除时写入毫秒时间戳。用时间戳而非 0/1，使同一业务键可容纳任意多条已删除行（唯一索引末尾追加本列）',
  `remark`       VARCHAR(500) NULL DEFAULT NULL       COMMENT '备注',
  PRIMARY KEY (`id`),
  KEY `idx_course_parent_sort` (`course_id`, `parent_id`, `sort`) COMMENT '加载课程目录树（章→节按序展开）'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '章/节表（两级树）';

-- ----------------------------------------------------------------------------
-- 23. crs_lesson 课时表
-- ----------------------------------------------------------------------------
DROP TABLE IF EXISTS `crs_lesson`;
CREATE TABLE `crs_lesson` (
  `id`              BIGINT       NOT NULL                COMMENT '课时ID（雪花算法）',
  `course_id`       BIGINT       NOT NULL                COMMENT '所属课程ID（冗余自章节，加速课程维度查询）',
  `chapter_id`      BIGINT       NOT NULL                COMMENT '所属节ID（→crs_chapter.id）',
  `lesson_name`     VARCHAR(100) NOT NULL                COMMENT '课时名称',
  `lesson_type`     TINYINT      NOT NULL DEFAULT 1      COMMENT '课时类型：1视频 2图文',
  `video_id`        BIGINT       NULL DEFAULT NULL       COMMENT '视频媒资ID（→vod_video.id，lesson_type=1 时必填）',
  `content_id`      BIGINT       NULL DEFAULT NULL       COMMENT '图文资料ID（→crs_material.id，lesson_type=2 时必填）',
  `duration`        INT          NOT NULL DEFAULT 0      COMMENT '视频时长（秒，冗余自 vod_video，完播判定分母）',
  `sort`            INT          NOT NULL DEFAULT 0      COMMENT '节内显示顺序（升序）',
  `is_free_preview` TINYINT      NOT NULL DEFAULT 0      COMMENT '是否免费试看：0否 1是',
  `status`          TINYINT      NOT NULL DEFAULT 1      COMMENT '课时状态：0隐藏 1可见',
  `tenant_id`       BIGINT       NOT NULL                COMMENT '租户（机构）ID',
  `create_by`       BIGINT       NULL DEFAULT NULL       COMMENT '创建人 user_id',
  `create_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by`       BIGINT       NULL DEFAULT NULL       COMMENT '更新人 user_id',
  `update_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted_at`      BIGINT      NOT NULL DEFAULT 0      COMMENT '逻辑删除：0=未删除，删除时写入毫秒时间戳（课时禁止物理删除）',
  `remark`          VARCHAR(500) NULL DEFAULT NULL       COMMENT '备注',
  PRIMARY KEY (`id`),
  KEY `idx_chapter_sort` (`chapter_id`, `sort`) COMMENT '加载节下课时列表',
  KEY `idx_course_status` (`course_id`, `status`) COMMENT '课程维度统计可见课时/计算课程进度',
  KEY `idx_video_id` (`video_id`) COMMENT '按媒资反查引用课时（删除媒资前校验）'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '课时表';

-- ----------------------------------------------------------------------------
-- 24. crs_material 图文资料内容表
-- ----------------------------------------------------------------------------
DROP TABLE IF EXISTS `crs_material`;
CREATE TABLE `crs_material` (
  `id`                  BIGINT       NOT NULL           COMMENT '图文资料ID（雪花算法）',
  `owner_node_id`       BIGINT       NOT NULL                COMMENT '归属节点ID（→org_node.id，创建时写入创建者所在节点）：管理端可见性按本列做子树过滤，此后不随创建人调岗漂移。学生端可见性走所属课时→课程→课程授权',
  `title`               VARCHAR(200) NOT NULL           COMMENT '资料标题',
  `content`             LONGTEXT     NULL               COMMENT '富文本正文（HTML）',
  `attachment_file_ids` JSON         NULL               COMMENT '附件文件ID数组（JSON，如 ["1953827104412590081"]，元素→sys_file.id）',
  `tenant_id`           BIGINT       NOT NULL           COMMENT '租户（机构）ID',
  `create_by`           BIGINT       NULL DEFAULT NULL  COMMENT '创建人 user_id',
  `create_time`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by`           BIGINT       NULL DEFAULT NULL  COMMENT '更新人 user_id',
  `update_time`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted_at`          BIGINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除标记：0=未删除；删除时写入毫秒时间戳。用时间戳而非 0/1，使同一业务键可容纳任意多条已删除行（唯一索引末尾追加本列）',
  `remark`              VARCHAR(500) NULL DEFAULT NULL  COMMENT '备注',
  PRIMARY KEY (`id`),
  KEY `idx_tenant_time` (`tenant_id`, `create_time`) COMMENT '机构内图文资料库列表分页'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '图文资料内容表';

-- ============================================================================
-- 区块 4/7：vod_ 视频与进度（4 张）
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 25. vod_video 云端媒资表
-- ----------------------------------------------------------------------------
DROP TABLE IF EXISTS `vod_video`;
CREATE TABLE `vod_video` (
  `id`             BIGINT       NOT NULL                COMMENT '媒资ID（雪花算法）',
  `owner_node_id`  BIGINT       NOT NULL                COMMENT '归属节点ID（→org_node.id，上传时写入上传者所在节点）：契约 2.5 授权前提，视频亦为受管资源（resource_type=3）',
  `provider`       TINYINT      NOT NULL DEFAULT 1      COMMENT '云厂商：1腾讯 2阿里',
  `vod_file_id`    VARCHAR(100) NULL DEFAULT NULL       COMMENT '云端媒资唯一ID（腾讯 FileId / 阿里 VideoId；预创建（status=0）时为 NULL，上传完成回调回填）',
  `video_name`     VARCHAR(200) NOT NULL                COMMENT '视频名称',
  `duration`       INT          NOT NULL DEFAULT 0      COMMENT '视频时长（秒，转码回调后回填）',
  `cover_url`      VARCHAR(500) NULL DEFAULT NULL       COMMENT '云端封面 URL',
  `size_bytes`     BIGINT       NOT NULL DEFAULT 0      COMMENT '源文件大小（字节）',
  `status`         TINYINT      NOT NULL DEFAULT 0      COMMENT '媒资状态：0上传中 1转码中 2正常 3转码失败 9禁用',
  `hls_url`        VARCHAR(500) NULL DEFAULT NULL       COMMENT '加密 HLS 播放地址（.m3u8，播放时需另取时效凭证）',
  `upload_user_id` BIGINT       NULL DEFAULT NULL       COMMENT '上传人 user_id',
  `tenant_id`      BIGINT       NOT NULL                COMMENT '租户（机构）ID',
  `create_by`      BIGINT       NULL DEFAULT NULL       COMMENT '创建人 user_id',
  `create_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by`      BIGINT       NULL DEFAULT NULL       COMMENT '更新人 user_id',
  `update_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted_at`     BIGINT      NOT NULL DEFAULT 0      COMMENT '逻辑删除标记：0=未删除；删除时写入毫秒时间戳。用时间戳而非 0/1，使同一业务键可容纳任意多条已删除行（唯一索引末尾追加本列）',
  `remark`         VARCHAR(500) NULL DEFAULT NULL       COMMENT '备注',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_provider_file` (`provider`, `vod_file_id`, `deleted_at`) COMMENT '同一云厂商媒资ID唯一（转码回调幂等定位，追加 deleted_at 兼容逻辑删除；vod_file_id 为 NULL 的上传中记录不参与唯一冲突，可多条并存）',
  KEY `idx_tenant_status` (`tenant_id`, `status`) COMMENT '机构媒资库按转码状态筛选',
  KEY `idx_owner_node_status` (`owner_node_id`, `status`) COMMENT '"我上传的媒资"列表 / 授权校验时确认自己是否为 owner'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '云端媒资表（腾讯云/阿里云 VOD；授权见 org_resource_grant，resource_type=3）';

-- ----------------------------------------------------------------------------
-- 26. vod_play_auth_log 播放凭证发放记录表（日志表：雪花主键，允许物理归档清理，按契约例外精简 update_by/remark）
-- ----------------------------------------------------------------------------
DROP TABLE IF EXISTS `vod_play_auth_log`;
CREATE TABLE `vod_play_auth_log` (
  `id`          BIGINT       NOT NULL                COMMENT '记录ID（雪花算法）',
  `viewer_user_id` BIGINT    NOT NULL                COMMENT '取证人账号ID（→sys_user.id）：学生、教师、管理员均可取证，故审计主体统一用账号而非学生档案',
  `viewer_type` TINYINT      NOT NULL                COMMENT '取证人类型（=sys_user.user_type）：1管理员 2教师 3学生。管理端预览与学生学习走同一接口、同一审计表，靠本列区分',
  `student_id`  BIGINT       NULL DEFAULT NULL       COMMENT '学生ID（→org_student.id）：仅 viewer_type=3 时有值；教师/管理员预览时为 NULL——二者没有 org_student 档案行，此列若为 NOT NULL 则管理端预览必然插入失败或漏审计',
  `lesson_id`   BIGINT       NOT NULL                COMMENT '课时ID（→crs_lesson.id）',
  `video_id`    BIGINT       NOT NULL                COMMENT '媒资ID（→vod_video.id）',
  `auth_token`  VARCHAR(500) NOT NULL                COMMENT '发放的播放凭证（PlayAuth/Key，脱敏截断存储）',
  `expire_time` DATETIME     NOT NULL                COMMENT '凭证过期时间（错误码 20001 校验依据）',
  `client_ip`   VARCHAR(64)  NULL DEFAULT NULL       COMMENT '请求方 IP',
  `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '发放时间',
  `tenant_id`   BIGINT       NOT NULL                COMMENT '租户（机构）ID',
  `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted_at`  BIGINT      NOT NULL DEFAULT 0      COMMENT '逻辑删除：0=未删除，删除时写入毫秒时间戳（日志业务恒为 0，清理走物理归档）',
  PRIMARY KEY (`id`),
  KEY `idx_viewer_time` (`viewer_user_id`, `create_time`) COMMENT '审计某账号取证频次（防刷排查）：覆盖学生、教师、管理员三类取证人',
  KEY `idx_student_time` (`student_id`, `create_time`) COMMENT '审计某学员取证频次；student_id 为 NULL 的管理端预览行不入本索引，正合需求',
  KEY `idx_lesson_time` (`lesson_id`, `create_time`) COMMENT '按课时统计取证量'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '播放凭证发放记录表（审计，可按时间归档清理）';

-- ----------------------------------------------------------------------------
-- 27. vod_watch_progress 学习进度表（学生×课时聚合态，由心跳任务每 60s 批量落盘）
-- ----------------------------------------------------------------------------
DROP TABLE IF EXISTS `vod_watch_progress`;
CREATE TABLE `vod_watch_progress` (
  `id`                  BIGINT       NOT NULL           COMMENT '主键ID（雪花算法）',
  `student_id`          BIGINT       NOT NULL           COMMENT '学生ID（→org_student.id）',
  `lesson_id`           BIGINT       NOT NULL           COMMENT '课时ID（→crs_lesson.id）',
  `course_id`           BIGINT       NOT NULL           COMMENT '课程ID（冗余自课时，加速课程维度统计）',
  `watched_duration`    INT          NOT NULL DEFAULT 0 COMMENT '已看时长（墙钟有效累计秒：复看已看区间同样计入，单次心跳最多计入 min(间隔,15)s；完播判定分子）',
  `max_position`        INT          NOT NULL DEFAULT 0 COMMENT '最远触达位置（秒，禁快进：仅允许回看 ≤ 此值区间）',
  `watch_status`        TINYINT      NOT NULL DEFAULT 0 COMMENT '完播状态：0未开始 1学习中 2已完成（watched_duration≥duration×90% 置 2，阈值租户可配 complete_rate_threshold）',
  `complete_time`       DATETIME     NULL DEFAULT NULL  COMMENT '完播时间（watch_status=2 时写入）',
  `last_heartbeat_time` DATETIME     NULL DEFAULT NULL  COMMENT '最后一次有效心跳时间（间隔合理性校验基准）',
  `tenant_id`           BIGINT       NOT NULL           COMMENT '租户（机构）ID',
  `create_by`           BIGINT       NULL DEFAULT NULL  COMMENT '创建人 user_id',
  `create_time`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by`           BIGINT       NULL DEFAULT NULL  COMMENT '更新人 user_id',
  `update_time`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted_at`          BIGINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除标记：0=未删除；删除时写入毫秒时间戳。用时间戳而非 0/1，使同一业务键可容纳任意多条已删除行（唯一索引末尾追加本列）',
  `remark`              VARCHAR(500) NULL DEFAULT NULL  COMMENT '备注',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_student_lesson` (`student_id`, `lesson_id`, `deleted_at`) COMMENT '契约 UK(student_id,lesson_id)：一名学生一个课时仅一条进度（心跳落盘 UPSERT 依据，追加 deleted_at 兼容逻辑删除）',
  KEY `idx_lesson_id` (`lesson_id`) COMMENT '教师端查看某课时下名下学员的观看进度（高频）',
  KEY `idx_course_student` (`course_id`, `student_id`) COMMENT '学生课程进度百分比计算/个人档案',
  KEY `idx_lesson_status` (`lesson_id`, `watch_status`) COMMENT '课时完播率统计（完成人数/总人数）'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '学习进度表（学生×课时聚合态）';

-- ----------------------------------------------------------------------------
-- 28. vod_heartbeat_log 心跳明细表（按月 RANGE 分区；日志表：雪花主键+分区列联合主键，
--     无逻辑删除，按契约例外精简 update_by/remark；分区维护策略见文件头注释）
-- ----------------------------------------------------------------------------
DROP TABLE IF EXISTS `vod_heartbeat_log`;
CREATE TABLE `vod_heartbeat_log` (
  `id`               BIGINT        NOT NULL                COMMENT '记录ID（雪花算法）',
  `student_id`       BIGINT        NOT NULL                COMMENT '学生ID（→org_student.id）',
  `lesson_id`        BIGINT        NOT NULL                COMMENT '课时ID（→crs_lesson.id）',
  `video_id`         BIGINT        NOT NULL                COMMENT '媒资ID（→vod_video.id）',
  `current_time_sec` DECIMAL(10,1) NOT NULL DEFAULT 0.0    COMMENT '心跳上报时的播放位置（秒，对应请求体 currentTime；命名已避开保留字）',
  `interval_sec`     INT           NOT NULL DEFAULT 0      COMMENT '与上次心跳的实际间隔（秒，>=8s 视为有效，单次计入 min(实际间隔,15)）',
  `seeked`           TINYINT       NOT NULL DEFAULT 0      COMMENT '本次心跳前是否发生 seek 或暂停恢复：0否 1是（对应请求体 seeked）。必须落库——它决定本次是否执行推进一致性校验（03-课程与视频 8.2.1 规则 6），不记录就无法回放这条心跳当时为什么被采纳或丢弃，而本表是防刷审计与进度争议回溯的唯一依据；同时供风控统计 seeked 频次：连续 >2 次或单课时每小时 >20 次即判异常，防脚本恒置 true 关闭规则 6',
  `reject_rule`      TINYINT       NOT NULL DEFAULT 0      COMMENT '本条心跳的判定结果：0=采纳并计时；2/3/5/6/7/8/9=被 03-03 §8.2.1 对应编号的规则拒绝。【编号锚定】本列的取值就是 §8.2.1 的规则编号，PRD F2-7 校验规则列表亦用同一套编号，三处必须一致——编号一旦改动，已落库的历史日志会指向另一条规则，且无法回溯修正。【1 与 4 保留不使用】1 凭证无效在进入心跳处理前即被拦下（返回 20001、前端续签后重试），此时学生与课时的绑定尚未通过校验，写一行归属到某学生的审计记录本身就是错的；4 倍速不加速是计时口径、不拒绝任何心跳，playRate 越界由规则 5 承担。实现方无需为这两个值写分支，运维在日志中找不到它们也不是埋点漏了。【2/3 与 5 的分工】规则 2/3 判定失败时虽经规则 5 的响应路径返回 20002，本列记的是**首次判定失败的那条规则**（2 或 3），5 专指参数明显非法；若一律记 5，本列就退化为"要么 0 要么 5"，丧失全部诊断价值。【为什么需要本列】本表是防刷审计与进度争议回溯的唯一依据，而此前没有任何一列记录"这条到底算没算、为什么没算"——只能从 interval_sec<8 反推规则 2，其余规则的拒绝完全不可追溯。1 字节换全链路可回放。不记 sessionId 是权衡结果：CHAR(32) 在常驻 0.95 亿行上多占约 3GB（+25%），而排查真正需要的是"为什么被拒"而非"哪个会话"',
  `client_ip`        VARCHAR(64)   NULL DEFAULT NULL       COMMENT '客户端 IP',
  `device`           VARCHAR(200)  NULL DEFAULT NULL       COMMENT '设备/浏览器标识（UA 摘要）',
  `created_time`     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '心跳时间（分区键）',
  `tenant_id`        BIGINT        NOT NULL                COMMENT '租户（机构）ID',
  `update_time`      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`, `created_time`),
  KEY `idx_student_lesson_time` (`student_id`, `lesson_id`, `created_time`) COMMENT '防刷审计：回放某学生某课时的心跳序列'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '心跳明细表（按月分区，可整分区归档清理，无逻辑删除）'
PARTITION BY RANGE (TO_DAYS(`created_time`)) (
  PARTITION p202608 VALUES LESS THAN (TO_DAYS('2026-09-01')),
  PARTITION p202609 VALUES LESS THAN (TO_DAYS('2026-10-01')),
  PARTITION p202610 VALUES LESS THAN (TO_DAYS('2026-11-01')),
  PARTITION p202611 VALUES LESS THAN (TO_DAYS('2026-12-01')),
  PARTITION p202612 VALUES LESS THAN (TO_DAYS('2027-01-01')),
  PARTITION p202701 VALUES LESS THAN (TO_DAYS('2027-02-01')),
  PARTITION pmax    VALUES LESS THAN MAXVALUE
);

-- ============================================================================
-- 区块 5/7：qb_ 题库（3 张）
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 29. qb_category 题库分类树表（科目/知识点）
-- ----------------------------------------------------------------------------
DROP TABLE IF EXISTS `qb_category`;
CREATE TABLE `qb_category` (
  `id`            BIGINT       NOT NULL                COMMENT '分类ID（雪花算法）',
  `parent_id`     BIGINT       NOT NULL DEFAULT 0      COMMENT '父分类ID（0=顶级，支持科目→知识点多级树）',
  `category_name` VARCHAR(50)  NOT NULL                COMMENT '分类名称',
  `sort`          INT          NOT NULL DEFAULT 0      COMMENT '同级显示顺序（升序）',
  `tenant_id`     BIGINT       NOT NULL                COMMENT '租户（机构）ID',
  `create_by`     BIGINT       NULL DEFAULT NULL       COMMENT '创建人 user_id',
  `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by`     BIGINT       NULL DEFAULT NULL       COMMENT '更新人 user_id',
  `update_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted_at`    BIGINT      NOT NULL DEFAULT 0      COMMENT '逻辑删除标记：0=未删除；删除时写入毫秒时间戳。用时间戳而非 0/1，使同一业务键可容纳任意多条已删除行（唯一索引末尾追加本列）',
  `remark`        VARCHAR(500) NULL DEFAULT NULL       COMMENT '备注',
  PRIMARY KEY (`id`),
  KEY `idx_tenant_parent_sort` (`tenant_id`, `parent_id`, `sort`) COMMENT '构建机构题库分类树'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '题库分类树表（科目/知识点）';

-- ----------------------------------------------------------------------------
-- 30. qb_question 题目主表（物理 ID 恒定：雪花全局唯一、永不复用；内容在版本表）
-- ----------------------------------------------------------------------------
DROP TABLE IF EXISTS `qb_question`;
CREATE TABLE `qb_question` (
  `id`              BIGINT       NOT NULL                COMMENT '题目物理ID（雪花算法，全局唯一，永不复用；错题本/作业以此绑定）',
  `owner_node_id`   BIGINT       NOT NULL                COMMENT '归属节点ID（→org_node.id，创建时写入出题人所在节点）：契约 2.5 授权前提，题目为受管资源（resource_type=2）',
  `category_id`     BIGINT       NOT NULL                COMMENT '所属分类ID（→qb_category.id）',
  `question_type`   TINYINT      NOT NULL                COMMENT '题型：1单选 2多选 3判断 4填空 5简答 6材料题(父题)',
  `parent_id`       BIGINT       NOT NULL DEFAULT 0      COMMENT '材料题子题所属父题ID（→qb_question.id），普通题=0',
  `difficulty`      TINYINT      NOT NULL DEFAULT 3      COMMENT '难度：1-5（1最易 5最难）',
  `current_version` INT          NOT NULL DEFAULT 1      COMMENT '当前版本号（从1起，每次编辑内容+1，历史版本存 qb_question_version）',
  `stem_preview`    VARCHAR(500) NULL DEFAULT NULL       COMMENT '题干纯文本摘要（列表页展示/检索，随当前版本更新）',
  `status`          TINYINT      NOT NULL DEFAULT 0      COMMENT '题目状态：0草稿 1启用 2停用（被作业引用不可停用，错误码 30001）',
  `tenant_id`       BIGINT       NOT NULL                COMMENT '租户（机构）ID',
  `create_by`       BIGINT       NULL DEFAULT NULL       COMMENT '创建人 user_id',
  `create_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by`       BIGINT       NULL DEFAULT NULL       COMMENT '更新人 user_id',
  `update_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted_at`      BIGINT      NOT NULL DEFAULT 0      COMMENT '逻辑删除：0=未删除，删除时写入毫秒时间戳（题目禁止物理删除）',
  `remark`          VARCHAR(500) NULL DEFAULT NULL       COMMENT '备注',
  PRIMARY KEY (`id`),
  KEY `idx_category_status` (`category_id`, `status`) COMMENT '教师选题：按分类筛启用题目（高频）',
  KEY `idx_parent_id` (`parent_id`) COMMENT '材料题加载子题列表',
  KEY `idx_tenant_type_status` (`tenant_id`, `question_type`, `status`) COMMENT '机构题库按题型筛选',
  KEY `idx_owner_node_status` (`owner_node_id`, `status`) COMMENT '"我出的题"列表 / 授权校验时确认自己是否为 owner'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '题目主表（物理 ID 恒定，内容见版本快照表；授权见 org_resource_grant，resource_type=2）';

-- ----------------------------------------------------------------------------
-- 31. qb_question_version 题目版本快照表（内容不可变：任何历史版本不可修改、不可删除）
-- ----------------------------------------------------------------------------
DROP TABLE IF EXISTS `qb_question_version`;
CREATE TABLE `qb_question_version` (
  `id`             BIGINT        NOT NULL                COMMENT '版本记录ID（雪花算法）',
  `question_id`    BIGINT        NOT NULL                COMMENT '题目物理ID（→qb_question.id）',
  `version`        INT           NOT NULL                COMMENT '版本号（从1起递增，编辑题目=写入新版本并回写主表 current_version）',
  `content`        JSON          NOT NULL                COMMENT '题目内容快照（JSON：题干/选项/子题排序等，写入后不可变）',
  `correct_answer` JSON          NULL                    COMMENT '标准答案（JSON；客观题自动判卷比对依据，简答题可为评分要点）',
  `analysis`       TEXT          NULL                    COMMENT '题目解析（按 answer_visible_type 时机对学生展示）',
  `score_default`  DECIMAL(6,2)  NOT NULL DEFAULT 0.00   COMMENT '建议分值（组卷时的默认分，作业内可覆写）',
  `tenant_id`      BIGINT        NOT NULL                COMMENT '租户（机构）ID',
  `create_by`      BIGINT        NULL DEFAULT NULL       COMMENT '版本创建人 user_id（→sys_user.id）',
  `create_time`    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by`      BIGINT        NULL DEFAULT NULL       COMMENT '更新人 user_id',
  `update_time`    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted_at`     BIGINT       NOT NULL DEFAULT 0      COMMENT '逻辑删除：0=未删除，删除时写入毫秒时间戳（版本快照业务上禁止删除，恒为0）',
  `remark`         VARCHAR(500)  NULL DEFAULT NULL       COMMENT '备注',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_question_version` (`question_id`, `version`, `deleted_at`) COMMENT '契约 UK(question_id,version)：同一题目版本号唯一（追加 deleted_at 保持全库统一方案）'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '题目版本快照表（内容不可变）';

-- ============================================================================
-- 区块 6/7：hw_ 作业（6 张）
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 32. hw_homework 作业/试卷定义表
-- ----------------------------------------------------------------------------
DROP TABLE IF EXISTS `hw_homework`;
CREATE TABLE `hw_homework` (
  `id`                  BIGINT       NOT NULL                COMMENT '作业ID（雪花算法）',
  `homework_name`       VARCHAR(100) NOT NULL                COMMENT '作业/试卷名称',
  `homework_type`       TINYINT      NOT NULL DEFAULT 1      COMMENT '类型：1作业 2考试',
  `owner_node_id`       BIGINT       NOT NULL                COMMENT '归属节点ID（→org_node.id，创建时写入创建者所在节点）：作业本身不是受管资源，此列用于"谁建的作业归谁管"的数据范围过滤（子树可见）',
  `course_id`           BIGINT       NULL DEFAULT NULL       COMMENT '关联课程ID（→crs_course.id，可空）',
  `total_score`         DECIMAL(6,2) NOT NULL DEFAULT 0.00   COMMENT '总分（冗余=Σ 普通题行与材料题父题行的 hw_homework_question.score，子题行不重复计入）',
  `question_count`      INT          NOT NULL DEFAULT 0      COMMENT '可作答题目数量（冗余计数：普通题各计1，材料题按子题计数、父题不计）',
  `deadline`            DATETIME     NULL DEFAULT NULL       COMMENT '完成截止时间（过期未交置答卷状态4，错误码 30002 依据）',
  `publish_time`        DATETIME     NULL DEFAULT NULL       COMMENT '发布时间（status 置 1 时写入）',
  `allow_late_submit`   TINYINT      NOT NULL DEFAULT 0      COMMENT '是否允许逾期补交：0否 1是',
  `answer_visible_type` TINYINT      NOT NULL DEFAULT 2      COMMENT '答案公布时机：1提交后 2截止后',
  `status`              TINYINT      NOT NULL DEFAULT 0      COMMENT '作业状态：0草稿 1已发布 2已截止 3已撤回',
  `tenant_id`           BIGINT       NOT NULL                COMMENT '租户（机构）ID',
  `create_by`           BIGINT       NULL DEFAULT NULL       COMMENT '创建人 user_id',
  `create_time`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by`           BIGINT       NULL DEFAULT NULL       COMMENT '更新人 user_id',
  `update_time`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted_at`          BIGINT      NOT NULL DEFAULT 0      COMMENT '逻辑删除：0=未删除，删除时写入毫秒时间戳（作业禁止物理删除）',
  `remark`              VARCHAR(500) NULL DEFAULT NULL       COMMENT '备注',
  PRIMARY KEY (`id`),
  KEY `idx_creator_status` (`create_by`, `status`) COMMENT '"我创建的作业"列表按状态筛选（创建人可为教师或管理员）',
  KEY `idx_course_id` (`course_id`) COMMENT '按课程聚合关联作业',
  KEY `idx_status_deadline` (`status`, `deadline`) COMMENT '定时任务扫描已发布且过期作业自动置已截止',
  KEY `idx_owner_node_status` (`owner_node_id`, `status`) COMMENT '管理端按节点子树查看下级布置的作业（数据权限过滤后的作业列表）'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '作业/试卷定义表';

-- ----------------------------------------------------------------------------
-- 33. hw_homework_question 作业-题目表（发布时固化题目版本，规避题库更新错位）
-- ----------------------------------------------------------------------------
DROP TABLE IF EXISTS `hw_homework_question`;
CREATE TABLE `hw_homework_question` (
  `id`               BIGINT       NOT NULL                COMMENT '主键ID（雪花算法）',
  `homework_id`      BIGINT       NOT NULL                COMMENT '作业ID（→hw_homework.id）',
  `question_id`      BIGINT       NOT NULL                COMMENT '题目物理ID（→qb_question.id）',
  `question_version` INT          NOT NULL                COMMENT '锁定的题目版本号（发布时刻 current_version 快照，→qb_question_version.version）',
  `score`            DECIMAL(6,2) NOT NULL DEFAULT 0.00   COMMENT '本题在该作业中的分值',
  `sort`             INT          NOT NULL DEFAULT 0      COMMENT '题目排列顺序（升序）',
  `tenant_id`        BIGINT       NOT NULL                COMMENT '租户（机构）ID',
  `create_by`        BIGINT       NULL DEFAULT NULL       COMMENT '创建人 user_id',
  `create_time`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by`        BIGINT       NULL DEFAULT NULL       COMMENT '更新人 user_id',
  `update_time`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted_at`       BIGINT      NOT NULL DEFAULT 0      COMMENT '逻辑删除标记：0=未删除；删除时写入毫秒时间戳。用时间戳而非 0/1，使同一业务键可容纳任意多条已删除行（唯一索引末尾追加本列）',
  `remark`           VARCHAR(500) NULL DEFAULT NULL       COMMENT '备注',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_homework_question` (`homework_id`, `question_id`, `deleted_at`) COMMENT '契约 UK(homework_id,question_id)：同一作业不可重复选同一题（追加 deleted_at 兼容逻辑删除）',
  KEY `idx_question_id` (`question_id`) COMMENT '按题目反查被哪些作业引用（停用校验，错误码 30001）'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '作业-题目表（发布时固化版本）';

-- ----------------------------------------------------------------------------
-- 34. hw_homework_target 作业分发对象表（全量精确到学生）
--     批量入口（按节点/标签/名下全体）在服务层展开为学生行，
--     由 grant_source + source_ref_id 记录来源以支持整批撤回与追溯；
--     展开时目标学生必须落在分发者子树内（契约 2.4），标签不得扩大范围。
-- ----------------------------------------------------------------------------
DROP TABLE IF EXISTS `hw_homework_target`;
CREATE TABLE `hw_homework_target` (
  `id`            BIGINT       NOT NULL                COMMENT '主键ID（雪花算法）',
  `homework_id`   BIGINT       NOT NULL                COMMENT '作业ID（→hw_homework.id）',
  `student_id`    BIGINT       NOT NULL                COMMENT '分发目标学员ID（→org_student.id）',
  `grant_source`  TINYINT      NOT NULL DEFAULT 1      COMMENT '分发来源：1手动选择 2按节点批量 3按标签批量 4按名下全体（契约 §5 grant_source；作业分发不使用 5按权限模板）',
  `source_ref_id` BIGINT       NULL DEFAULT NULL       COMMENT '批量来源对象ID（grant_source=2→org_node.id，=3→org_tag.id，=4→org_node.id(分发者节点)；=1 时为 NULL）',
  `tenant_id`     BIGINT       NOT NULL                COMMENT '租户（机构）ID',
  `create_by`     BIGINT       NULL DEFAULT NULL       COMMENT '创建人 user_id',
  `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by`     BIGINT       NULL DEFAULT NULL       COMMENT '更新人 user_id',
  `update_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted_at`    BIGINT      NOT NULL DEFAULT 0      COMMENT '逻辑删除：0=未删除，删除时写入毫秒时间戳（撤回分发走逻辑删除）',
  `remark`        VARCHAR(500) NULL DEFAULT NULL       COMMENT '备注',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_homework_student` (`homework_id`, `student_id`, `deleted_at`) COMMENT '契约 UK(homework_id,student_id)：同一学员不可重复分发同一作业（追加 deleted_at 兼容逻辑删除）',
  KEY `idx_student_homework` (`student_id`, `homework_id`) COMMENT '学生端拉取本人待办作业列表（最高频）',
  KEY `idx_source` (`grant_source`, `source_ref_id`) COMMENT '按批量来源整批撤回分发 / 追溯分发由来'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '作业分发对象表（全量精确到学生）';

-- ----------------------------------------------------------------------------
-- 35. hw_answer_sheet 学生答卷表
--     teacher_node_id 为"作答时刻导师节点"快照：
--     提交时固化，学员转导师后该行仍归原导师的历史业绩统计（契约 2.6 历史归原导师）。
-- ----------------------------------------------------------------------------
DROP TABLE IF EXISTS `hw_answer_sheet`;
CREATE TABLE `hw_answer_sheet` (
  `id`               BIGINT       NOT NULL                COMMENT '答卷ID（雪花算法）',
  `homework_id`      BIGINT       NOT NULL                COMMENT '作业ID（→hw_homework.id）',
  `student_id`       BIGINT       NOT NULL                COMMENT '学生ID（→org_student.id）',
  `teacher_node_id`  BIGINT       NULL DEFAULT NULL       COMMENT '作答时刻导师节点ID快照（→org_node.id，node_type=2；NULL=作答时学员直挂管理员节点、尚未分配导师；转导师不影响历史业绩口径）',
  `status`           TINYINT      NOT NULL DEFAULT 0      COMMENT '答卷状态：0未开始 1作答中 2已提交待批改 3已批改 4逾期未交',
  `objective_score`  DECIMAL(6,2) NULL DEFAULT NULL       COMMENT '客观题得分（提交时系统自动判分写入）',
  `subjective_score` DECIMAL(6,2) NULL DEFAULT NULL       COMMENT '主观题得分（教师批改完成后写入）',
  `total_score`      DECIMAL(6,2) NULL DEFAULT NULL       COMMENT '总得分（=客观+主观，批改完成后写入）',
  `submit_time`      DATETIME     NULL DEFAULT NULL       COMMENT '提交时间（重复提交返回错误码 30003）',
  `is_late`          TINYINT      NOT NULL DEFAULT 0      COMMENT '是否逾期提交：0否 1是',
  `grade_teacher_id` BIGINT       NULL DEFAULT NULL       COMMENT '批改人 user_id（→sys_user.id；教师与管理员均可批改，故不指向 org_teacher）',
  `grade_time`       DATETIME     NULL DEFAULT NULL       COMMENT '批改完成时间',
  `tenant_id`        BIGINT       NOT NULL                COMMENT '租户（机构）ID',
  `create_by`        BIGINT       NULL DEFAULT NULL       COMMENT '创建人 user_id',
  `create_time`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by`        BIGINT       NULL DEFAULT NULL       COMMENT '更新人 user_id',
  `update_time`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted_at`       BIGINT      NOT NULL DEFAULT 0      COMMENT '逻辑删除：0=未删除，删除时写入毫秒时间戳（答卷禁止物理删除）',
  `remark`           VARCHAR(500) NULL DEFAULT NULL       COMMENT '备注',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_homework_student` (`homework_id`, `student_id`, `deleted_at`) COMMENT '契约 UK(homework_id,student_id)：一名学生一份作业仅一份答卷（追加 deleted_at 兼容逻辑删除）',
  KEY `idx_homework_status` (`homework_id`, `status`) COMMENT '教师批改流水线拉取待批改答卷/提交率统计（高频）',
  KEY `idx_student_submit` (`student_id`, `submit_time`) COMMENT '学生个人档案历史成绩曲线按时间排序',
  KEY `idx_teacher_node_homework` (`teacher_node_id`, `homework_id`) COMMENT '导师维度上卷：作业按时提交率/成绩导出按作答时刻节点快照统计（历史归原导师，转导师后原导师看板不变）'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '学生答卷表';

-- ----------------------------------------------------------------------------
-- 36. hw_answer_detail 逐题作答明细表
-- ----------------------------------------------------------------------------
DROP TABLE IF EXISTS `hw_answer_detail`;
CREATE TABLE `hw_answer_detail` (
  `id`               BIGINT       NOT NULL                COMMENT '明细ID（雪花算法）',
  `answer_sheet_id`  BIGINT       NOT NULL                COMMENT '答卷ID（→hw_answer_sheet.id）',
  `question_id`      BIGINT       NOT NULL                COMMENT '题目物理ID（→qb_question.id）',
  `question_version` INT          NOT NULL                COMMENT '作答时刻的题目版本号快照（与作业固化版本一致）',
  `student_id`       BIGINT       NOT NULL                COMMENT '【冗余】学生ID（→org_student.id）：与 answer_sheet_id 指向的 hw_answer_sheet.student_id 恒等，提交时一并写入。冗余理由见 submit_date——错题榜的分子分母都是 COUNT(DISTINCT student_id)，无此列则索引不覆盖，仍需为每行回表',
  `teacher_node_id`  BIGINT       NULL DEFAULT NULL       COMMENT '【冗余】作答时刻导师节点ID快照（→org_node.id，node_type=2）：与 hw_answer_sheet.teacher_node_id 同源同时刻写入，NULL=作答时尚未分配导师。冗余理由见 submit_date',
  `submit_date`      DATE         NULL DEFAULT NULL       COMMENT '【冗余】提交日期（= hw_answer_sheet.submit_time 的日期部分），与 student_id / teacher_node_id 在提交时**一并写入、不得二次计算**（三者必须与答卷头同源同时刻，否则错题榜与作业分析会得出两个不同的归属）。NULL = 尚未提交，故本列非空即等价于答卷 status ∈ {2,3}，无需再回头 join 判状态。三列冗余是为高频错题榜（PRD F4-1 / 03-05 §2.3(4)）：本表稳态 3600 万行，不冗余则只能先按 teacher_node_id 从 hw_answer_sheet 捞上万个 sheet_id（且 idx_teacher_node_homework 不含 submit_time，拿不到时间范围）再 IN 查明细分组聚合，看板首屏 2s 达不到',
  `student_answer`   JSON         NULL                    COMMENT '学生作答内容（JSON：选项/填空文本/简答富文本）',
  `is_correct`       TINYINT      NULL DEFAULT NULL       COMMENT '判定结果：0错误 1正确 2半对(多选漏选/填空部分对/主观题部分得分) NULL待批改（主观题批改前）；三态口径：正确率+漏选率+错误率=100%，半对不计入错误率',
  `score`            DECIMAL(6,2) NULL DEFAULT NULL       COMMENT '本题得分',
  `auto_graded`      TINYINT      NOT NULL DEFAULT 1      COMMENT '判分方式：0人工 1自动',
  `comment`          VARCHAR(1000) NULL DEFAULT NULL      COMMENT '教师评语（主观题批改时填写）',
  `grade_time`       DATETIME     NULL DEFAULT NULL       COMMENT '本题判分时间',
  `tenant_id`        BIGINT       NOT NULL                COMMENT '租户（机构）ID',
  `create_by`        BIGINT       NULL DEFAULT NULL       COMMENT '创建人 user_id',
  `create_time`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by`        BIGINT       NULL DEFAULT NULL       COMMENT '更新人 user_id',
  `update_time`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted_at`       BIGINT      NOT NULL DEFAULT 0      COMMENT '逻辑删除：0=未删除，删除时写入毫秒时间戳（答卷明细禁止物理删除）',
  `remark`           VARCHAR(500) NULL DEFAULT NULL       COMMENT '备注',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_sheet_question` (`answer_sheet_id`, `question_id`, `deleted_at`) COMMENT '同一答卷同一题仅一条作答记录（追加 deleted_at 兼容逻辑删除）',
  KEY `idx_teacher_date_question` (`teacher_node_id`, `deleted_at`, `submit_date`, `question_id`, `is_correct`, `student_id`) COMMENT '【高频错题榜主路径，PRD F4-1】两个等值列（teacher_node_id、deleted_at）在前定位，submit_date 做范围裁剪，其后三列让 GROUP BY question_id / 按 is_correct 分档 / COUNT(DISTINCT student_id) 全在索引内完成——六列合起来恰好覆盖该查询引用的全部列，EXPLAIN 应为 Using index。deleted_at 不可省：它在 WHERE 里，漏掉就要为每行回表，覆盖失效（实测 EXPLAIN 退化为 Using index condition）。GROUP BY 列位于范围列之后，故仍有一次 filesort，但分组集是单个导师的题目数（百量级），可接受。缺此索引时唯一执行方式是 IN 上万个 sheet_id 扫 3600 万行明细',
  KEY `idx_question_correct` (`question_id`, `is_correct`) COMMENT '全机构维度按题目聚合错误数（不限导师，如题库质量分析）；导师维度走 idx_teacher_date_question'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '逐题作答明细表';

-- ----------------------------------------------------------------------------
-- 37. hw_wrong_book 动态错题本表（绑定题目物理 ID + 首次做错时刻版本号，规避题库更新错位）
-- ----------------------------------------------------------------------------
DROP TABLE IF EXISTS `hw_wrong_book`;
CREATE TABLE `hw_wrong_book` (
  `id`                      BIGINT       NOT NULL           COMMENT '错题记录ID（雪花算法）',
  `student_id`              BIGINT       NOT NULL           COMMENT '学生ID（→org_student.id）',
  `question_id`             BIGINT       NOT NULL           COMMENT '题目物理ID（→qb_question.id）',
  `question_version`        INT          NOT NULL           COMMENT '首次做错时刻的题目版本号（内容按此版本快照展示，不随题库更新变化）',
  `source_homework_id`      BIGINT       NULL DEFAULT NULL  COMMENT '首次做错来源作业ID（→hw_homework.id）',
  `source_answer_detail_id` BIGINT       NULL DEFAULT NULL  COMMENT '首次做错来源作答明细ID（→hw_answer_detail.id）',
  `wrong_count`             INT          NOT NULL DEFAULT 1 COMMENT '累计做错次数（再次做错 +1 并刷新 last_wrong_time）',
  `first_wrong_time`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '首次做错时间',
  `last_wrong_time`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最近做错时间',
  `master_status`           TINYINT      NOT NULL DEFAULT 0 COMMENT '掌握状态：0未掌握 1已掌握',
  `master_time`             DATETIME     NULL DEFAULT NULL  COMMENT '标记已掌握时间（master_status=1 时写入）',
  `tenant_id`               BIGINT       NOT NULL           COMMENT '租户（机构）ID',
  `create_by`               BIGINT       NULL DEFAULT NULL  COMMENT '创建人 user_id',
  `create_time`             DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by`               BIGINT       NULL DEFAULT NULL  COMMENT '更新人 user_id',
  `update_time`             DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted_at`              BIGINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除标记：0=未删除；删除时写入毫秒时间戳。用时间戳而非 0/1，使同一业务键可容纳任意多条已删除行（唯一索引末尾追加本列）',
  `remark`                  VARCHAR(500) NULL DEFAULT NULL  COMMENT '备注',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_student_question` (`student_id`, `question_id`, `deleted_at`) COMMENT '契约 UK(student_id,question_id)：一名学生一道题仅一条错题记录（重复做错走计数，追加 deleted_at 兼容逻辑删除）',
  KEY `idx_student_master` (`student_id`, `master_status`, `last_wrong_time`) COMMENT '学生端错题本按掌握状态筛选并按最近错时间排序（高频）'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '动态错题本表';

-- ============================================================================
-- 区块 7/7：stat_ 数据中心（4 张）
-- ----------------------------------------------------------------------------
-- 统计架构（契约第 4 节末）：stat_student_daily 是唯一原始事实表，
-- 凭其 teacher_node_id / node_id 快照向两个维度上卷（导师维度 → stat_teacher_daily，
-- 节点维度 → stat_node_daily），不存在第二份原始数据。
-- ----------------------------------------------------------------------------
-- 【统计表必须存分子分母，不能只存比率】（契约 §4 stat_ 表格）
--   比率不可跨天平均：AVG(每日比率) ≠ 区间真实比率（各日分母不同，等权平均失真）。
--   因此凡是比率指标，本区块一律落库【分子列 + 分母列】，禁止 AVG(比率列)。
--   stat_teacher_daily / stat_node_daily 保留的 avg_complete_rate / ontime_submit_rate
--   两个派生比率列【仅供单日直读展示】，跨天区间一律走分子分母列。
-- 【统计列分两类语义，取区间值的方式完全不同，切勿混用】（契约 §4 细化）
--   ① 累计状态型：finished_lesson_count / should_lesson_count
--      = 截至该日的【累计快照】，不是当日新增。
--      区间取值 = 取【末日行】，【严禁跨天求和】。
--      反例：第1天 10/100、第2天 15/100，两日区间真值 = 15/100 = 15%；
--            若按 Σ 算成 25/200 = 12.5% 就错了（同一门课时被重复计数）。
--   ② 当日流量型：ontime_submit_count / late_submit_count / should_submit_count / watch_seconds
--      = 当日新增量，区间取值 = 跨天求和 Σ。
--   ③ is_active / active_student_count：按天求和只得【活跃人日】；
--      区间【去重活跃人数】必须回事实表 COUNT(DISTINCT student_id)。
--   故：区间完播率   = 末日行 finished_lesson_count ÷ 末日行 should_lesson_count
--       区间按时提交率 = Σontime_submit_count ÷ Σshould_submit_count
-- 【stat_node_daily 语义 = 该节点整棵子树的聚合】（契约 §4 说明）：
--   每行自含子树全部学员数据，读某节点大屏 1 行命中，无需展开子树求和；
--   代价是【父子节点的行不可相加】（父行已包含子行），跨节点汇总只能取最近公共祖先的行。
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 38. stat_student_daily 学生日学习汇总表（唯一事实表，定时任务每日凌晨基于进度/答卷生成）
--     teacher_node_id / node_id 均为"结算时刻"双快照：
--     已结算行永不重算，导师看板与机构大屏的历史天然不变；
--     转导师当日归新导师（分母对齐，契约 2.6）。
-- ----------------------------------------------------------------------------
DROP TABLE IF EXISTS `stat_student_daily`;
CREATE TABLE `stat_student_daily` (
  `id`                       BIGINT       NOT NULL           COMMENT '主键ID（雪花算法）',
  `student_id`               BIGINT       NOT NULL           COMMENT '学生ID（→org_student.id）',
  `teacher_node_id`          BIGINT       NULL DEFAULT NULL  COMMENT '结算时刻导师节点ID快照（→org_node.id，node_type=2；NULL=结算时直挂管理员节点、尚未分配导师；已结算行永不重算）',
  `node_id`                  BIGINT       NOT NULL           COMMENT '结算时刻该学生节点的直属父节点ID快照（→org_node.id；学生节点恒有父节点故非空；已结算行永不重算，机构大屏按此列上卷）',
  `stat_date`                DATE         NOT NULL           COMMENT '统计日期（自然日，结算单位）',
  `watch_seconds`            INT          NOT NULL DEFAULT 0 COMMENT '【当日流量型】当日观看视频总秒数（墙钟有效累计）；当日新增；区间取值为跨天求和',
  `finished_lesson_count`    INT          NOT NULL DEFAULT 0 COMMENT '【累计状态型】【完播率分子】累计快照：截至本日已完播课时数（不是当日新增）；区间取值取末日行，严禁跨天求和',
  `should_lesson_count`      INT          NOT NULL DEFAULT 0 COMMENT '【累计状态型】【完播率分母】累计快照：截至本日应学课时数（已授权课程中处于学习计划内的课时数）；区间取值取末日行，严禁跨天求和',
  `ontime_submit_count`      INT          NOT NULL DEFAULT 0 COMMENT '【当日流量型】【按时提交率分子】当日按时提交作业份数（is_late=0）；当日新增；区间取值为跨天求和',
  `late_submit_count`        INT          NOT NULL DEFAULT 0 COMMENT '【当日流量型】当日逾期提交作业份数（is_late=1，与按时提交分开计，二者之和为当日实际提交总数）；当日新增；区间取值为跨天求和',
  `should_submit_count`      INT          NOT NULL DEFAULT 0 COMMENT '【当日流量型】【按时提交率分母】当日应提交作业份数（截止日为当日的已分发作业数）；当日新增；区间取值为跨天求和',
  `is_active`                TINYINT      NOT NULL DEFAULT 0 COMMENT '当日是否活跃：0否 1是（有观看或作答行为即为 1）；按天求和只得活跃人日，区间去重活跃人数须回事实表 COUNT(DISTINCT student_id)',
  `tenant_id`                BIGINT       NOT NULL           COMMENT '租户（机构）ID',
  `create_by`                BIGINT       NULL DEFAULT NULL  COMMENT '创建人 user_id（定时任务写入为 NULL）',
  `create_time`              DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by`                BIGINT       NULL DEFAULT NULL  COMMENT '更新人 user_id',
  `update_time`              DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted_at`               BIGINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除标记：0=未删除；删除时写入毫秒时间戳。用时间戳而非 0/1，使同一业务键可容纳任意多条已删除行（唯一索引末尾追加本列）',
  `remark`                   VARCHAR(500) NULL DEFAULT NULL  COMMENT '备注',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_student_date` (`student_id`, `stat_date`, `deleted_at`) COMMENT '契约 UK(student_id,stat_date)：一名学生一天一条汇总（定时任务 UPSERT 依据，追加 deleted_at 兼容逻辑删除）',
  KEY `idx_teacher_node_date` (`teacher_node_id`, `stat_date`) COMMENT '【上卷维度①】导师维度：生成 stat_teacher_daily / 导师看板历史趋势（按结算快照读取，历史归原导师）',
  KEY `idx_node_date` (`node_id`, `stat_date`) COMMENT '【上卷维度②】节点维度：结算任务按 node_id 取数生成 stat_node_daily（写入侧按 ancestors 展开子树后 IN 查询）；大屏查询侧读 stat_node_daily 单行命中，不经本索引',
  KEY `idx_tenant_date` (`tenant_id`, `stat_date`) COMMENT '机构全量按日扫描（结算任务分片、全机构趋势）'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '学生日学习汇总表（唯一事实表，含导师节点/归属节点双快照）';

-- ----------------------------------------------------------------------------
-- 39. stat_teacher_daily 导师日汇总表
--     由 stat_student_daily 按 teacher_node_id 快照二次聚合，即"导师看板/业绩"口径。
--     存分子分母而非只存比率。区间指标按列语义分两类取值：累计状态型（finished/should_lesson_count）
--     取区间末日行相除，严禁跨天求和；当日流量型（submit 类、watch_seconds）Σ分子 ÷ Σ分母。
--     一律禁止 AVG(日比率)。
-- ----------------------------------------------------------------------------
DROP TABLE IF EXISTS `stat_teacher_daily`;
CREATE TABLE `stat_teacher_daily` (
  `id`                    BIGINT       NOT NULL              COMMENT '主键ID（雪花算法）',
  `teacher_node_id`       BIGINT       NOT NULL              COMMENT '导师节点ID（→org_node.id，node_type=2）',
  `stat_date`             DATE         NOT NULL              COMMENT '统计日期（自然日）',
  `student_count`         INT          NOT NULL DEFAULT 0    COMMENT '当日名下学员数（结算时刻 teacher_node_id 指向本节点的在读学员数）',
  `watch_seconds`         BIGINT       NOT NULL DEFAULT 0    COMMENT '【当日流量型】当日名下学员观看总秒数；区间取值为跨天求和。缺此列时导师看板的学习时长需回事实表全量求和',
  `late_submit_count`     INT          NOT NULL DEFAULT 0    COMMENT '【当日流量型】当日到期作业中逾期提交的份数；区间取值为跨天求和',
  `finished_lesson_count` INT          NOT NULL DEFAULT 0    COMMENT '【累计状态型】【完播率分子】名下学员累计快照合计：截至本日已完播课时数；区间取值取末日行，严禁跨天求和',
  `should_lesson_count`   INT          NOT NULL DEFAULT 0    COMMENT '【累计状态型】【完播率分母】名下学员累计快照合计：截至本日应学课时数；区间取值取末日行，严禁跨天求和',
  `ontime_submit_count`   INT          NOT NULL DEFAULT 0    COMMENT '【当日流量型】【按时提交率分子】名下学员当日按时提交份数合计；当日新增；区间取值为跨天求和',
  `should_submit_count`   INT          NOT NULL DEFAULT 0    COMMENT '【当日流量型】【按时提交率分母】名下学员当日应提交份数合计；当日新增；区间取值为跨天求和',
  `avg_complete_rate`     DECIMAL(5,2) NOT NULL DEFAULT 0.00 COMMENT '当日完播率（0-100，两位小数）= finished_lesson_count/should_lesson_count×100，派生冗余列【仅供单日直读】；区间完播率取末日行的分子分母相除，禁止 AVG(本列)',
  `ontime_submit_rate`    DECIMAL(5,2) NOT NULL DEFAULT 0.00 COMMENT '当日按时提交率（0-100，两位小数）= ontime_submit_count/should_submit_count×100，派生冗余列【仅供单日直读】；区间按时提交率 = Σ分子÷Σ分母，禁止 AVG(本列)',
  `active_student_count`  INT          NOT NULL DEFAULT 0    COMMENT '当日活跃学员数（= SUM(stat_student_daily.is_active)）；按天求和只得活跃人日，区间去重活跃人数须回事实表 COUNT(DISTINCT student_id)',
  `tenant_id`            BIGINT       NOT NULL              COMMENT '租户（机构）ID',
  `create_by`            BIGINT       NULL DEFAULT NULL     COMMENT '创建人 user_id（定时任务写入为 NULL）',
  `create_time`          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by`            BIGINT       NULL DEFAULT NULL     COMMENT '更新人 user_id',
  `update_time`          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted_at`           BIGINT      NOT NULL DEFAULT 0    COMMENT '逻辑删除标记：0=未删除；删除时写入毫秒时间戳。用时间戳而非 0/1，使同一业务键可容纳任意多条已删除行（唯一索引末尾追加本列）',
  `remark`               VARCHAR(500) NULL DEFAULT NULL     COMMENT '备注',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_teacher_node_date` (`teacher_node_id`, `stat_date`, `deleted_at`) COMMENT '契约 UK(teacher_node_id,stat_date)：一位导师一天一条汇总（追加 deleted_at 兼容逻辑删除）',
  KEY `idx_tenant_date` (`tenant_id`, `stat_date`) COMMENT '机构管理端导师排行榜按日期聚合全部导师'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '导师日汇总表（定时任务由 stat_student_daily 上卷生成）';

-- ----------------------------------------------------------------------------
-- 40. stat_node_daily 节点日汇总表（机构/校区大屏）
--     【语义：每行 = 该节点整棵子树的聚合】（契约 §4）：
--       结算时按 org_node.ancestors 展开每个节点的子树，把子树内全部学员的
--       stat_student_daily 汇总为一行写入。读某节点大屏只需 1 行命中，无需展开求和。
--       代价：父子节点的行【不可相加】（父行已包含子行数据），跨节点汇总时
--       只能取最近公共祖先的那一行，绝不能对多行 SUM，否则重复计数。
--     存分子分母而非只存比率。区间指标按列语义分两类取值：累计状态型（finished/should_lesson_count）
--     取区间末日行相除，严禁跨天求和；当日流量型（submit 类、watch_seconds）Σ分子 ÷ Σ分母。
--     一律禁止 AVG(日比率)。
-- ----------------------------------------------------------------------------
DROP TABLE IF EXISTS `stat_node_daily`;
CREATE TABLE `stat_node_daily` (
  `id`                    BIGINT       NOT NULL              COMMENT '主键ID（雪花算法）',
  `node_id`               BIGINT       NOT NULL              COMMENT '组织节点ID（→org_node.id）；本行为【该节点整棵子树】的聚合，非直属汇总，父子行不可相加',
  `stat_date`             DATE         NOT NULL              COMMENT '统计日期（自然日）',
  `student_count`         INT          NOT NULL DEFAULT 0    COMMENT '当日该节点子树内在读学员总数',
  `watch_seconds`         BIGINT       NOT NULL DEFAULT 0    COMMENT '【当日流量型】子树内学员当日观看总秒数；区间取值为跨天求和。大屏总学习时长 KPI 直读本列，避免回事实表全子树求和（那会废掉本表读大屏1行命中的设计初衷）',
  `finished_lesson_count` INT          NOT NULL DEFAULT 0    COMMENT '【累计状态型】【完播率分子】子树内学员累计快照合计：截至本日已完播课时数；区间取值取末日行，严禁跨天求和',
  `should_lesson_count`   INT          NOT NULL DEFAULT 0    COMMENT '【累计状态型】【完播率分母】子树内学员累计快照合计：截至本日应学课时数；区间取值取末日行，严禁跨天求和',
  `ontime_submit_count`   INT          NOT NULL DEFAULT 0    COMMENT '【当日流量型】【按时提交率分子】子树内学员当日按时提交份数合计；当日新增；区间取值为跨天求和',
  `should_submit_count`   INT          NOT NULL DEFAULT 0    COMMENT '【当日流量型】【按时提交率分母】子树内学员当日应提交份数合计；当日新增；区间取值为跨天求和',
  `avg_complete_rate`     DECIMAL(5,2) NOT NULL DEFAULT 0.00 COMMENT '当日完播率（0-100，两位小数）= finished_lesson_count/should_lesson_count×100，派生冗余列【仅供单日直读】；区间完播率取末日行的分子分母相除，禁止 AVG(本列)',
  `ontime_submit_rate`    DECIMAL(5,2) NOT NULL DEFAULT 0.00 COMMENT '当日按时提交率（0-100，两位小数）= ontime_submit_count/should_submit_count×100，派生冗余列【仅供单日直读】；区间按时提交率 = Σ分子÷Σ分母，禁止 AVG(本列)',
  `active_student_count`  INT          NOT NULL DEFAULT 0    COMMENT '当日子树内活跃学员数（= SUM(stat_student_daily.is_active)）；按天求和只得活跃人日，区间去重活跃人数须回事实表 COUNT(DISTINCT student_id)',
  `tenant_id`            BIGINT       NOT NULL              COMMENT '租户（机构）ID',
  `create_by`            BIGINT       NULL DEFAULT NULL     COMMENT '创建人 user_id（定时任务写入为 NULL）',
  `create_time`          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by`            BIGINT       NULL DEFAULT NULL     COMMENT '更新人 user_id',
  `update_time`          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted_at`           BIGINT      NOT NULL DEFAULT 0    COMMENT '逻辑删除标记：0=未删除；删除时写入毫秒时间戳。用时间戳而非 0/1，使同一业务键可容纳任意多条已删除行（唯一索引末尾追加本列）',
  `remark`               VARCHAR(500) NULL DEFAULT NULL     COMMENT '备注',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_node_date` (`node_id`, `stat_date`, `deleted_at`) COMMENT '契约 UK(node_id,stat_date)：一个节点一天一条汇总（追加 deleted_at 兼容逻辑删除）',
  KEY `idx_tenant_date` (`tenant_id`, `stat_date`) COMMENT '机构大屏按日期批量取数（结算任务写入分片）'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '节点日汇总表（定时任务由 stat_student_daily 上卷生成）';

-- ----------------------------------------------------------------------------
-- 41. stat_export_task 报表导出任务表（异步生成 Excel）
-- ----------------------------------------------------------------------------
DROP TABLE IF EXISTS `stat_export_task`;
CREATE TABLE `stat_export_task` (
  `id`           BIGINT       NOT NULL                COMMENT '任务ID（雪花算法）',
  `export_type`  TINYINT      NOT NULL                COMMENT '导出类型：1学员学习进度 2学员作业成绩',
  `biz_params`   JSON         NULL                    COMMENT '导出条件参数（JSON，如 {"nodeId":"1953827104412590402","teacherNodeId":null,"dateRange":["2026-08-01","2026-08-31"]}；nodeId 表示按该节点子树导出）',
  `status`       TINYINT      NOT NULL DEFAULT 0      COMMENT '任务状态：0排队 1生成中 2完成 3失败（失败对应错误码 40001）',
  `file_id`      BIGINT       NULL DEFAULT NULL       COMMENT '生成的报表文件ID（→sys_file.id，status=2 时写入）',
  `fail_reason`  VARCHAR(500) NULL DEFAULT NULL       COMMENT '失败原因（status=3 时写入）',
  `applicant_id` BIGINT       NOT NULL                COMMENT '申请人 user_id',
  `tenant_id`    BIGINT       NOT NULL                COMMENT '租户（机构）ID',
  `create_by`    BIGINT       NULL DEFAULT NULL       COMMENT '创建人 user_id',
  `create_time`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by`    BIGINT       NULL DEFAULT NULL       COMMENT '更新人 user_id',
  `update_time`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted_at`   BIGINT      NOT NULL DEFAULT 0      COMMENT '逻辑删除标记：0=未删除；删除时写入毫秒时间戳。用时间戳而非 0/1，使同一业务键可容纳任意多条已删除行（唯一索引末尾追加本列）',
  `remark`       VARCHAR(500) NULL DEFAULT NULL       COMMENT '备注',
  PRIMARY KEY (`id`),
  KEY `idx_applicant_time` (`applicant_id`, `create_time`) COMMENT '我的导出任务列表按时间倒序',
  KEY `idx_status` (`status`) COMMENT '异步 Worker 扫描排队中任务'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '报表导出任务表（异步）';

SET FOREIGN_KEY_CHECKS = 1;

-- ============================================================================
-- ============================================================================
-- 最小初始化数据（四层组织树：平台超管 → 机构管理员 → 教师 → 学生）
-- 说明：
--   1) 每个节点都是一个人，node_type 与 sys_user.user_type 取值一致（0超管 1管理员 2教师 3学生）；
--      不存在独立于人的组织单元节点，组织层级由管理员节点的嵌套表达。
--   2) ref_user_id 非空，故落库顺序固定为「先插 sys_user → 再插 org_node → 回写 sys_user.node_id」。
--   3) 密码为 BCrypt 占位串，部署时必须替换为真实哈希（示例明文 Admin@123）。
--   4) 租户开通的三步同事务（契约 §2.1）：插租户行(root_node_id 暂空) → 插机构管理员节点 → 回写 root_node_id。
--      机构最高管理员节点的 id 即该租户的 tenant_id。
--
--   树形：
--     平台超管 0（node_type=0, ancestors=''）
--       └─ 机构管理员 ...590001（1, ancestors='0'，id = tenant_id）
--            └─ 教师 ...590403（2, ancestors='0,...590001'）
--                 └─ 学生 ...590404（3, 叶子）
--   任一角色的可见范围 = 自身节点子树，无第二套规则。
--   停用语义：管理员节点 status=1 → 其整棵子树禁止登录；教师/学生节点 status=1 → 仅本人。
-- ============================================================================

-- 平台超管账号（tenant_id=0；node_id=0 即平台根 → 子树=全平台，无需特例分支）
INSERT INTO `sys_user` (`id`, `username`, `password`, `user_type`, `real_name`, `phone`, `avatar`, `node_id`, `status`, `pwd_reset_flag`, `last_login_time`, `tenant_id`, `create_by`, `create_time`, `update_time`, `deleted_at`, `remark`)
VALUES (1953827104412590101, 'superadmin', '$2a$10$BCRYPT_PLACEHOLDER_REPLACE_ON_DEPLOY_0000000000000000', 0, '平台超级管理员', NULL, NULL, 0, 0, 1, NULL, 0, NULL, '2026-08-12 10:00:00', '2026-08-12 10:00:00', 0, '平台运营方超管，部署后立即改密');

-- 平台根节点（全表唯一 id=0 / parent_id=-1 / ancestors=''，tenant_id=0，不属于任何租户）
INSERT INTO `org_node` (`id`, `parent_id`, `ancestors`, `node_name`, `node_type`, `ref_user_id`, `sort`, `status`, `child_count`, `student_count`, `tenant_id`, `create_by`, `create_time`, `update_time`, `deleted_at`, `remark`)
VALUES (0, -1, '', 'EduMatrix 平台', 0, 1953827104412590101, 0, 0, 1, 1, 0, NULL, '2026-08-12 10:00:00', '2026-08-12 10:00:00', 0, '平台根节点，全系统唯一');

-- 四个默认角色（平台内置，tenant_id=0；仅定义操作权限，数据范围由所在节点子树决定）
INSERT INTO `sys_role` (`id`, `role_name`, `role_key`, `status`, `sort`, `tenant_id`, `create_by`, `create_time`, `update_time`, `deleted_at`, `remark`)
VALUES
(1953827104412590201, '平台超管',  'super_admin', 0, 1, 0, 1953827104412590101, '2026-08-12 10:00:00', '2026-08-12 10:00:00', 0, '运营方，跨租户管理租户开通'),
(1953827104412590202, '管理员',    'org_admin',   0, 2, 0, 1953827104412590101, '2026-08-12 10:00:00', '2026-08-12 10:00:00', 0, '机构各级管理员，角色相同、差别仅在树的位置'),
(1953827104412590203, '教师',      'teacher',     0, 3, 0, 1953827104412590101, '2026-08-12 10:00:00', '2026-08-12 10:00:00', 0, '导师，其直接子节点即名下学员'),
(1953827104412590204, '学生',      'student',     0, 4, 0, 1953827104412590101, '2026-08-12 10:00:00', '2026-08-12 10:00:00', 0, '学员，叶子节点');

INSERT INTO `sys_user_role` (`id`, `user_id`, `role_id`, `tenant_id`, `create_by`, `create_time`, `update_time`, `deleted_at`, `remark`)
VALUES (1953827104412590301, 1953827104412590101, 1953827104412590201, 0, 1953827104412590101, '2026-08-12 10:00:00', '2026-08-12 10:00:00', 0, NULL);

-- ---- 示例租户：开通三步同事务（契约 §2.1 解循环依赖）----
-- 第 1 步：插租户行，root_node_id 暂空
INSERT INTO `sys_tenant` (`id`, `root_node_id`, `name`, `contact_name`, `contact_phone`, `expire_time`, `status`, `max_student_count`, `create_by`, `create_time`, `update_time`, `deleted_at`, `remark`)
VALUES (1953827104412590001, NULL, '示例教育机构（演示租户）', '张老师', '13800000000', '2027-08-12 00:00:00', 0, 500, 1953827104412590101, '2026-08-12 10:00:00', '2026-08-12 10:00:00', 0, '开发联调用演示租户');

-- 第 2 步：机构最高管理员（账号 → 节点，节点 id = tenant_id，挂在平台根下）
INSERT INTO `sys_user` (`id`, `username`, `password`, `user_type`, `real_name`, `phone`, `avatar`, `node_id`, `status`, `pwd_reset_flag`, `last_login_time`, `tenant_id`, `create_by`, `create_time`, `update_time`, `deleted_at`, `remark`)
VALUES (1953827104412590102, '13800000001', '$2a$10$BCRYPT_PLACEHOLDER_REPLACE_ON_DEPLOY_0000000000000000', 1, '示例机构管理员', '13800000001', NULL, 1953827104412590001, 0, 1, NULL, 1953827104412590001, 1953827104412590101, '2026-08-12 10:00:00', '2026-08-12 10:00:00', 0, '机构最高管理员，其子树 = 全机构');

INSERT INTO `org_node` (`id`, `parent_id`, `ancestors`, `node_name`, `node_type`, `ref_user_id`, `sort`, `status`, `child_count`, `student_count`, `tenant_id`, `create_by`, `create_time`, `update_time`, `deleted_at`, `remark`)
VALUES (1953827104412590001, 0, '0', '示例教育机构（演示租户）', 1, 1953827104412590102, 0, 0, 1, 1, 1953827104412590001, 1953827104412590101, '2026-08-12 10:00:00', '2026-08-12 10:00:00', 0, '机构最高管理员节点：id = tenant_id = sys_tenant.root_node_id');

-- 第 3 步：回写 root_node_id（以上三步必须在同一事务内）
UPDATE `sys_tenant` SET `root_node_id` = 1953827104412590001 WHERE `id` = 1953827104412590001;

INSERT INTO `sys_user_role` (`id`, `user_id`, `role_id`, `tenant_id`, `create_by`, `create_time`, `update_time`, `deleted_at`, `remark`)
VALUES (1953827104412590302, 1953827104412590102, 1953827104412590202, 1953827104412590001, 1953827104412590101, '2026-08-12 10:00:00', '2026-08-12 10:00:00', 0, NULL);

-- ---- 示例教师（node_type=2，其子节点即名下学员）----
INSERT INTO `sys_user` (`id`, `username`, `password`, `user_type`, `real_name`, `phone`, `avatar`, `node_id`, `status`, `pwd_reset_flag`, `last_login_time`, `tenant_id`, `create_by`, `create_time`, `update_time`, `deleted_at`, `remark`)
VALUES (1953827104412590103, '13800000002', '$2a$10$BCRYPT_PLACEHOLDER_REPLACE_ON_DEPLOY_0000000000000000', 2, '示例导师', '13800000002', NULL, 1953827104412590403, 0, 1, NULL, 1953827104412590001, 1953827104412590102, '2026-08-12 10:00:00', '2026-08-12 10:00:00', 0, NULL);

INSERT INTO `org_node` (`id`, `parent_id`, `ancestors`, `node_name`, `node_type`, `ref_user_id`, `sort`, `status`, `child_count`, `student_count`, `tenant_id`, `create_by`, `create_time`, `update_time`, `deleted_at`, `remark`)
VALUES (1953827104412590403, 1953827104412590001, '0,1953827104412590001', '示例导师', 2, 1953827104412590103, 0, 0, 1, 1, 1953827104412590001, 1953827104412590102, '2026-08-12 10:00:00', '2026-08-12 10:00:00', 0, NULL);

INSERT INTO `sys_user_role` (`id`, `user_id`, `role_id`, `tenant_id`, `create_by`, `create_time`, `update_time`, `deleted_at`, `remark`)
VALUES (1953827104412590303, 1953827104412590103, 1953827104412590203, 1953827104412590001, 1953827104412590102, '2026-08-12 10:00:00', '2026-08-12 10:00:00', 0, NULL);

INSERT INTO `org_teacher` (`id`, `node_id`, `user_id`, `teacher_no`, `subject`, `title`, `entry_date`, `tenant_id`, `create_by`, `create_time`, `update_time`, `deleted_at`, `remark`)
VALUES (1953827104412590501, 1953827104412590403, 1953827104412590103, 'T0001', '数学', '高级教师', '2026-03-01', 1953827104412590001, 1953827104412590102, '2026-08-12 10:00:00', '2026-08-12 10:00:00', 0, NULL);

-- ---- 示例学生（node_type=3，叶子；挂在教师下即该导师名下学员）----
INSERT INTO `sys_user` (`id`, `username`, `password`, `user_type`, `real_name`, `phone`, `avatar`, `node_id`, `status`, `pwd_reset_flag`, `last_login_time`, `tenant_id`, `create_by`, `create_time`, `update_time`, `deleted_at`, `remark`)
VALUES (1953827104412590104, '13800000003', '$2a$10$BCRYPT_PLACEHOLDER_REPLACE_ON_DEPLOY_0000000000000000', 3, '示例学员', '13800000003', NULL, 1953827104412590404, 0, 1, NULL, 1953827104412590001, 1953827104412590102, '2026-08-12 10:00:00', '2026-08-12 10:00:00', 0, NULL);

INSERT INTO `org_node` (`id`, `parent_id`, `ancestors`, `node_name`, `node_type`, `ref_user_id`, `sort`, `status`, `child_count`, `student_count`, `tenant_id`, `create_by`, `create_time`, `update_time`, `deleted_at`, `remark`)
VALUES (1953827104412590404, 1953827104412590403, '0,1953827104412590001,1953827104412590403', '示例学员', 3, 1953827104412590104, 0, 0, 0, 0, 1953827104412590001, 1953827104412590102, '2026-08-12 10:00:00', '2026-08-12 10:00:00', 0, NULL);

INSERT INTO `sys_user_role` (`id`, `user_id`, `role_id`, `tenant_id`, `create_by`, `create_time`, `update_time`, `deleted_at`, `remark`)
VALUES (1953827104412590304, 1953827104412590104, 1953827104412590204, 1953827104412590001, 1953827104412590102, '2026-08-12 10:00:00', '2026-08-12 10:00:00', 0, NULL);

INSERT INTO `org_student` (`id`, `node_id`, `user_id`, `student_no`, `guardian_name`, `guardian_phone`, `status`, `quit_time`, `quit_reason`, `archive_time`, `tenant_id`, `create_by`, `create_time`, `update_time`, `deleted_at`, `remark`)
VALUES (1953827104412590601, 1953827104412590404, 1953827104412590104, 'S0001', '示例家长', '13800000004', 0, NULL, NULL, NULL, 1953827104412590001, 1953827104412590102, '2026-08-12 10:00:00', '2026-08-12 10:00:00', 0, NULL);

-- ============================================================================
-- DDL 结束（sys_ 10 + org_ 10 + crs_ 4 + vod_ 4 + qb_ 3 + hw_ 6 + stat_ 4，共 41 张，
-- 覆盖设计契约第 4 节与 8.1 节全部表清单）
-- ============================================================================
