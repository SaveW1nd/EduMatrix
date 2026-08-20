package com.edumatrix.org.grant.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.edumatrix.common.grant.ResourceGrantReader;
import com.edumatrix.common.resource.GrantableResourceItem;
import com.edumatrix.common.resource.GrantableResourceQuery;
import com.edumatrix.common.resource.GrantableResourceReader;
import com.edumatrix.common.resource.ResourceOwnerChecker;
import com.edumatrix.common.resource.ResourceType;
import com.edumatrix.common.response.PageResult;
import com.edumatrix.common.subtree.CurrentNodeProvider;
import com.edumatrix.common.subtree.NodeNameReader;
import com.edumatrix.common.subtree.SubtreeScopeHelper;
import com.edumatrix.common.account.UserNameReader;
import com.edumatrix.org.grant.dto.GrantHealthQueryReq;
import com.edumatrix.org.grant.dto.GrantableResourceQueryReq;
import com.edumatrix.org.grant.dto.NodeGrantedResourceQueryReq;
import com.edumatrix.org.grant.entity.OrgResourceGrant;
import com.edumatrix.org.grant.mapper.GrantSourceRefMapper;
import com.edumatrix.org.grant.mapper.OrgResourceGrantMapper;
import com.edumatrix.org.grant.vo.GrantHealthRowVO;
import com.edumatrix.org.grant.vo.GrantHealthSummaryVO;
import com.edumatrix.org.grant.vo.GrantableResourceVO;
import com.edumatrix.org.grant.vo.NodeGrantedResourceVO;

/**
 * 模块 11 的<b>读侧接口</b>：接口 37 我可授权的资源列表、接口 41 节点已获授权资源列表。
 *
 * <p>「读侧接口」不等于「读侧判定」：判定（能不能用 / 能不能再下发）一律走
 * {@code common/} 的两个构件，本类只负责把结果拼成响应。
 */
@Service
public class GrantQueryService {

    private static final Logger log = LoggerFactory.getLogger(GrantQueryService.class);

    /**
     * 接口 41 单次载入授权行的告警阈值。
     *
     * <p>契约 §7.1 把 {@code grant_rows_per_node} 的 P99 定在 <b>2000</b>，
     * 触发通常意味着「有人把整个题库一次性授给了某个节点」。这里取其 2.5 倍作为
     * <b>告警线而不是截断线</b> —— 截断会让页面少列几行而<b>不报错</b>，
     * 那是本项目 1 号失败模式；告警至少让人看见。
     */
    private static final int ROWS_WARN_THRESHOLD = 5000;

    private final GrantableResourceReader grantableReader;
    private final GrantHealthService healthService;
    private final org.springframework.data.redis.core.StringRedisTemplate redisTemplate;
    private final ResourceGrantReader grantReader;
    private final ResourceOwnerChecker ownerChecker;
    private final OrgResourceGrantMapper grantMapper;
    private final GrantSourceRefMapper sourceRefMapper;
    private final SubtreeScopeHelper subtreeScope;
    private final CurrentNodeProvider currentNodeProvider;
    private final NodeNameReader nodeNameReader;
    private final UserNameReader userNameReader;

    public GrantQueryService(GrantableResourceReader grantableReader,
                             GrantHealthService healthService,
                             org.springframework.data.redis.core.StringRedisTemplate redisTemplate,
                             ResourceGrantReader grantReader,
                             ResourceOwnerChecker ownerChecker,
                             OrgResourceGrantMapper grantMapper,
                             GrantSourceRefMapper sourceRefMapper,
                             SubtreeScopeHelper subtreeScope,
                             CurrentNodeProvider currentNodeProvider,
                             NodeNameReader nodeNameReader,
                             UserNameReader userNameReader) {
        this.grantableReader = grantableReader;
        this.healthService = healthService;
        this.redisTemplate = redisTemplate;
        this.grantReader = grantReader;
        this.ownerChecker = ownerChecker;
        this.grantMapper = grantMapper;
        this.sourceRefMapper = sourceRefMapper;
        this.subtreeScope = subtreeScope;
        this.currentNodeProvider = currentNodeProvider;
        this.nodeNameReader = nodeNameReader;
        this.userNameReader = userNameReader;
    }

