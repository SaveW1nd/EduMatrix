package com.edumatrix.org.grant.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.edumatrix.common.resource.ResourceOwnerChecker;
import com.edumatrix.common.resource.ResourceType;
import com.edumatrix.org.grant.mapper.GrantHealthMapper;

/**
 * 授权健康度的<b>分类口径</b> —— 巡检任务与接口 51 <b>共用这一处</b>（F-83）。
 *
 * <h2>两类必须分开，中间没有任何一处相加</h2>
 * <table border="1">
 *   <caption>契约 §2.5 规则 6</caption>
 *   <tr><th>类别</th><th>成因</th><th>处置</th><th>指标</th></tr>
 *   <tr><td>{@code dangling} 真悬挂</td><td>级联回收失效</td><td>进告警</td>
 *       <td>{@code grant_dangling_count}，<b>目标值恒 0</b></td></tr>
 *   <tr><td>{@code crossScope} 跨管辖</td><td><b>节点移动的合法产物</b></td>
 *       <td>只作待办</td><td>{@code grant_cross_scope_count}，<b>不进告警</b></td></tr>
 * </table>
 * <p>合并计数会让<b>任何一次教师调岗或学员转交</b>都使指标永久非 0，持续假警报，
 * 最终结果是运维关掉告警、真悬挂也没人看。本项目在 <b>F-20 已经为这条踩过一次</b>。
 *
 * <h2>怎么区分（F-82：以 {@code org_node_change_log} 为准）</h2>
 * <p>两者的<b>形态完全一样</b>（都是「父级无权、子级仍持有」），差别只在<b>成因</b>：
 * 一个是级联回收失效，一个是有人动了树。而<b>成因唯一的直接证据是异动轨迹</b> ——
 * 授权发生之后，目标节点<b>或其任一祖先</b>有过「移动」类异动（{@code change_type}
 * 2 分配导师 / 3 转交管理员 / 4 教师调岗 / 8 节点移动），就是跨管辖；否则是真悬挂。
 *
 * <p><b>⚠ 这条判据依赖 {@code org_node_change_log} 的保留期，已登记</b>：
 * 轨迹一旦被清理或保留期缩短到短于授权存续期，<b>跨管辖会被误判成真悬挂</b> ——
 * 表现是 {@code grant_dangling_count} 从 0 慢慢爬起来、每次教师调岗涨一点，
 * 而那正是 F-20 要防的「运维关掉告警」的开端。<b>方向是保守的</b>
 *（误判成告警而不是误判成待办），但不是没有代价。
 *
 * <p>备选判据是「看链的形状」（祖先链上还有没有人持有），那是<b>启发式</b>：
 * 同一个形状既可能来自移动也可能来自撤销失败，分不出来。
 */
@Service
public class GrantHealthService {

    private final GrantHealthMapper healthMapper;
    private final ResourceOwnerChecker ownerChecker;

    public GrantHealthService(GrantHealthMapper healthMapper, ResourceOwnerChecker ownerChecker) {
        this.healthMapper = healthMapper;
        this.ownerChecker = ownerChecker;
    }

    /**
     * 扫描当前租户，分出两类。<b>巡检任务与接口 51 都调它</b>。
     *
     * <p>调用方必须已在正确的租户上下文里（Job 走 {@code runWithTenant}，
     * 接口走会话）—— 本类<b>一个字都不写租户条件</b>（契约 §2.9）。
     */
    public HealthScan scan() {
        List<GrantHealthMapper.HealthRow> suspects = healthMapper.selectSuspects();
        if (suspects.isEmpty()) {
            return new HealthScan(List.of(), List.of());
        }

        // 第一步：去掉「父节点恰好是该资源 owner」的 —— 那不是悬挂，是正常的第一跳。
        // owner 判定走 SPI 批量取，不在 SQL 里认识那三张资源表
        Map<ResourceType, Set<Long>> idsByType = new EnumMap<>(ResourceType.class);
        suspects.forEach(row -> ResourceType.of(row.getResourceType()).ifPresent(type ->
                idsByType.computeIfAbsent(type, k -> new LinkedHashSet<>()).add(row.getResourceId())));
        Map<ResourceType, Map<Long, Long>> ownerByType = new EnumMap<>(ResourceType.class);
        idsByType.forEach((type, ids) -> ownerByType.put(type, ownerChecker.ownerNodeIdsOf(type, ids)));

        List<GrantHealthMapper.HealthRow> broken = new ArrayList<>();
        for (GrantHealthMapper.HealthRow row : suspects) {
            ResourceType type = ResourceType.of(row.getResourceType()).orElse(null);
            if (type == null) {
                continue;
            }
            Long owner = ownerByType.getOrDefault(type, Map.of()).get(row.getResourceId());
            if (owner == null) {
                // 资源已被逻辑删除 / 停用 / 查不到 —— 契约 §2.5 规则 12：
                // 指向已删除或已停用资源的授权行【不计为悬挂】。资源状态是可逆的，
                // 恢复后授权自动重新生效；把它们计进去会让指标被一批「本来就该保留」的行淹掉
                continue;
            }
            if (owner.equals(row.getParentNodeId())) {
                continue;   // 父节点就是 owner —— 正常的第一跳
            }
            broken.add(row);
        }
        if (broken.isEmpty()) {
            return new HealthScan(List.of(), List.of());
        }

        // 第二步：按异动轨迹分成两类（F-82）
        Set<Long> nodesToCheck = new LinkedHashSet<>();
        LocalDateTime earliest = null;
        for (GrantHealthMapper.HealthRow row : broken) {
            nodesToCheck.add(row.getTargetNodeId());
            nodesToCheck.addAll(
                    com.edumatrix.common.subtree.NodePath.parseAncestorIds(row.getTargetAncestors()));
            if (row.getGrantTime() != null && (earliest == null || row.getGrantTime().isBefore(earliest))) {
                earliest = row.getGrantTime();
            }
        }
        Set<Long> moved = earliest == null ? Set.of()
                : new LinkedHashSet<>(healthMapper.selectMovedNodeIds(List.copyOf(nodesToCheck), earliest));

        List<GrantHealthMapper.HealthRow> dangling = new ArrayList<>();
        List<GrantHealthMapper.HealthRow> crossScope = new ArrayList<>();
        for (GrantHealthMapper.HealthRow row : broken) {
            if (movedSince(row, moved)) {
                crossScope.add(row);
            } else {
                dangling.add(row);
            }
        }
        return new HealthScan(dangling, crossScope);
    }

