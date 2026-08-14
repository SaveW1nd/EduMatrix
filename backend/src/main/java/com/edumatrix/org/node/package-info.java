/**
 * 模块 06：组织树查询与节点移动事务（03-02 §3.1~§3.6，六个接口）。
 *
 * <p>本包是 <b>全系统唯一改变树结构的入口</b>（03-02 §3.4）。模块 07 的分配导师、
 * 转交管理员、教师调岗都是 {@code NodeMoveService} 的语义化封装，
 * <b>不得另写一套改父逻辑</b>（04-实施计划.md 模块 06 规则 1、模块 07 规则 5）。
 *
 * <h2>⚠ 模块 06 的临时构件清单（模块 07 / 11 请照此交接）</h2>
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
 * <h3>清单（四个）</h3>
 * <ol>
 *   <li>{@code mapper/NodeMemberMapper} —— {@code org_student} 的学籍状态与在读计数、
 *       {@code org_teacher.student_count} 的增减。<b>交给模块 07 的 {@code org/member}</b>；
 *   <li>{@code mapper/NodeGrantScopeMapper} —— {@code org_resource_grant} 的窄只读，
 *       算移动响应的 {@code outOfScopeGrants}。<b>交给模块 11 的 {@code org/grant}</b>，
 *       届时连同 {@code revokeOutOfScopeGrants=true} 的级联回收动作一起实现
 *       （本模块只做字段与开关，契约 §2.5 规则 9、04-实施计划.md 模块 06 规则 8）；
 *   <li>{@code mapper/NodeAccountMapper} —— {@code sys_user} 的窄读写（§3.1/§3.2 的
 *       {@code refUserName}/{@code refUserPhone}、§3.3 的姓名同步、§3.6 的重置密码）。
 *       <b>这一条在工单的「涉及表」里已明确授权</b>（模块 06 写 {@code sys_user}（重置密码）），
 *       不是越界；但它跨的是 {@code system} 领域的表，交接对象是
 *       {@code system/user} 将来对外暴露的 Service，形态与 {@code StudentQuotaMapper} 互为镜像；
 *   <li>{@code service/NodeTypeRule} —— <b>不是临时构件，但有一份同源的第二实现</b>：
 *       {@code system/user/service/PlatformNodeWriter#assertParentAcceptsChild}。
 *       检查③禁止 {@code system} import {@code org}，在模块 07 把 {@code PlatformNodeWriter}
 *       退休之前，两份必须并存。<b>改任一份都要同时改另一份，且不会有任何东西报错。</b>
 * </ol>
 *
 * <h2>本包与 {@code common/subtree} 的分工</h2>
 * <p>「我能看到哪些数据」一律走 {@code common/subtree/SubtreeScopeHelper}（模块 01 产出，
 * <b>只调用不修改</b>，05-工程结构.md §D 模块 06 那一行）；本包只管<b>树本身的读写</b>。
 */
package com.edumatrix.org.node;
