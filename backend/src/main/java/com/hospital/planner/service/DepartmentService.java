package com.hospital.planner.service;

import com.hospital.planner.dto.DepartmentDto;
import com.hospital.planner.entity.Department;
import com.hospital.planner.entity.LifecycleStatus;
import com.hospital.planner.exception.ApiException;
import com.hospital.planner.repository.DepartmentRepository;
import com.hospital.planner.repository.EquipmentRepository;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DepartmentService {
    private final DepartmentRepository departments;
    private final EquipmentRepository equipment;
    private final AuditService audit;

    public DepartmentService(DepartmentRepository departments, EquipmentRepository equipment, AuditService audit) {
        this.departments = departments;
        this.equipment = equipment;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<DepartmentDto> list(boolean includeInactive) {
        List<Department> all = includeInactive ? departments.findAllByOrderByName() : departments.findByActiveTrueOrderByName();
        return all.stream().map(d -> new DepartmentDto(d.id, d.name, d.active,
                equipment.countByDepartmentIdAndLifecycleStatusNot(d.id, LifecycleStatus.RETIRED))).toList();
    }

    @Transactional
    public DepartmentDto create(String name) {
        String n = name.trim();
        if (departments.existsByNameIgnoreCase(n)) throw ApiException.conflict("DUPLICATE_DEPARTMENT", "A department with this name already exists");
        Department d = new Department();
        d.name = n;
        departments.saveAndFlush(d);
        DepartmentDto dto = new DepartmentDto(d.id, d.name, d.active, null);
        audit.log("DEPARTMENT_CREATED", "DEPARTMENT", d.id, null, dto);
        return dto;
    }

    @Transactional
    public DepartmentDto update(long id, String name, Boolean active) {
        Department d = departments.findById(id).orElseThrow(() -> ApiException.notFound("Department " + id));
        DepartmentDto before = new DepartmentDto(d.id, d.name, d.active, null);
        boolean newActive = active == null ? d.active : active;
        if (!newActive && equipment.countByDepartmentIdAndLifecycleStatusNot(id, LifecycleStatus.RETIRED) > 0) {
            throw ApiException.conflict("DEPARTMENT_HAS_EQUIPMENT", "Transfer or retire the department's equipment before deactivating it");
        }
        String n = name.trim();
        if (departments.existsByNameIgnoreCaseAndIdNot(n, id)) throw ApiException.conflict("DUPLICATE_DEPARTMENT", "A department with this name already exists");
        d.name = n;
        d.active = newActive;
        departments.saveAndFlush(d);
        DepartmentDto after = new DepartmentDto(d.id, d.name, d.active, null);
        audit.log("DEPARTMENT_UPDATED", "DEPARTMENT", id, before, after);
        return after;
    }
}
