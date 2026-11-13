package com.hospital.planner.service;

import com.hospital.planner.common.CurrentUser;
import com.hospital.planner.dto.DocumentDto;
import com.hospital.planner.entity.EquipmentDocument;
import com.hospital.planner.exception.ApiException;
import com.hospital.planner.repository.CalibrationRepository;
import com.hospital.planner.repository.EquipmentDocumentRepository;
import com.hospital.planner.repository.EquipmentRepository;
import com.hospital.planner.repository.MaintenanceRepository;
import com.hospital.planner.repository.WorkOrderRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Certificates, service reports and attachments, stored in PostgreSQL (max 5 MB each). */
@Service
public class DocumentService {
    public static final Set<String> TYPES = Set.of("ASSET", "MAINTENANCE", "CALIBRATION", "WORK_ORDER");
    public static final Set<String> KINDS = Set.of("CERTIFICATE", "SERVICE_REPORT", "INVOICE", "MANUAL", "ATTACHMENT");
    public static final Set<String> CONTENT = Set.of("application/pdf", "image/png", "image/jpeg", "text/plain", "text/csv");

    private final EquipmentDocumentRepository docs;
    private final EquipmentRepository equipment;
    private final MaintenanceRepository maintenance;
    private final CalibrationRepository calibrations;
    private final WorkOrderRepository workOrders;
    private final AuditService audit;

    public DocumentService(EquipmentDocumentRepository docs, EquipmentRepository equipment, MaintenanceRepository maintenance,
                           CalibrationRepository calibrations, WorkOrderRepository workOrders, AuditService audit) {
        this.docs = docs;
        this.equipment = equipment;
        this.maintenance = maintenance;
        this.calibrations = calibrations;
        this.workOrders = workOrders;
        this.audit = audit;
    }

    private static String cleanName(String name) {
        String n = name == null ? "file" : name.replace('\\', '/');
        n = n.substring(n.lastIndexOf('/') + 1).replaceAll("[^A-Za-z0-9._ -]", "_").trim();
        return n.isEmpty() ? "file" : (n.length() > 150 ? n.substring(n.length() - 150) : n);
    }

    private boolean exists(String type, long id) {
        return switch (type) {
            case "ASSET" -> equipment.existsById(id);
            case "MAINTENANCE" -> maintenance.existsById(id);
            case "CALIBRATION" -> calibrations.existsById(id);
            default -> workOrders.existsById(id);
        };
    }

    /** Who may read documents: work order documents are visible to staff only for breakdowns they reported; never to viewers. */
    private void assertCanView(String type, long entityId) {
        if (!"WORK_ORDER".equals(type)) return;
        if (CurrentUser.is("VIEWER")) throw ApiException.forbidden("Viewers cannot see work order documents");
        if (CurrentUser.is("STAFF") && !workOrders.findReporter(entityId).map(CurrentUser.username()::equals).orElse(false)) {
            throw ApiException.forbidden("You can only see documents of breakdowns you reported");
        }
    }

    /** Who may attach: ADMIN anywhere; ENGINEER on work they are assigned to; STAFF only on breakdowns they reported. */
    private void assertCanAttach(String type, long entityId) {
        if (CurrentUser.isAdmin()) return;
        String me = CurrentUser.username();
        boolean ok = switch (type) {
            case "MAINTENANCE" -> CurrentUser.is("ENGINEER") && maintenance.findById(entityId).map(m -> me.equals(m.assignedEngineer)).orElse(false);
            case "CALIBRATION" -> CurrentUser.is("ENGINEER") && calibrations.findById(entityId).map(c -> me.equals(c.assignedEngineer)).orElse(false);
            case "WORK_ORDER" -> (CurrentUser.is("ENGINEER") && workOrders.findEngineer(entityId).map(me::equals).orElse(false))
                    || (CurrentUser.is("STAFF") && workOrders.findReporter(entityId).map(me::equals).orElse(false));
            default -> false;
        };
        if (!ok) throw ApiException.forbidden("You may only attach documents to records assigned to you (or breakdowns you reported)");
    }

    @Transactional
    public DocumentDto upload(String entityType, long entityId, String kind, String filename, String contentType, byte[] bytes) {
        if (!TYPES.contains(entityType)) throw ApiException.unprocessable("INVALID_ENTITY_TYPE", "entityType must be one of " + TYPES);
        if (!KINDS.contains(kind)) throw ApiException.unprocessable("INVALID_KIND", "kind must be one of " + KINDS);
        if (bytes == null || bytes.length == 0) throw ApiException.bad("The uploaded file is empty");
        if (contentType == null || !CONTENT.contains(contentType)) {
            throw ApiException.unprocessable("UNSUPPORTED_FILE_TYPE", "Allowed types: PDF, PNG, JPEG, plain text, CSV");
        }
        if (!exists(entityType, entityId)) throw ApiException.notFound(entityType + " " + entityId);
        assertCanAttach(entityType, entityId);
        EquipmentDocument d = new EquipmentDocument();
        d.entityType = entityType;
        d.entityId = entityId;
        d.docKind = kind;
        d.filename = cleanName(filename);
        d.contentType = contentType;
        d.sizeBytes = (long) bytes.length;
        d.content = bytes;
        d.uploadedBy = CurrentUser.username();
        d.uploadedAt = Instant.now();
        docs.saveAndFlush(d);
        audit.log("DOCUMENT_UPLOADED", "DOCUMENT", d.id, null, Map.of("entityType", entityType, "entityId", entityId, "kind", kind));
        return new DocumentDto(d.id, d.entityType, d.entityId, d.docKind, d.filename, d.contentType, d.sizeBytes, d.uploadedBy, d.uploadedAt);
    }

    @Transactional(readOnly = true)
    public List<DocumentDto> list(String entityType, long entityId) {
        if (!TYPES.contains(entityType)) throw ApiException.unprocessable("INVALID_ENTITY_TYPE", "entityType must be one of " + TYPES);
        assertCanView(entityType, entityId);
        return docs.listFor(entityType, entityId);
    }

    /** Returns the stored file (the entity never leaves the service layer through the API; the controller streams its bytes). */
    @Transactional(readOnly = true)
    public EquipmentDocument forDownload(long id) {
        EquipmentDocument d = docs.findById(id).orElseThrow(() -> ApiException.notFound("Document " + id));
        assertCanView(d.entityType, d.entityId);
        return d;
    }
}
