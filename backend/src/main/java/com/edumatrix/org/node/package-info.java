/**
 * 模块 06：组织树查询与节点移动事务（03-02 §3.1~§3.6，六个接口）。
 *
 * <p>本包是 <b>全系统唯一改变树结构的入口</b>（03-02 §3.4）。模块 07 的分配导师、
 * 转交管理员、教师调岗都是 {@code NodeMoveService} 的语义化封装，
 * <b>不得另写一套改父逻辑</b>（04-实施计划.md 模块 06 规则 1、模块 07 规则 5）。
 *
 * <h2>⚠ 模块 06 的临时构件清单（模块 07 已处理，剩余交模块 11）</h2>
 *
 * <p>本模块要读写三张<b>属于别的子域</b>的表。它们与
 * {@code system/user/entity/SystemOrgNode} 那张清单<b>不是一回事</b>，两张清单必须分开看：
 *
 * <table border="1">
 *   <caption>两张清单的区别</caption>
 *   <tr><th></th><th>{@code SystemOrgNode} 的清单（七个）</th><th>本清单（四个）</th></tr>
 *   <tr><td>是什么</td>
 *       <td>{@code system} 领域为了碰 {@code org} 的表而开的窄构件</td>
 *       <td>{@code org/node} 为了碰 {@code org} 其它<b>子域</b>的表而开的窄构件</td></tr>
 *   <tr><td>为什么当初必须这么开</td>
 *       <td>检查③禁止 {@code system} import {@code org}，而 {@code org} 那时还不存在</td>
 *       <td><b>检查③管不到</b>（{@code DOMAINS} 是顶层领域包，{@code node}/{@code member}/
 *           {@code grant} 是 {@code org} 内部子域）；纯粹是对方子域还没建</td></tr>
 *   <tr><td>谁来交接</td><td>模块 07（建人/删人接口建成时）</td>
 *       <td>模块 07（{@code org/member}）、模块 11（{@code org/grant}）</td></tr>
 * </table>
 *
 * <p><b>混在一张清单里会把两件事搅成一件</b> —— 模块 07 删完 {@code system} 那七个
 * 就会以为交接完毕。所以 {@code SystemOrgNode} 的清单末尾只留一行指到这里。
 *
 * <h3>清单（四个）—— 逐条三态：<b>已交接 / 未到期 / 经核对不交接</b></h3>
 * <ol>
 *   <li><b>【已交接，模块 07】</b>{@code mapper/NodeMemberMapper} —— {@code org_student} 的
 *       学籍状态与在读计数、{@code org_teacher.student_count} 的增减。
 *       模块 07 建成 {@code org/member} 的真实体后，三条方法分别迁入
 *       {@code org/member/mapper/OrgStudentMapper}（前两条）与
 *       {@code org/member/mapper/OrgTeacherMapper#addStudentCount}（第三条），
 *       <b>SQL 与整段注释逐字保留</b>，本类已删除。{@code NodeMoveService} 改注入那两个 Mapper；
 *   <li><b>【已交接，模块 11】</b>{@code mapper/NodeGrantScopeMapper} 的
 *       {@code selectSubtreeGrants} —— 算移动响应的 {@code outOfScopeGrants}。
 *       本模块当时只做了<b>字段与开关</b>，{@code revokeOutOfScopeGrants=true} 只记一条 WARN。
 *       模块 11 落地后：查询搬到 {@code org/grant/mapper/OutOfScopeGrantMapper}，
 *       判定补齐为契约 §2.5 规则 9 的完整判据（复用 {@code ResourceOwnerChecker.canRegrant}），
 *       级联回收接上 {@code GrantCascadeMapper#revokeSubtree}，
 *       {@code NodeMoveService} 里那段 WARN 与占位注释<b>已删除</b>。
 *       {@code NodeGrantScopeMapper} 只剩节点详情的 {@code grantedResourceStat} 一条，常驻。
 *       另见 03-02 §6.12（接口 52 归属变更影响面预检），同样落在模块 11；
 *   <li><b>【经核对不交接，常驻】</b>{@code mapper/NodeAccountMapper} —— {@code sys_user}
 *       的窄读写（§3.1/§3.2 的 {@code refUserName}/{@code refUserPhone}、§3.3 的姓名同步、
 *       §3.6 的重置密码）。<b>原登记的交接对象是「{@code system/user} 将来对外暴露的 Service」，
 *       模块 07 核对后判定这条不成立</b>，理由两条：
 *       <ul>
 *         <li><b>工单已授权</b>：04-实施计划.md 模块 07 的「涉及表」写栏逐字列着
 *             {@code sys_user}、{@code sys_user_role} —— {@code org} 领域读写 {@code sys_user}
 *             是模块 07 工单明确授权的（建人要插账号、绑角色），<b>不是越界</b>。
 *             当初「表在对方领域、对方 Service 还没有」这个成因，在模块 07 之后反而消失了：
 *             {@code org} 本来就要直连这张表；
 *         <li><b>反向 SPI 会形成双向 Bean 依赖</b>：把这 5 条推给 {@code system} 侧的 SPI，
 *             方向与 {@code system} 消费 {@code org} 的那条 SPI（{@code PlatformNodeWriter}
 *             退休后要建的 {@code OrgNodeGateway}）相反，两端都是 Bean，构造器循环风险是真的。
 *       </ul>
 *       <b>模块 07 的 {@code org/member} 因此不另开第二个 {@code sys_user} Mapper</b>，
 *       建人所需的账号写能力并入本类 —— 一张表在 {@code org} 领域内只有一个入口；
 *   <li><b>【常驻，非临时构件】</b>{@code service/NodeTypeRule} —— 承载规则的唯一判定。
 *       它<b>有一份同源的第二实现</b>：
 *       {@code system/user/service/PlatformNodeWriter#assertParentAcceptsChild}。
 *       检查③禁止 {@code system} import {@code org}，两份必须并存到
 *       {@code PlatformNodeWriter} 退休为止。<b>改任一份都要同时改另一份，且不会有任何东西报错。</b>
 *       <b>退休时机已由模块 07 重新定过，见下。</b>
 * </ol>
 *
 * <h2>⚠ {@code SystemOrgNode} 那张七个构件的清单：模块 07 <b>没有</b>处理，已重新定时机</h2>
 * <p>模块 07 核实出一件当初写清单时没考虑到的事：<b>检查③ 拦的是 import 语句本身，
 * 不区分 import 的是实体还是 Service</b>（{@code check_backend_conventions.sh} 第 56~65 行的
 * grep 是 {@code ^import com\.edumatrix\.($OTHERS)\.}）。所以那张清单里写的
 * 「{@code system/user} 改调对方 Service」<b>照字面做会当场触发检查③</b> ——
 * 只让对方 Service 返回自己的 DTO 解决不了这一点，{@code import} 那个 Service 类型本身就命中。
 *
 * <p><b>正解是既有先例</b>：{@code common/account/PasswordHasher} 与 {@code SessionRevoker}
 * ——接口声明在 {@code common/}、实现在提供方领域、消费方跨领域注入。
 * 交接方案因此是：新增 {@code common/orgnode/OrgNodeGateway}（SPI）+ {@code NodeBrief}（DTO），
 * 实现落 {@code org/node}，七个构件全部删除。
 *
 * <p><b>但模块 07 没有做它</b>，原因不是技术上不成立，而是<b>改动面与模块 06 的整改高度重叠</b>
 * （两边同时改 {@code NodeMoveService} / {@code OrgNodeMapper} / {@code NodeGrantScopeMapper} /
 * 本文件，合并时极易把注释合掉一半）。<b>新的到期时机：单独一轮，排在模块 06 整改合入之后。</b>
 * 在那之前 {@code NodeTypeRule} 的第二份实现原样并存。
 *
 * <h2>本包与 {@code common/subtree} 的分工</h2>
 * <p>「我能看到哪些数据」一律走 {@code common/subtree/SubtreeScopeHelper}（模块 01 产出，
 * <b>只调用不修改</b>，05-工程结构.md §D 模块 06 那一行）；本包只管<b>树本身的读写</b>。
 */
package com.edumatrix.org.node;
