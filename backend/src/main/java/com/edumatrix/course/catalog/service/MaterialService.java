package com.edumatrix.course.catalog.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.edumatrix.common.account.UserNameReader;
import com.edumatrix.common.errorcode.ErrorCode;
import com.edumatrix.common.file.FileMeta;
import com.edumatrix.common.file.FileMetaReader;
import com.edumatrix.common.response.BizException;
import com.edumatrix.common.response.PageResult;
import com.edumatrix.common.subtree.SubtreeScopeHelper;
import com.edumatrix.common.xss.HtmlSanitizer;
import com.edumatrix.course.catalog.dto.MaterialCreateReq;
import com.edumatrix.course.catalog.dto.MaterialPageQuery;
import com.edumatrix.course.catalog.dto.MaterialUpdateReq;
import com.edumatrix.course.catalog.entity.CrsLesson;
import com.edumatrix.course.catalog.entity.CrsMaterial;
import com.edumatrix.course.catalog.mapper.CrsLessonMapper;
import com.edumatrix.course.catalog.mapper.CrsMaterialMapper;
import com.edumatrix.course.catalog.vo.AttachmentVO;
import com.edumatrix.course.catalog.vo.CreatedIdVO;
import com.edumatrix.course.catalog.vo.MaterialDetailVO;
import com.edumatrix.course.catalog.vo.MaterialListVO;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 图文资料管理（03-03 §4.1~§4.5，接口 17~21）。
 *
 * <h2>XSS 过滤发生在写入时</h2>
 * <p>PRD F2-2 验收标准第 2 条逐字：「When 保存图文资料，Then 服务端
 * <b>过滤脚本标签后落库</b>」。<b>读取路径不做第二次过滤</b> ——
 * 完整论证见 {@link HtmlSanitizer} 类注释。
 *
 * <h2>可见性按 {@code owner_node_id} 子树过滤（D 定案，<b>明知地推翻分册</b>）</h2>
 * <p>03-03 §4.1 / §4.2 / §4.4 / §4.5 四处权限栏写的是「过滤 {@code create_by} 所在节点」，
 * 而 DDL 与 02-数据库设计 §4.2.4 的列注释写的是「管理端可见性按本列
 * （{@code owner_node_id}）做子树过滤，此后不随创建人调岗漂移」。
 * 契约在这件事上<b>沉默</b> —— §4「资源归属唯一化」与 §2.5「受管资源」逐字只点名
 * {@code crs_course} / {@code qb_question} / {@code vod_video} 三张，不含 {@code crs_material}，
 * 所以构不成上位依据，按权威顺序本该取分册。需方裁决取 {@code owner_node_id}，
 * 已登记 <b>F-45</b>，本轮已订正四处权限栏。
 *
 * <h2>{@code attachments[]} 没有 {@code fileUrl}</h2>
 * <p>D-2 定案：{@code material_attach} 不在内联档，取文件一律走 03-01 §7.3。
 * {@link AttachmentVO} 在类型上就没有 URL 字段。
 * ⚠ 模块 11 之前，那条下载路径按 B-3 / F-38 的 fail-closed <b>一律 404</b>，
 * 这是设计行为不是本模块的 bug。
 */
@Service
public class MaterialService {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<Long>> ID_LIST = new TypeReference<>() { };

    private final CrsMaterialMapper materialMapper;
    private final CrsLessonMapper lessonMapper;
    private final CourseAccessGuard guard;
    private final SubtreeScopeHelper subtreeScopeHelper;
    private final HtmlSanitizer htmlSanitizer;
    private final MaterialContentRewriter contentRewriter;
    private final FileMetaReader fileMetaReader;
    private final UserNameReader userNameReader;

    public MaterialService(CrsMaterialMapper materialMapper,
                           CrsLessonMapper lessonMapper,
                           CourseAccessGuard guard,
                           SubtreeScopeHelper subtreeScopeHelper,
                           HtmlSanitizer htmlSanitizer,
                           MaterialContentRewriter contentRewriter,
                           FileMetaReader fileMetaReader,
                           UserNameReader userNameReader) {
        this.materialMapper = materialMapper;
        this.lessonMapper = lessonMapper;
        this.guard = guard;
        this.subtreeScopeHelper = subtreeScopeHelper;
        this.htmlSanitizer = htmlSanitizer;
        this.contentRewriter = contentRewriter;
        this.fileMetaReader = fileMetaReader;
        this.userNameReader = userNameReader;
    }

    // =====================================================================
    // 接口 17 §4.1 图文资料分页列表
    // =====================================================================

