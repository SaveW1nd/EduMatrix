package com.edumatrix.system.tenant.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.TableName;
import com.edumatrix.common.entity.BaseEntity;

/**
 * {@code sys_tenant} 机构（租户）表（03-01 §5，契约 §2.1）。
 *
 * <h2>它继承 {@link BaseEntity} 而不是 {@code TenantEntity}</h2>
 * <p>{@code sys_tenant} <b>没有 {@code tenant_id} 列</b>——全库仅它与 {@code sys_menu}
 * 两张如此（契约 §2.9 / {@code EduMatrixTenantLineHandler}）。租户处理器的
 * {@code ignoreTable} 对它返回 true，<b>它压根不进租户插件</b>。
 * 继承 {@code TenantEntity} 会让 SELECT 带上一个不存在的列，运行期 {@code Unknown column}。
 *
 * <h2>{@link #id} 与 {@link #rootNodeId} 是同一个值</h2>
 * <p>DDL 对 {@code id} 的注释逐字：「其值 = 该机构在 {@code org_node} 上的根节点 id」；
 * 03-01 §5.3 的响应字段说明亦逐字：{@code rootNodeId}「<b>其值等于租户 {@code id}，
 * 也等于 {@code adminNodeId}</b>」。三个字段并列只为调用方按语义取用。
 *
 * <p>那为什么还留一列 {@code root_node_id}？因为<b>它承担的是"开通是否完成"这个状态</b>：
 * 开通租户存在循环依赖（根节点的 {@code tenant_id} 来自租户行 id，租户行的
 * {@code root_node_id} 来自根节点 id），落库顺序固定为「插租户行（{@code root_node_id} 暂空）
 * → 插机构根节点 → 回写」，三步同一事务（契约 §2.1）。所以这一列<b>必须允许 NULL</b>，
 * 而它为 NULL 的那一瞬只存在于事务内部，对外永远不可见。
 */
@TableName("sys_tenant")
public class SysTenant extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** 0 正常。 */
    public static final int STATUS_NORMAL = 0;
    /** 1 停用（PRD F1-1 规则 4：等同到期处置，用于欠费/违规冻结）。 */
    public static final int STATUS_DISABLED = 1;

    /**
     * 机构根节点 ID（{@code → org_node.id}），值与本表 {@code id} 相同。
     *
     * <p><b>创建后不可变更</b>（03-01 §5.0 / §5.4：改动即等同于跨租户搬迁，本期不支持）；
     * 删除/停用租户<b>不改动该指向</b>（§5.5：以便恢复）。
     */
    private Long rootNodeId;

    /** 机构名称，最长 50 字（DDL 列宽 100），<b>全平台唯一</b>（{@code uk_name}）。 */
    private String name;

    private String contactName;

    private String contactPhone;

    /**
     * 服务到期时间（{@code NULL} = 永久）。
     *
     * <p>到期后该机构全部账号登录被拒（{@code 10007}），<b>数据保留不删除，续期后恢复</b>
     * （PRD F1-1 规则 3）。判定在 {@code auth} 的 {@code LoginCheckService}，本模块只写不判。
     * 调整到期时间走 §5.6 续期接口，§5.4 修改租户<b>不碰这一列</b>。
     */
    private LocalDateTime expireTime;

    /** 0 正常 1 停用。停用机构走本列，<b>不停用机构根节点</b>（PRD F1-1 规则 7）。 */
    private Integer status;

    /**
     * 学生账号数上限（0 = 不限制）。
     *
     * <p>在读学生数（{@code org_student.status = 0}）达到本值时，新增/导入学生被拒
     * → {@code 10207}（PRD F1-1 规则 5）。<b>「在读」的口径全系统只有一个</b>：
     * 按 {@code org_student} 的在读行数计，与模块 07 同口径（§E 的 F-22 已定案，
     * 落地见 {@code system/user/mapper/StudentQuotaMapper} 的类注释）。
     */
    private Integer maxStudentCount;

    public Long getRootNodeId() {
        return rootNodeId;
    }

    public void setRootNodeId(Long rootNodeId) {
        this.rootNodeId = rootNodeId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getContactName() {
        return contactName;
    }

    public void setContactName(String contactName) {
        this.contactName = contactName;
    }

    public String getContactPhone() {
        return contactPhone;
    }

    public void setContactPhone(String contactPhone) {
        this.contactPhone = contactPhone;
    }

    public LocalDateTime getExpireTime() {
        return expireTime;
    }

    public void setExpireTime(LocalDateTime expireTime) {
        this.expireTime = expireTime;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public Integer getMaxStudentCount() {
        return maxStudentCount;
    }

    public void setMaxStudentCount(Integer maxStudentCount) {
        this.maxStudentCount = maxStudentCount;
    }
}
