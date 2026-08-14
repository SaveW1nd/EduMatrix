package com.edumatrix.system.menu.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.edumatrix.common.entity.BaseEntity;

/**
 * {@code sys_menu} 菜单/按钮权限表（03-01 §4，契约 §2.1）。
 *
 * <h2>它继承 {@link BaseEntity} 而不是 {@code TenantEntity}</h2>
 * <p>{@code sys_menu} 是<b>平台级表，没有 {@code tenant_id} 列</b>（全库仅它与
 * {@code sys_tenant} 两张如此）。租户处理器的 {@code ignoreTable} 因此对它返回 true ——
 * 它压根不进租户插件。各租户通过<b>角色-菜单关联</b>获得可用子集，
 * 而不是靠每个租户复制一份菜单树。
 *
 * <p>继承 {@code TenantEntity} 会让 MyBatis-Plus 往 SELECT 里带一个不存在的
 * {@code tenant_id} 列，运行期 {@code Unknown column} —— 与
 * {@code BaseEntity} 类注释里 {@code vod_heartbeat_log} 那条是同一类错误。
 */
@TableName("sys_menu")
public class SysMenu extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** 目录：可挂目录/菜单，无 {@code perms}（124 行初始数据里有 7 行）。 */
    public static final String TYPE_DIRECTORY = "M";
    /** 菜单：对应一个前端路由，{@code perms} 形如 {@code system:user:list}。 */
    public static final String TYPE_MENU = "C";
    /** 按钮：无 {@code path}，{@code perms} 必填；<b>不可有子节点</b>。 */
    public static final String TYPE_BUTTON = "F";

    /** 顶级菜单的 {@code parent_id}（DDL 注释：0 = 顶级）。 */
    public static final long ROOT_PARENT_ID = 0L;

    /** 父菜单 ID，顶级为 {@link #ROOT_PARENT_ID}。 */
    private Long parentId;

    private String menuName;

    /** M 目录 / C 菜单 / F 按钮（契约 §5 权威值）。<b>创建后不可修改</b>（03-01 §4.3）。 */
    private String menuType;

    /**
     * 权限标识，<b>全局唯一</b>（{@code uk_perms(perms, deleted_at)}，F-12 定案）。
     *
     * <p>格式与动作词表由契约 §3.1 定死，它是<b>线上鉴权依据</b>而不是展示字符串 ——
     * 03-01 §4.3 明写「权限标识改动会影响线上鉴权，需与后端注解同步发版」。
     * 目录行为 {@code null}，MySQL 唯一索引不约束 NULL，故 7 行目录天然不冲突。
     */
    private String perms;

    /** 前端路由地址；按钮为 {@code null}。 */
    private String path;

    private String icon;

    private Integer sort;

    /**
     * 0 隐藏 1 显示（DDL DEFAULT 1）。
     *
     * <p><b>隐藏仍参与权限计算</b>（03-01 §4.1 字段说明），只是不出现在侧边导航 ——
     * 所以装配 {@code perms} 时不得按本列过滤。
     */
    private Integer visible;

    /**
     * 菜单状态：0 正常 1 停用。
     *
     * <p><b>本模块不写也不按它过滤。</b>「停用的菜单是否仍授予 perms」全套文档没有定义
     * （{@code AuthPermMapper} 类注释已就同一问题记过一次：04 §B 规则 11 描述的链路
     * 就是四张表直连，加条件就是发明一条判定规则）。§4.2/§4.3 的参数表里也没有这一列。
     */
    private Integer status;

    public Long getParentId() {
        return parentId;
    }

    public void setParentId(Long parentId) {
        this.parentId = parentId;
    }

    public String getMenuName() {
        return menuName;
    }

    public void setMenuName(String menuName) {
        this.menuName = menuName;
    }

    public String getMenuType() {
        return menuType;
    }

    public void setMenuType(String menuType) {
        this.menuType = menuType;
    }

    public String getPerms() {
        return perms;
    }

    public void setPerms(String perms) {
        this.perms = perms;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getIcon() {
        return icon;
    }

    public void setIcon(String icon) {
        this.icon = icon;
    }

    public Integer getSort() {
        return sort;
    }

    public void setSort(Integer sort) {
        this.sort = sort;
    }

    public Integer getVisible() {
        return visible;
    }

    public void setVisible(Integer visible) {
        this.visible = visible;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    /** 按钮不可有子节点（03-01 §4.2：「层级不合法——如按钮下挂子节点——返回 400」）。 */
    public boolean isButton() {
        return TYPE_BUTTON.equals(menuType);
    }
}