    /** 目标节点或其任一祖先有过「移动」类异动。 */
    private static boolean movedSince(GrantHealthMapper.HealthRow row, Set<Long> moved) {
        if (moved.contains(row.getTargetNodeId())) {
            return true;
        }
        return com.edumatrix.common.subtree.NodePath.parseAncestorIds(row.getTargetAncestors())
                .stream().anyMatch(moved::contains);
    }

    /**
     * 30 天内到期的授权 —— <b>恒为空清单</b>（需方 2026-08-21 定案：授权一律永久有效）。
     *
     * <p><b>不是缺陷，是定案的直接后果</b>：没有到期日就没有「临期」。
     * {@code type=expiring} 这个取值<b>保留</b>（它在 §9.6 的参数表里，删它是接口签名变更），
     * 调用返回空清单、{@code total=0}，{@code summary} 的两个数照常。
     *
     * <p><b>方法保留而不是让调用方少一个分支</b>：将来若恢复有效期（例如「试听一个月」），
     * 恢复点就在这一处 —— 而 {@code GrantHealthMapper.selectExpiring} 那条 SQL 已删除，
     * 需要连同 {@code valid_end} 的写入一起重做，不是把注释解开就行。
     */
    public List<GrantHealthMapper.HealthRow> expiring() {
        return List.of();
    }

    /** 每个节点持有的有效授权行数（{@code grant_rows_per_node}）。 */
    public List<GrantHealthMapper.NodeRowCount> rowsPerNode() {
        return healthMapper.selectRowsPerNode();
    }

    /**
     * 接口 51 的查询：<b>与巡检任务调同一个 {@link #scan()}</b>（F-83）。
     *
     * <p>只返回 {@code target_node_id} 落在<b>当前用户子树内</b>的行 ——
     * 不在子树内的<b>不返回</b>，不暴露存在性（§9.6 数据权限栏）。
     *
     * @param type 三个取值之一；{@code expiring} 恒回空清单（授权无有效期），不参与两类计数
     */
    public HealthView view(String type, Integer resourceType, Long myNodeId,
                           java.util.function.BiPredicate<Long, String> inMySubtree) {
        HealthScan scan = scan();
        List<GrantHealthMapper.HealthRow> dangling = filter(scan.dangling(), resourceType, inMySubtree);
        List<GrantHealthMapper.HealthRow> crossScope =
                filter(scan.crossScope(), resourceType, inMySubtree);

        List<GrantHealthMapper.HealthRow> rows = switch (type) {
            case "dangling" -> dangling;
            case "crossScope" -> crossScope;
            default -> filter(expiring(), resourceType, inMySubtree);
        };
        // summary 的两个数【永远按子树过滤后的全量算】，与 type 无关 ——
        // 页面 A2 的两张卡片是一起看的，按当前 tab 只回一个数会让另一张卡片空着
        return new HealthView(rows, dangling.size(), crossScope.size());
    }

    private static List<GrantHealthMapper.HealthRow> filter(
            List<GrantHealthMapper.HealthRow> rows, Integer resourceType,
            java.util.function.BiPredicate<Long, String> inMySubtree) {
        List<GrantHealthMapper.HealthRow> kept = new ArrayList<>();
        for (GrantHealthMapper.HealthRow row : rows) {
            if (resourceType != null && !resourceType.equals(row.getResourceType())) {
                continue;
            }
            if (!inMySubtree.test(row.getTargetNodeId(), row.getTargetAncestors())) {
                continue;
            }
            kept.add(row);
        }
        return kept;
    }

    /** 接口 51 的一次查询结果：当前 tab 的行 + <b>两个永不相加</b>的计数。 */
    public record HealthView(List<GrantHealthMapper.HealthRow> rows,
                             int danglingCount, int crossScopeCount) {
    }

    /**
     * 一次扫描的结果。<b>两个列表，从头到尾不合并</b> ——
     * 本记录<b>刻意不提供</b> {@code total()} 之类的方法：
     * 提供了就一定有人用，而那正是 F-20 那次的形态。
     */
    public record HealthScan(List<GrantHealthMapper.HealthRow> dangling,
                             List<GrantHealthMapper.HealthRow> crossScope) {
    }
}
