package com.edumatrix.course.catalog.service;

import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.edumatrix.common.file.FileBizType;
import com.edumatrix.common.file.FileOwnershipChecker;
import com.edumatrix.common.resource.ResourceOwnerChecker;
import com.edumatrix.common.resource.ResourceType;
import com.edumatrix.common.subtree.SubtreeScopeHelper;
import com.edumatrix.course.catalog.mapper.CrsMaterialMapper;

/**
 * {@code material_attach}（图文资料附件）的归属校验 ——
 * <b>解除模块 05 的 B-3 / F-38 fail-closed</b>。
 *
 * <h2>在它出现之前，这个 bizType 一律 404</h2>
 * <p>成因（F-38）：03-01 §7.3 把 {@code material_attach} 归在
 * 「其余 bizType <b>本租户已登录用户可下载</b>」，而 03-03 §6.3 要求学生看图文课时
 * 必须「该学生节点被显式授权该课程」，否则 {@code 20013}。
 * <b>同一份讲义，走课时接口要授权、走文件接口不要</b>，而 {@code fileId} 是雪花 ID、
 * 同租户内时间相邻<b>可近邻枚举</b>。模块 05 按「暂时下不了优于能下且不该下」先关上。
 * 本类把它按<b>授权</b>打开。
 *
 * <h2>两支判定，逐字取自 {@code crs_material} 的 DDL 列注释</h2>
 * <p>{@code owner_node_id} 的列注释原文：「<b>管理端可见性按本列做子树过滤</b>，
 * 此后不随创建人调岗漂移。<b>学生端可见性走所属课时→课程→课程授权</b>」。
 * 于是：
 * <ol>
 *   <li><b>管理端</b>：资料的 {@code owner_node_id} 在我的子树内 —— 我管得着这份资料；
 *   <li><b>使用端</b>：引用该资料的<b>任一课程</b>我 {@code canUse}
 *       （是 owner ∪ 被显式授权且在有效期内）。
 * </ol>
 * <p>第二支用 {@code canUse} 而不是 {@code canRegrant}：这里问的是<b>能不能用</b>，
 * 不是能不能再下发。跨管辖的教师<b>仍然能打开讲义</b>（契约 §2.5 规则 9：
 * 保留使用能力），只是不能再把课授给别人。用错谓词的表现是
 * <b>调岗教师的备课资料突然打不开</b>，而接口只回一个 404。
 *
 * <h2>为什么落在 {@code course/catalog} 而不是 {@code org/grant}（F-91）</h2>
 * <p>它要读 {@code crs_material} 与 {@code crs_lesson}，而
 * {@code check_backend_conventions.sh} 检查③ 禁止 {@code org} 域 import {@code course} 域。
 * SPI 接口留在 {@code common/file}，实现放在<b>拥有那两张表的领域</b> —— 与
 * {@code FileOwnershipChecker} 类注释里那张「谁来注册」的表一致。
 *
 * <h2>查不到就拒（fail closed）</h2>
 * <p>附件不属于任何资料（孤儿行、或资料已被逻辑删除）时返回 {@code false}。
 * 返回 {@code true} 才是危险的那一侧：那等于「查不到归属就放行」。
 */
@Component
public class MaterialAttachOwnershipChecker implements FileOwnershipChecker {

    private final CrsMaterialMapper materialMapper;
    private final CourseAccessGuard guard;
    private final ResourceOwnerChecker ownerChecker;
    private final SubtreeScopeHelper subtreeScope;

    public MaterialAttachOwnershipChecker(CrsMaterialMapper materialMapper,
                                          CourseAccessGuard guard,
                                          ResourceOwnerChecker ownerChecker,
                                          SubtreeScopeHelper subtreeScope) {
        this.materialMapper = materialMapper;
        this.guard = guard;
        this.ownerChecker = ownerChecker;
        this.subtreeScope = subtreeScope;
    }

    @Override
    public Set<FileBizType> supportedBizTypes() {
        return Set.of(FileBizType.MATERIAL_ATTACH);
    }

    @Override
    public boolean canAccess(FileRef file, Long userId, boolean isOrgAdmin) {
        Long myNodeId = guard.myNodeId();
        if (myNodeId == null) {
            return false;
        }
        List<CrsMaterialMapper.MaterialAttachRef> refs =
                materialMapper.selectRefsByAttachmentFileId(file.fileId());
        if (refs.isEmpty()) {
            return false;   // 查不到归属 → 拒。放行才是危险的那一侧
        }
        for (CrsMaterialMapper.MaterialAttachRef ref : refs) {
            // ① 管理端：资料归属在我子树内
            if (ref.getOwnerNodeId() != null
                    && subtreeScope.isInSubtree(myNodeId, ref.getOwnerNodeId())) {
                return true;
            }
            // ② 使用端：引用它的课程我能用（与 03-03 §6.3 的 20013 判定逐条相同）
            if (ref.getCourseId() != null
                    && ownerChecker.canUse(ResourceType.COURSE, ref.getCourseId(), myNodeId)) {
                return true;
            }
        }
        return false;
    }
}
