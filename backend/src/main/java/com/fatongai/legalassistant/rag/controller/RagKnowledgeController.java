package com.fatongai.legalassistant.rag.controller;

import com.fatongai.legalassistant.common.ApiResponse;
import com.fatongai.legalassistant.rag.dto.RagAnswerRequest;
import com.fatongai.legalassistant.rag.dto.RagIngestTextRequest;
import com.fatongai.legalassistant.rag.service.RagKnowledgeService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/rag")
public class RagKnowledgeController {
    private final RagKnowledgeService ragKnowledgeService;

    public RagKnowledgeController(RagKnowledgeService ragKnowledgeService) {
        this.ragKnowledgeService = ragKnowledgeService;
    }

    @GetMapping("/meta")
    public ApiResponse<?> meta() {
        return ApiResponse.ok(ragKnowledgeService.meta());
    }

    @GetMapping("/documents")
    public ApiResponse<?> documents(@RequestParam(value = "moduleScope", required = false) String moduleScope,
                                    @RequestParam(value = "status", required = false) String status,
                                    @RequestParam(value = "page", defaultValue = "1") Integer page,
                                    @RequestParam(value = "size", defaultValue = "20") Integer size) {
        return ApiResponse.ok(ragKnowledgeService.listDocuments(moduleScope, status, page, size));
    }

    @PostMapping(value = "/documents/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<?> upload(@RequestPart("file") MultipartFile file,
                                 @RequestParam(value = "title", required = false) String title,
                                 @RequestParam(value = "sourceType", defaultValue = "upload") String sourceType,
                                 @RequestParam(value = "moduleScope", defaultValue = "all") String moduleScope,
                                 @RequestParam(value = "businessType", defaultValue = "通用法律知识") String businessType,
                                 @RequestParam(value = "tags", required = false) String tags) throws Exception {
        return ApiResponse.ok(ragKnowledgeService.ingestFile(file, title, sourceType, moduleScope, businessType, tags));
    }

    @PostMapping("/documents/text")
    public ApiResponse<?> ingestText(@RequestBody RagIngestTextRequest request) {
        return ApiResponse.ok(ragKnowledgeService.ingestText(
                request.getTitle(),
                request.getText(),
                request.getSourceType(),
                request.getModuleScope(),
                request.getBusinessType(),
                request.getTags()
        ));
    }

    @DeleteMapping("/documents/{docId}")
    public ApiResponse<?> delete(@PathVariable("docId") String docId) {
        return ApiResponse.ok(java.util.Map.of("deleted", ragKnowledgeService.deleteDocument(docId)));
    }

    @GetMapping("/search")
    public ApiResponse<?> search(@RequestParam(value = "q", required = false) String query,
                                 @RequestParam(value = "moduleScope", required = false) String moduleScope,
                                 @RequestParam(value = "businessType", required = false) String businessType,
                                 @RequestParam(value = "limit", defaultValue = "6") Integer limit) {
        return ApiResponse.ok(ragKnowledgeService.search(query, moduleScope, businessType, limit));
    }

    @PostMapping("/answer")
    public ApiResponse<?> answer(@RequestBody RagAnswerRequest request) {
        return ApiResponse.ok(ragKnowledgeService.answer(
                request.getQuestion(),
                request.getModuleScope(),
                request.getBusinessType(),
                request.getLimit()
        ));
    }

    @PostMapping("/bootstrap-foundation")
    public ApiResponse<?> bootstrapFoundation() {
        return ApiResponse.ok(ragKnowledgeService.bootstrapFoundation());
    }
}
