-- ============================================================================
-- V202608160000__split_staff_list_perms.sql
-- 模块 07 落地时暴露：一个 org:staff:list 管着两个角色集不同的列表接口
--
-- 【问题】03-02 给这两个接口的角色集【不同】：
--     §4.1 管理员分页列表 GET /org/admins   · 权限：仅 org_admin
--     §5.1 教师分页列表   GET /org/teachers · 权限：org_admin；teacher（仅本人一条）
--   而契约 §10 附表 A 里它们共用一个页面级 org:staff:list（菜单 1949000000000100200），
--   且只绑了 super_admin 与 org_admin。一个开关管两盏灯：
--     · 不绑 teacher → 教师调 §5.1 拿 403，与分册直接冲突；
--     · 绑了 teacher → 教师能列出【全机构管理员】，与 §4.1「仅 org_admin」直接冲突。
--   两条要求两两不可兼得，这不是「哪一侧写错了」，是 perms 粒度不足以表达。
--   已登记为 04-实施计划.md §E 的 F-30，需方裁决：【拆】。
--
-- 【与模块 06 的 V202608150000 形状相似但不同】那次是「补一条绑定即可，无副作用」
--   （org:node:list 的两个接口权限栏【都】写着 teacher）。本次补绑会连带把 §4.1
--   开给教师，所以只能拆 perms，不能补绑。
--
-- 【与契约 §3.1 边界 2 的关系】边界 2 说「只有写操作才单独发按钮标识，同一页面内的
--   辅助读接口随该页 :list 一并放行」，理由是「否则按钮表会膨胀到与接口数等长」。
--   本次新增的是【两个读 perms】，形式上像是它的例外，但成因不同：
--   边界 2 防的是「按接口逐个发 perms」，而这里是【同一页面内两个接口的角色集不同】——
--   那个前提边界 2 没有考虑过。本次只拆这一处，不是开一条按接口发 perms 的口子；
--   org:staff:list 保留，继续管「能不能进人员管理页面」。
--
-- 【ID 规则】沿用附表 A 的固定值约定：id = 1949000000000000000 + 目录号×10000
--   + 菜单号×100 + 按钮号。人员管理是 (10, 2, 0)，既有按钮 1~6 已用到 …100206，
--   本次两行取按钮号 7 / 8 → …100207 / …100208，续在其后【不重排既有行】
--   （与错误码「废弃号位保留不复用」、接口编号不重排是同一条纪律）。
--   sys_role_menu 的既有 id 段末号已用到 …000201（模块 06 的 V202608150000），
--   本次五行取 …000202 ~ …000206。
--
-- 【tenant_id 必须是 0】契约 §2.9：sys_role_menu 承载平台级行，租户插件对它注入的
--   条件是 (tenant_id = ? OR tenant_id = 0)。写成别的值，这些绑定对所有租户都不可见
--   —— 表现是 perms 少了两条，而接口照样 200，属于「不报错故障」。
--   sys_menu 本身【无 tenant_id 列】（平台级表，不进插件），故不写该列。
--
-- 【幂等】INSERT ... SELECT ... WHERE NOT EXISTS，理由同 V202608150000：
--   Flyway 保证同一版本只跑一次，但开发库常有人手工补过，撞主键会让后续版本全部卡住。
--
-- 【基线一个字不改】V202608120000 与 V202608140000 均未改动（契约 §7.3）。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- ① 两个新菜单行（按钮粒度，挂在「人员管理」下，与既有六个按钮平级）
-- ---------------------------------------------------------------------------

INSERT INTO `sys_menu` (`id`, `parent_id`, `menu_name`, `menu_type`, `perms`, `path`,
                        `sort`, `visible`, `deleted_at`, `create_by`, `remark`)
SELECT 1949000000000100207, 1949000000000100200, '管理员列表', 'F', 'org:admin:list', NULL,
       7, 1, 0, 1953827104412590101,
       '03-02 §4.1：仅 org_admin。与教师列表拆开，见 F-30'
  FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM `sys_menu` WHERE `perms` = 'org:admin:list' AND `deleted_at` = 0);

INSERT INTO `sys_menu` (`id`, `parent_id`, `menu_name`, `menu_type`, `perms`, `path`,
                        `sort`, `visible`, `deleted_at`, `create_by`, `remark`)
SELECT 1949000000000100208, 1949000000000100200, '教师列表', 'F', 'org:teacher:list', NULL,
       8, 1, 0, 1953827104412590101,
       '03-02 §5.1：org_admin + teacher（教师只看到本人一条，由子树判定自然得出）'
  FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM `sys_menu` WHERE `perms` = 'org:teacher:list' AND `deleted_at` = 0);

-- ---------------------------------------------------------------------------
-- ② 五条角色绑定
--     org:admin:list   → super_admin、org_admin          （§4.1 仅 org_admin）
--     org:teacher:list → super_admin、org_admin、teacher  （§5.1 含 teacher）
--   差别就是最后那一行 —— 拆 perms 的全部意义。
-- ---------------------------------------------------------------------------

INSERT INTO `sys_role_menu` (`id`, `role_id`, `menu_id`, `tenant_id`, `create_by`, `remark`)
SELECT 1949100000000000202, 1953827104412590201, 1949000000000100207, 0,
       1953827104412590101, 'super_admin'
  FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM `sys_role_menu`
                    WHERE `role_id` = 1953827104412590201 AND `menu_id` = 1949000000000100207
                      AND `deleted_at` = 0);

INSERT INTO `sys_role_menu` (`id`, `role_id`, `menu_id`, `tenant_id`, `create_by`, `remark`)
SELECT 1949100000000000203, 1953827104412590202, 1949000000000100207, 0,
       1953827104412590101, 'org_admin'
  FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM `sys_role_menu`
                    WHERE `role_id` = 1953827104412590202 AND `menu_id` = 1949000000000100207
                      AND `deleted_at` = 0);

INSERT INTO `sys_role_menu` (`id`, `role_id`, `menu_id`, `tenant_id`, `create_by`, `remark`)
SELECT 1949100000000000204, 1953827104412590201, 1949000000000100208, 0,
       1953827104412590101, 'super_admin'
  FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM `sys_role_menu`
                    WHERE `role_id` = 1953827104412590201 AND `menu_id` = 1949000000000100208
                      AND `deleted_at` = 0);

INSERT INTO `sys_role_menu` (`id`, `role_id`, `menu_id`, `tenant_id`, `create_by`, `remark`)
SELECT 1949100000000000205, 1953827104412590202, 1949000000000100208, 0,
       1953827104412590101, 'org_admin'
  FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM `sys_role_menu`
                    WHERE `role_id` = 1953827104412590202 AND `menu_id` = 1949000000000100208
                      AND `deleted_at` = 0);

INSERT INTO `sys_role_menu` (`id`, `role_id`, `menu_id`, `tenant_id`, `create_by`, `remark`)
SELECT 1949100000000000206, 1953827104412590203, 1949000000000100208, 0,
       1953827104412590101, 'teacher（03-02 §5.1：教师可查教师列表，子树判定使其只看到本人一条）'
  FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM `sys_role_menu`
                    WHERE `role_id` = 1953827104412590203 AND `menu_id` = 1949000000000100208
                      AND `deleted_at` = 0);
