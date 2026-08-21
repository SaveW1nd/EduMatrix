package com.edumatrix.org.member.service;

import org.springframework.stereotype.Component;

import com.edumatrix.common.student.StudentEnrollmentReader;
import com.edumatrix.org.member.entity.OrgStudent;
import com.edumatrix.org.member.mapper.OrgStudentMapper;

/**
 * {@link StudentEnrollmentReader} 在 {@code org} 领域的唯一实现。
 *
 * <p>照 {@code course/catalog/LessonVisibilityProvider}、{@code system/user/SysUserNameProvider}
 * 的先例：接口在 {@code common/}、实现在拥有该表的领域里，跨领域调用方只依赖接口。
 */
@Component
public class OrgStudentEnrollmentProvider implements StudentEnrollmentReader {

    private final OrgStudentMapper studentMapper;

    public OrgStudentEnrollmentProvider(OrgStudentMapper studentMapper) {
        this.studentMapper = studentMapper;
    }

    @Override
    public Enrollment byNodeId(Long nodeId) {
        if (nodeId == null) {
            return null;
        }
        OrgStudent student = studentMapper.selectByNodeId(nodeId);
        return student == null ? null : new Enrollment(student.getId(), student.getStatus());
    }
}
