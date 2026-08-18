/**
 * 模块 07：人员与学籍 + K12 合规（03-02 §4~§6，20 个接口）。
 *
 * <p>本包<b>第一次让系统有了真实的教师与学生</b>——在它之前，全库只有超管与机构最高管理员
 * （03-01 §5.3 开通租户时建的那一个）。
 *
 * <h2>三条边界，物理上分在三个 Service 里</h2>
 * <table border="1">
 *   <caption>为什么不是一个 {@code OrgStudentService} 全包</caption>
 *   <tr><th>Service</th><th>接口</th><th>它<b>做不到</b>什么</th></tr>
 *   <tr><td>{@code OrgStudentService}</td><td>16 / 17 / 18 / 19</td>
 *       <td>改父、改学籍状态</td></tr>
 *   <tr><td>{@code StudentAssignService}</td><td>20 / 21 / 22</td>
 *       <td>改学籍状态；<b>连改父也不自己做</b>，一律调 {@code NodeMoveService}</td></tr>
 *   <tr><td>{@code StudentLifecycleService}</td><td>23 / 24 / 25</td>
 *       <td>改父（除恢复时可选的重新挂载，那一步同样调 {@code NodeMoveService}）</td></tr>
 * </table>
 * <p>03-02 §6.3 逐字：「归属变更<b>不走本接口</b>，必须使用接口 20/21/22；
 * 学籍状态变更走接口 23/24/25」。拆成三个类是为了让这句话<b>由类型系统保证</b>，
 * 而不是靠下一个人记得。
 *
 * <h2>改父一律走模块 06 的 {@code NodeMoveService}（规则 5）</h2>
 * <p>分配导师 / 批量分配 / 转交管理员 / 教师调岗<b>全部</b>是它的语义化封装。
 * 本包<b>没有一行改 {@code parent_id} 的代码</b>。
 * {@code changeType} 也不由本包决定——{@code NodeMoveService#inferChangeType}
 * 按 {@code (movingType, targetType)} 推断。
 *
 * <p><b>事务边界</b>：本包的方法包着 {@code NodeMoveService#move} 的事务
 * （{@code REQUIRED} 加入外层）。它的缓存清除用 {@code afterCommit} 注册，
 * 因此在<b>本包</b>的事务提交后才触发——这正是它类注释里预告的「被模块 07 的更外层事务包着」
 * 的情形。<b>本包因此不得提前提交、也不得把批量拆成多个事务。</b>
 *
 * <h2>本包持有的两条跨子域引用</h2>
 * <ul>
 *   <li>{@code org/node} 的 {@code OrgNodeMapper} / {@code NodeTypeRule} /
 *       {@code NodeChangeLogWriter} / {@code CurrentNodeResolver} / {@code NodeMoveService} /
 *       {@code NodeAccountMapper} —— 同一顶层领域内的子域引用，<b>检查③ 管不到</b>
 *       （{@code DOMAINS} 是顶层领域包）；
 *   <li>{@code common/} 的 {@code SubtreeScopeHelper} / {@code PasswordHasher} /
 *       {@code SessionRevoker} / {@code TenantHelper} / {@code IdWorker} ——
 *       <b>只调用不修改</b>（05-工程结构.md §D）。
 * </ul>
 *
 * <h2>本包<b>不做</b>的三件事（都在别的模块的工单里）</h2>
 * <ol>
 *   <li><b>套用权限模板</b>（{@code templateId}）——模块 11 的授权引擎 + 模块 17 的模板接口。
 *       传了会留一条 WARN，<b>不静默</b>（{@code MemberWriteSupport#warnTemplateNotApplied}）；
 *   <li><b>标签筛选</b>（{@code tagIds} / {@code tagMatchMode}）——模块 17。
 *       DTO 里干脆<b>不给字段</b>，前端传了会收到 400 而不是一个「筛选没生效」的空列表；
 *   <li><b>Excel 导入</b>（接口 27/28/29）——模块 17，但它「走模块 07 的建人事务，不另写一套」
 *       （04-实施计划.md 模块 17 那一行），入口就是 {@code MemberWriteSupport#createPerson}。
 * </ol>
 *
 * <h2>合规三件套在本包的落点</h2>
 * <ul>
 *   <li><b>F7-1 监护人同意留痕</b>：{@code StudentCreateReq#guardianConsent} 上的
 *       {@code @AssertTrue}（参数校验阶段 400，请求进不到 Service）+
 *       {@code MemberOperLogWriter#guardianConsent}（与建档同事务）；
 *   <li><b>F7-2 水印脱敏</b>：<b>不在本包</b>，在模块 12/14 的播放链路；
 *   <li><b>F7-3 删除请求</b>：{@code StudentLifecycleService#archive} 写下
 *       {@code archive_reason}，{@code AnonymizeArchivedStudentJob} +
 *       {@code StudentAnonymizeService} 在 30 日后执行不可逆脱敏。
 *       <b>覆写掩码，绝不置 NULL</b>（契约 §2.2 同源原则表第 2 行）。
 * </ul>
 */
package com.edumatrix.org.member;