    public PageResult<MaterialListVO> page(MaterialPageQuery query) {
        List<Long> scope = subtreeScopeHelper.subtreeNodeIds(guard.myNodeId());
        if (scope.isEmpty()) {
            // 契约 §7.1：数据权限过滤条件为空集时绝不退化为「不加过滤」
            return PageResult.empty();
        }
        LambdaQueryWrapper<CrsMaterial> wrapper = new LambdaQueryWrapper<CrsMaterial>()
                .in(CrsMaterial::getOwnerNodeId, scope);
        if (query.getTitle() != null && !query.getTitle().isBlank()) {
            wrapper.like(CrsMaterial::getTitle, query.getTitle().trim());
        }
        wrapper.orderByDesc(CrsMaterial::getCreateTime).orderByDesc(CrsMaterial::getId);

        IPage<CrsMaterial> page = materialMapper.selectPage(
                new Page<>(PageResult.normalizePageNum(query.getPageNum()),
                        PageResult.normalizePageSize(query.getPageSize())), wrapper);

        List<CrsMaterial> rows = page.getRecords();
        Map<Long, Integer> refCounts = refLessonCounts(rows.stream().map(CrsMaterial::getId).toList());
        Map<Long, String> creatorNames = userNameReader.realNames(
                rows.stream().map(CrsMaterial::getCreateBy)
                        .filter(java.util.Objects::nonNull).distinct().toList());

        List<MaterialListVO> list = new ArrayList<>(rows.size());
        for (CrsMaterial material : rows) {
            MaterialListVO vo = new MaterialListVO();
            vo.setId(material.getId());
            vo.setTitle(material.getTitle());
            vo.setAttachmentCount(parseAttachmentIds(material.getAttachmentFileIds()).size());
            vo.setRefLessonCount(refCounts.getOrDefault(material.getId(), 0));
            vo.setCreateBy(material.getCreateBy());
            vo.setCreateByName(creatorNames.get(material.getCreateBy()));
            vo.setCreateTime(material.getCreateTime());
            vo.setUpdateTime(material.getUpdateTime());
            list.add(vo);
        }
        return PageResult.of(page.getTotal(), list);
    }

    // =====================================================================
    // 接口 18 §4.2 图文资料详情
    // =====================================================================

    public MaterialDetailVO detail(Long materialId) {
        CrsMaterial material = loadMaterialByPath(materialId);
        MaterialDetailVO vo = new MaterialDetailVO();
        vo.setId(material.getId());
        vo.setTitle(material.getTitle());
        // D-3：出参时把 fileId 占位重写为 ≤30 分钟签名地址
        vo.setContent(contentRewriter.toResponse(material.getContent()));
        vo.setAttachments(attachments(material));
        vo.setCreateBy(material.getCreateBy());
        vo.setCreateTime(material.getCreateTime());
        vo.setUpdateBy(material.getUpdateBy());
        vo.setUpdateTime(material.getUpdateTime());
        return vo;
    }

    // =====================================================================
    // 接口 19 §4.3 创建图文资料
    // =====================================================================

    @Transactional(rollbackFor = Exception.class)
    public CreatedIdVO create(MaterialCreateReq req) {
        CrsMaterial material = new CrsMaterial();
        material.setOwnerNodeId(guard.myNodeId());
        material.setTitle(req.getTitle().trim());
        material.setContent(htmlSanitizer.sanitize(req.getContent()));
        material.setAttachmentFileIds(writeAttachmentIds(req.getAttachmentFileIds()));
        materialMapper.insert(material);

        CreatedIdVO vo = new CreatedIdVO();
        vo.setId(material.getId());
        return vo;
    }

    // =====================================================================
    // 接口 20 §4.4 修改图文资料
    // =====================================================================

