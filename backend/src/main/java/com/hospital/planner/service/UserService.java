package com.hospital.planner.service;

import com.hospital.planner.common.CurrentUser;
import com.hospital.planner.common.PageResult;
import com.hospital.planner.dto.UserDto;
import com.hospital.planner.entity.Department;
import com.hospital.planner.entity.User;
import com.hospital.planner.exception.ApiException;
import com.hospital.planner.repository.DepartmentRepository;
import com.hospital.planner.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {
    public static final Set<String> ROLES = Set.of("ADMIN", "ENGINEER", "STAFF", "VIEWER");
    private final UserRepository users;
    private final DepartmentRepository departments;
    private final PasswordEncoder encoder;
    private final AuditService audit;
    private final EntityManager em;

    public UserService(UserRepository users, DepartmentRepository departments, PasswordEncoder encoder, AuditService audit, EntityManager em) {
        this.users = users;
        this.departments = departments;
        this.encoder = encoder;
        this.audit = audit;
        this.em = em;
    }

    @Transactional(readOnly = true)
    public PageResult<UserDto> list(String q, String role, Boolean active, int page, int size) {
        StringBuilder w = new StringBuilder(" where 1=1");
        Map<String, Object> p = new LinkedHashMap<>();
        if (q != null && !q.isBlank()) {
            w.append(" and (lower(u.username) like :q or lower(u.fullName) like :q)");
            p.put("q", "%" + q.trim().toLowerCase() + "%");
        }
        if (role != null && !role.isBlank()) { w.append(" and u.role = :role"); p.put("role", role); }
        if (active != null) { w.append(" and u.active = :active"); p.put("active", active); }
        int sz = Math.max(1, Math.min(size, 200));
        int pg = Math.max(0, page);
        TypedQuery<Long> count = em.createQuery("select count(u) from User u" + w, Long.class);
        TypedQuery<User> data = em.createQuery("select u from User u left join fetch u.department" + w + " order by u.username", User.class);
        p.forEach((k, v) -> { count.setParameter(k, v); data.setParameter(k, v); });
        long total = count.getSingleResult();
        data.setFirstResult(pg * sz);
        data.setMaxResults(sz);
        List<UserDto> items = data.getResultList().stream().map(UserDto::of).toList();
        return new PageResult<>(items, pg, sz, total, (int) ((total + sz - 1) / sz));
    }

    @Transactional(readOnly = true)
    public UserDto get(long id) {
        return UserDto.of(users.findWithDepartment(id).orElseThrow(() -> ApiException.notFound("User " + id)));
    }

    @Transactional
    public UserDto create(String username, String password, String fullName, String email, String role, Long departmentId) {
        if (!ROLES.contains(role)) throw ApiException.unprocessable("INVALID_ROLE", "Role must be one of " + ROLES);
        if (users.existsByUsernameIgnoreCase(username)) throw ApiException.conflict("DUPLICATE_USER", "Username already exists");
        User u = new User();
        u.username = username;
        u.passwordHash = encoder.encode(password);
        u.fullName = fullName;
        u.email = email;
        u.role = role;
        u.department = department(departmentId);
        users.saveAndFlush(u);
        UserDto dto = UserDto.of(users.findWithDepartment(u.id).orElseThrow());
        audit.log("USER_CREATED", "USER", u.id, null, dto);
        return dto;
    }

    private static Map<String, Object> snapshot(User u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("fullName", u.fullName);
        m.put("email", u.email);
        m.put("role", u.role);
        m.put("departmentId", u.department == null ? null : u.department.id);
        m.put("active", u.active);
        return m;
    }

    /** PATCH semantics: a field that is omitted (null) keeps its stored value. */
    @Transactional
    public UserDto update(long id, String fullName, String email, String role, Long departmentId, Boolean active) {
        User u = users.findWithDepartment(id).orElseThrow(() -> ApiException.notFound("User " + id));
        Map<String, Object> before = snapshot(u);
        String newRole = role == null ? u.role : role;
        boolean newActive = active == null ? u.active : active;
        if (!ROLES.contains(newRole)) throw ApiException.unprocessable("INVALID_ROLE", "Role must be one of " + ROLES);
        boolean wasActiveAdmin = "ADMIN".equals(u.role) && u.active;
        if (wasActiveAdmin && (!"ADMIN".equals(newRole) || !newActive) && users.countByRoleAndActiveTrue("ADMIN") <= 1) {
            throw ApiException.conflict("LAST_ADMIN", "Cannot remove or deactivate the last active ADMIN");
        }
        if (!newActive && u.username.equals(CurrentUser.username())) {
            throw ApiException.conflict("SELF_DEACTIVATION", "You cannot deactivate your own account");
        }
        if (fullName != null) u.fullName = fullName;
        if (email != null) u.email = email;
        if (departmentId != null) u.department = department(departmentId);
        u.role = newRole;
        u.active = newActive;
        users.saveAndFlush(u);
        var diff = AuditService.diff(before, snapshot(u));
        audit.log("USER_UPDATED", "USER", id, diff.get("old"), diff.get("new"));
        return UserDto.of(u);
    }

    @Transactional
    public void resetPassword(long id, String newPassword) {
        User u = users.findById(id).orElseThrow(() -> ApiException.notFound("User " + id));
        u.passwordHash = encoder.encode(newPassword);
        Map<String, Object> note = new LinkedHashMap<>();
        note.put("username", u.username);
        audit.log("PASSWORD_RESET", "USER", id, null, note);   // never log the password or hash
    }

    private Department department(Long departmentId) {
        if (departmentId == null) return null;
        return departments.findById(departmentId)
                .orElseThrow(() -> ApiException.unprocessable("UNKNOWN_DEPARTMENT", "Department " + departmentId + " does not exist"));
    }
}