    // =====================================================================
    // 接口 37 §9.1 我可授权的资源列表
    // =====================================================================

    /**
     * 我可授权的资源 = 自有 ∪ <b>可再下发</b>的受授权资源。
     *
     * <h2>⚠ 受授权那一半用 {@code canRegrant} 过滤，不是 {@code canUse}</h2>
     * <p>§9.1 的定位逐字：「本列表即接口 38 授权动作的<b>合法资源全集</b> ——
     * 列表之外的任何资源 ID 传给接口 38 一律返回 {@code 10301}」。
     * 用 {@code canUse} 会把跨管辖行（能用、不能再下发，契约 §2.5 规则 9）列进来，
     * 于是<b>列表里看得见、授出去报 10301</b> —— 用户照着界面操作，系统告诉他他错了。
     * 三种失败里这是最糟的一种：<b>界面在骗人</b>。
     *
     * <p>反过来也必须成立：这里滤掉的，接口 38 必须拒。两处用的是<b>同一个</b>
     * {@code regrantableIds}，不是两段各写一遍的判定。
     */
    public PageResult<GrantableResourceVO> grantableResources(GrantableResourceQueryReq req) {
        Long myNodeId = currentNodeProvider.requireCurrentNodeId();
        ResourceType type = ResourceType.of(req.getResourceType()).orElseThrow();

        // 我持有的（canUse 口径）→ 再滤掉跨管辖的（canRegrant 口径）
        List<Long> held = grantReader.grantedResourceIds(type, myNodeId);
        Set<Long> regrantable = ownerChecker.regrantableIds(type, held, myNodeId);

        GrantableResourceQuery query = new GrantableResourceQuery();
        query.setMyNodeId(myNodeId);
        query.setRegrantableIds(new ArrayList<>(regrantable));
        query.setSource(req.getSource());
        query.setKeyword(req.getKeyword());
        query.setSubject(req.getSubject());
        query.setCategoryId(req.getCategoryId());
        query.setPageNum(PageResult.normalizePageNum(req.getPageNum()));
        query.setPageSize(PageResult.normalizePageSize(req.getPageSize()));

        PageResult<GrantableResourceItem> page = grantableReader.page(type, query);
        List<GrantableResourceItem> rows = page.getList();

        Map<Long, String> nodeNames = nodeNameReader.nodeNames(
                rows.stream().map(GrantableResourceItem::getOwnerNodeId).filter(java.util.Objects::nonNull)
                        .distinct().toList());
        List<GrantableResourceVO> list = new ArrayList<>(rows.size());
        for (GrantableResourceItem item : rows) {
            GrantableResourceVO vo = new GrantableResourceVO();
            vo.setResourceType(type.code());
            vo.setResourceId(item.getResourceId());
            vo.setResourceName(item.getResourceName());
            vo.setOwnerNodeId(item.getOwnerNodeId());
            vo.setOwnerNodeName(nodeNames.get(item.getOwnerNodeId()));
            vo.setSource(item.getSource());
            vo.setExtra(item.getExtra());
            // §9.1 的 validStart/validEnd 【恒为 null】：需方 2026-08-21 定案
            // 「授权一律永久有效」—— 字段保留（响应结构不变），但没有任何一条路径给它赋值。
            // 这不是缺陷，文档已写明
            list.add(vo);
        }
        return PageResult.of(page.getTotal(), list);
    }

    // =====================================================================
    // 接口 41 §9.5 节点已获授权资源列表
    // =====================================================================