    /**
     * <b>用 {@code set(...)} 逐列覆盖，不用 {@code updateById}</b>：后者跳过 {@code null} 字段，
     * 于是「把附件清空」会静默失效 —— 接口 200、附件还在，正是 1 号失败模式。
     * §4.4 参数表逐字写着附件「<b>全量覆盖</b>」。
     * {@code MaterialIT#updateOverwritesAttachmentsAndSanitizes} 钉住这条。
     */
    @Transactional(rollbackFor = Exception.class)
    public void update(Long materialId, MaterialUpdateReq req) {
        CrsMaterial material = loadMaterialByPath(materialId);
        materialMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update
                .LambdaUpdateWrapper<CrsMaterial>()
                .eq(CrsMaterial::getId, material.getId())
                .set(CrsMaterial::getTitle, req.getTitle().trim())
                .set(CrsMaterial::getContent, htmlSanitizer.sanitize(req.getContent()))
                .set(CrsMaterial::getAttachmentFileIds, writeAttachmentIds(req.getAttachmentFileIds())));
    }

    // =====================================================================
    // 接口 21 §4.5 删除图文资料
    // =====================================================================

    /** 存在未删除课时引用（{@code crs_lesson.content_id}）时拒绝 → {@code 20010}（§4.5）。 */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long materialId) {
        CrsMaterial material = loadMaterialByPath(materialId);
        Long refs = lessonMapper.selectCount(new LambdaQueryWrapper<CrsLesson>()
                .eq(CrsLesson::getContentId, material.getId()));
        if (refs != null && refs > 0) {
            throw new BizException(ErrorCode.MATERIAL_IN_USE);
        }
        materialMapper.deleteById(material.getId());
    }

    // =====================================================================
    // 内部
    // =====================================================================

    /**
     * <b>路径上的资料</b>（{@code /materials/{id}}，§4.2 / §4.4 / §4.5）：
     * 不存在 / 已删除 / 跨租户 <b>与</b>「存在但 {@code owner_node_id} 不在我的子树内」
     * 一律 <b>404</b>。
     *
     * <h2>F-42 定案：两者必须给出同一个结果</h2>
     * <p>原先前者抛 {@code 20009}、后者 404，合起来能被拿来<b>探测存在性</b>。
     * 统一到 404 而不是统一到业务码：契约 §2.4 三分法第 1 行是上位文档。
     *
     * <p><b>{@code 20009} 没有退役</b>：它仍然是「创建/修改<b>课时</b>时 {@code materialId}
     * 指向的资料不可用」的码（§3.3 规则 3），那里的 {@code materialId} 来自<b>请求体</b>，
     * 见 {@code LessonService#loadMaterialByParam}。方法名里的 {@code ByPath} 用来把两类分开。
     */
    private CrsMaterial loadMaterialByPath(Long materialId) {
        CrsMaterial material = materialId == null ? null : materialMapper.selectById(materialId);
        if (material == null) {
            throw BizException.notFound(materialId);
        }
        if (!subtreeScopeHelper.isInSubtree(guard.myNodeId(), material.getOwnerNodeId())) {
            throw BizException.notFound(materialId);
        }
        return material;
    }

    private List<AttachmentVO> attachments(CrsMaterial material) {
        List<Long> ids = parseAttachmentIds(material.getAttachmentFileIds());
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<Long, FileMeta> metas = new LinkedHashMap<>();
        for (FileMeta meta : fileMetaReader.metaOf(ids)) {
            metas.put(meta.fileId(), meta);
        }
        List<AttachmentVO> list = new ArrayList<>(ids.size());
        for (Long id : ids) {
            FileMeta meta = metas.get(id);
            if (meta == null) {
                // 附件行已被清理（如 7 天保留期）：仍返回 fileId，让前端能显示「已失效」，
                // 而不是静默少一条 —— 静默少一条没人会发现
                AttachmentVO vo = new AttachmentVO();
                vo.setFileId(id);
                list.add(vo);
                continue;
            }
            AttachmentVO vo = new AttachmentVO();
            vo.setFileId(meta.fileId());
            vo.setFileName(meta.fileName());
            vo.setFileSize(meta.fileSize());
            list.add(vo);
        }
        return list;
    }

    private Map<Long, Integer> refLessonCounts(List<Long> materialIds) {
        Map<Long, Integer> counts = new LinkedHashMap<>();
        if (materialIds.isEmpty()) {
            return counts;
        }
        for (CrsLesson lesson : lessonMapper.selectList(new LambdaQueryWrapper<CrsLesson>()
                .select(CrsLesson::getContentId)
                .in(CrsLesson::getContentId, materialIds))) {
            counts.merge(lesson.getContentId(), 1, Integer::sum);
        }
        return counts;
    }

    static List<Long> parseAttachmentIds(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyList();
        }
        try {
            List<Long> ids = JSON.readValue(json, ID_LIST);
            return ids == null ? Collections.emptyList() : ids;
        } catch (Exception e) {
            // 列里是脏 JSON：不让整条详情接口挂掉，但要留痕
            return Collections.emptyList();
        }
    }

    private static String writeAttachmentIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return null;
        }
        try {
            return JSON.writeValueAsString(ids);
        } catch (Exception e) {
            throw new BizException(ErrorCode.BAD_REQUEST, "附件列表格式非法");
        }
    }
}
