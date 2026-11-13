package com.hospital.planner.controller;

import com.hospital.planner.dto.DocumentDto;
import com.hospital.planner.entity.EquipmentDocument;
import com.hospital.planner.exception.ApiException;
import com.hospital.planner.service.DocumentService;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {
    private final DocumentService service;

    public DocumentController(DocumentService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DocumentDto upload(@RequestParam("file") MultipartFile file, @RequestParam String entityType, @RequestParam long entityId,
                              @RequestParam(defaultValue = "ATTACHMENT") String kind) throws IOException {
        if (file.isEmpty()) throw ApiException.bad("The uploaded file is empty");
        return service.upload(entityType, entityId, kind, file.getOriginalFilename(), file.getContentType(), file.getBytes());
    }

    @GetMapping
    public List<DocumentDto> list(@RequestParam String entityType, @RequestParam long entityId) {
        return service.list(entityType, entityId);
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<byte[]> download(@PathVariable long id) {
        EquipmentDocument d = service.forDownload(id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + d.filename + "\"")
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType(d.contentType))
                .body(d.content);
    }
}