    /**
     * 某节点<b>已被显式授权</b>的资源。<b>不回溯祖先链</b>（契约 §2.5 规则 4）。
     *
     * <h2>越界返回 404</h2>
     * <p>{@code {id}} 是<b>路径上的操作对象</b>，按契约 §2.4 三分法返回 404、不暴露存在性。
     * 学生的子树就是他自己，所以「学生只能查自己」由同一个判定覆盖，<b>不需要第二条分支</b>。
     *
     * <h2>为什么在内存里筛选与分页</h2>
     * <p>{@code keyword} 匹配的是<b>资源名</b>，而资源名在 {@code crs_course} /
     * {@code qb_question} / {@code vod_video} 三张表里 —— {@code org} 域读不到（检查③），
     * 必须先把行取出来、批量解析名称，才谈得上按名字筛。
     *
     * <p>于是只有两条路可选：<b>(a)</b> 无 keyword 时走 SQL 分页、有 keyword 时走内存；
     * <b>(b)</b> 一律走内存。取 (b)。(a) 的代价是<b>两条路径的排序必须逐字一致</b>，
     * 而一旦分叉，表现是「加了关键词之后翻页结果对不上」—— 接口 200、字段齐全、结果错。
     * 低频管理页不值得为此冒险。
     *
     * <p>规模有上界且被指标盯着：契约 §7.1 的 {@code grant_rows_per_node} P99 = 2000。
     * 超过 {@value #ROWS_WARN_THRESHOLD} 行记 WARN —— <b>告警而不截断</b>，
     * 截断会让页面静默少列几行。
     */
    public PageResult<NodeGrantedResourceVO> nodeGrantedResources(Long nodeId,
                                                                  NodeGrantedResourceQueryReq req) {
        // 【必须传 myNodeId，不能用无参重载】SubtreeScopeHelper 的无参重载从【注入的】
        // CurrentContextProvider 取「我是谁」，而集成测试底座把那个 provider 设成
        // asNoSession() 状态的测试 provider —— 于是 myNodeId 为 null、isInSubtree 直接返回 false，
        // 表现是【所有请求一律 404】，连一条 selectPath 都不会发。
        // 生产里注入的是 Sa-Token 那个实现，同样一行代码【是好的】—— 也就是说
        // 这个坑只在测试里现形，方向与「以为存在、实际从未生效」正好相反，但同样不报错。
        // src/main 里其余 13 个调用点全部显式传 myNodeId，本处照做，不做第 14 种写法。
        subtreeScope.assertInSubtree(currentNodeProvider.requireCurrentNodeId(), nodeId);

        LambdaQueryWrapper<OrgResourceGrant> wrapper = new LambdaQueryWrapper<OrgResourceGrant>()
                .eq(OrgResourceGrant::getTargetNodeId, nodeId);
        if (req.getResourceType() != null) {
            wrapper.eq(OrgResourceGrant::getResourceType, req.getResourceType());
        }
        List<OrgResourceGrant> rows = grantMapper.selectList(wrapper);
        if (rows.size() > ROWS_WARN_THRESHOLD) {
            log.warn("节点 {} 持有 {} 条授权行，已超过 grant_rows_per_node 的 P99 告警线（契约 §7.1）。"
                            + "本次【未截断】，但通常意味着有人把整个题库一次性授给了这个节点",
                    nodeId, rows.size());
        }

        // 【授权没有有效期，于是这里不再过滤】——需方 2026-08-21 定案。
        // 原先的 includeExpired 参数已删除（F-107）：它恒无差别，勾上与不勾逐字节相同，
        // 与「调了没反应」的空壳接口是同一个形状
        List<OrgResourceGrant> visible = rows;

        Map<String, String> resourceNames = resourceNamesOf(visible);
        Map<Long, String> nodeNames = nodeNameReader.nodeNames(List.of(nodeId));
        Map<Long, String> userNames = userNameReader.realNames(visible.stream()
                .map(OrgResourceGrant::getGrantBy).filter(java.util.Objects::nonNull)
                .distinct().toList());

        String keyword = req.getKeyword() == null ? null : req.getKeyword().trim();
        List<NodeGrantedResourceVO> all = new ArrayList<>(visible.size());
        for (OrgResourceGrant row : visible) {
            String resourceName = resourceNames.get(nameKey(row));
            if (keyword != null && !keyword.isEmpty()
                    && (resourceName == null || !resourceName.contains(keyword))) {
                continue;
            }
            NodeGrantedResourceVO vo = new NodeGrantedResourceVO();
            vo.setId(row.getId());
            vo.setResourceType(row.getResourceType());
            vo.setResourceId(row.getResourceId());
            vo.setResourceName(resourceName);
            vo.setTargetNodeId(row.getTargetNodeId());
            vo.setTargetNodeName(nodeNames.get(row.getTargetNodeId()));
            // validStart / validEnd 【恒为 null】—— 授权一律永久有效（需方 2026-08-21 定案）。
            // 那两个是【事实】字段（「没有到期日」），字段保留、无人赋值；
            // 而 expired 是对那个事实的【判断】，判断的对象没了，字段已删（F-107）
            vo.setGrantSource(row.getGrantSource());
            vo.setGrantSourceName(OrgResourceGrant.sourceName(row.getGrantSource()));
            vo.setSourceRefId(row.getSourceRefId());
            vo.setSourceRefName(sourceRefName(row));
            vo.setGrantBy(row.getGrantBy());
            vo.setGrantByName(userNames.get(row.getGrantBy()));
            vo.setGrantTime(row.getGrantTime());
            all.add(vo);
        }

        all.sort(Comparator.comparing(NodeGrantedResourceVO::getGrantTime,
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(NodeGrantedResourceVO::getId, Comparator.reverseOrder()));

        int pageNum = PageResult.normalizePageNum(req.getPageNum());
        int pageSize = PageResult.normalizePageSize(req.getPageSize());
        int from = Math.min((pageNum - 1) * pageSize, all.size());
        int to = Math.min(from + pageSize, all.size());
        return PageResult.of(all.size(), List.copyOf(all.subList(from, to)));
    }

    /** 按资源类型分组批量解析资源名 —— 三类各一次查询，不逐行点查。 */
    private Map<String, String> resourceNamesOf(List<OrgResourceGrant> rows) {
        Map<ResourceType, Set<Long>> byType = new EnumMap<>(ResourceType.class);
        for (OrgResourceGrant row : rows) {
            ResourceType.of(row.getResourceType()).ifPresent(type ->
                    byType.computeIfAbsent(type, k -> new LinkedHashSet<>()).add(row.getResourceId()));
        }
        Map<String, String> names = new LinkedHashMap<>();
        byType.forEach((type, ids) ->
                grantableReader.namesOf(type, ids).forEach((resourceId, name) ->
                        names.put(nameKey(type.code(), resourceId), name)));
        return names;
    }

    /**
     * 资源名的 Map 键：{@code resourceType} 与 {@code resourceId} 拼成一个<b>无歧义</b>的串。
     *
     * <p>只用 {@code resourceId} 当键是不行的：三类资源的 ID 来自同一个雪花发号器，
     * 理论上不撞，但<b>「理论上不撞」不是可以依赖的前提</b> ——
     * 一旦撞上，表现是某条授权显示成了<b>另一类资源的名字</b>，而接口返回 200。
     *
     * <p><b>用字符串拼接而不是把两个数算成一个数</b>：任何
     * {@code type * a + id * b} 形式的合成都是<b>哈希</b>，不同的 {@code (type, id)}
     * 可以映射到同一个值 —— 那样为了防撞键引入的东西自己会撞键，且同样不报错。
     */
    private static String nameKey(OrgResourceGrant row) {
        return nameKey(row.getResourceType(), row.getResourceId());
    }

    private static String nameKey(Integer resourceType, Long resourceId) {
        return resourceType + ":" + resourceId;
    }

    /** {@code sourceRefName}：按 {@code grant_source} 分派到节点 / 标签 / 模板。 */
    private String sourceRefName(OrgResourceGrant row) {
        Long refId = row.getSourceRefId();
        Integer source = row.getGrantSource();
        if (refId == null || source == null) {
            return null;
        }
        return switch (source) {
            case OrgResourceGrant.SOURCE_NODE, OrgResourceGrant.SOURCE_ALL_STUDENTS ->
                    nodeNameReader.nodeNames(List.of(refId)).get(refId);
            case OrgResourceGrant.SOURCE_TAG -> sourceRefMapper.selectTagName(refId);
            case OrgResourceGrant.SOURCE_TEMPLATE -> sourceRefMapper.selectTemplateName(refId);
            default -> null;
        };
    }

    // =====================================================================
    // 接口 51 §9.6 授权健康度巡检结果查询
    // =====================================================================

    /**
     * <b>只读，一个授权行都不改</b>（§9.6 说明段）。
     *
     * <p>两个修复动作<b>复用既有接口</b>：一键回收走接口 39（级联逻辑与手动撤销
     * <b>完全相同</b>，没有第二套语义）、补授上级走接口 38。不为巡检另开写接口。
     *
     * <p>{@code danglingCount} 与 {@code crossScopeCount} <b>分开返回</b>，
     * 且 {@code summary} 与当前 {@code type} 无关 —— 页面 A2 的两张卡片是一起看的。
     */
    public PageResult<GrantHealthRowVO> health(GrantHealthQueryReq req) {
        Long myNodeId = currentNodeProvider.requireCurrentNodeId();
        GrantHealthService.HealthView view = healthService.view(
                req.getType(), req.getResourceType(), myNodeId,
                (targetNodeId, ancestors) -> myNodeId.equals(targetNodeId)
                        || com.edumatrix.common.subtree.NodePath.parseAncestorIds(ancestors)
                        .contains(myNodeId));

        List<com.edumatrix.org.grant.mapper.GrantHealthMapper.HealthRow> all = view.rows();
        int pageNum = PageResult.normalizePageNum(req.getPageNum());
        int pageSize = PageResult.normalizePageSize(req.getPageSize());
        int from = Math.min((pageNum - 1) * pageSize, all.size());
        int to = Math.min(from + pageSize, all.size());
        List<com.edumatrix.org.grant.mapper.GrantHealthMapper.HealthRow> page =
                all.subList(from, to);

        Map<String, String> resourceNames = resourceNamesOfHealth(page);
        Map<Long, String> nodeNames = nodeNameReader.nodeNames(page.stream()
                .map(com.edumatrix.org.grant.mapper.GrantHealthMapper.HealthRow::getParentNodeId)
                .filter(java.util.Objects::nonNull).distinct().toList());
        LocalDateTime detectedTime = lastRunOf();

        List<GrantHealthRowVO> list = new ArrayList<>(page.size());
        for (var row : page) {
            GrantHealthRowVO vo = new GrantHealthRowVO();
            vo.setResourceType(row.getResourceType());
            vo.setResourceId(row.getResourceId());
            vo.setResourceName(resourceNames.get(nameKey(row.getResourceType(), row.getResourceId())));
            vo.setTargetNodeId(row.getTargetNodeId());
            vo.setTargetNodeName(row.getTargetNodeName());
            // 【原先这里按 type 分叉：expiring 时 missingNodeId 置 null（§9.6 字段说明）】
            // 授权取消有效期后 expiring 恒为空清单，本循环在 type=expiring 时【一次都不进】——
            // 那个三元表达式的两个分支【区分不出来】，留着就是一段无人能证伪的代码。删掉
            vo.setMissingNodeId(row.getParentNodeId());
            vo.setMissingNodeName(nodeNames.get(row.getParentNodeId()));
            // validEnd 恒为 null（授权无有效期），字段保留
            vo.setDetectedTime(detectedTime);
            list.add(vo);
        }
        return PageResult.of(all.size(), list,
                new GrantHealthSummaryVO(view.danglingCount(), view.crossScopeCount()));
    }

    /** 该租户最近一轮巡检的完成时刻；从未巡检过时 {@code null}（见 {@code GrantHealthRowVO}）。 */
    private LocalDateTime lastRunOf() {
        Long tenantId = com.edumatrix.common.tenant.TenantHelper.getTenantIdOrNull();
        if (tenantId == null) {
            return null;
        }
        String value = redisTemplate.opsForValue()
                .get(com.edumatrix.common.redis.RedisKeys.grantHealthLastRun(tenantId));
        return value == null ? null
                : LocalDateTime.parse(value,
                        java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    private Map<String, String> resourceNamesOfHealth(
            List<com.edumatrix.org.grant.mapper.GrantHealthMapper.HealthRow> rows) {
        Map<ResourceType, Set<Long>> byType = new EnumMap<>(ResourceType.class);
        rows.forEach(row -> ResourceType.of(row.getResourceType()).ifPresent(type ->
                byType.computeIfAbsent(type, k -> new LinkedHashSet<>()).add(row.getResourceId())));
        Map<String, String> names = new LinkedHashMap<>();
        byType.forEach((type, ids) -> grantableReader.namesOf(type, ids)
                .forEach((id, name) -> names.put(nameKey(type.code(), id), name)));
        return names;
    }
}
