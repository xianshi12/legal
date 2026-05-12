package com.fatongai.legalassistant.chat.controller;

import com.fatongai.legalassistant.ai.LegalAiService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fatongai.legalassistant.auth.AuthContext;
import com.fatongai.legalassistant.chat.dto.AttachmentExtractItem;
import com.fatongai.legalassistant.chat.dto.ChatMessage;
import com.fatongai.legalassistant.chat.dto.ChatMessageAttachment;
import com.fatongai.legalassistant.chat.dto.CreateSessionRequest;
import com.fatongai.legalassistant.chat.dto.SendMessageResponse;
import com.fatongai.legalassistant.chat.service.ChatFileRegistryService;
import com.fatongai.legalassistant.chat.service.ChatHistoryService;
import com.fatongai.legalassistant.chat.service.LegalBasisMatcher;
import com.fatongai.legalassistant.chat.service.ChatSessionService;
import com.fatongai.legalassistant.common.ApiResponse;
import com.fatongai.legalassistant.file.AttachmentExtractService;
import com.fatongai.legalassistant.file.FileStorageService;
import com.fatongai.legalassistant.rag.service.RagKnowledgeService;
import jakarta.validation.Valid;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatSessionService sessionService;
    private final ChatHistoryService historyService;
    private final LegalAiService legalAiService;
    private final LegalBasisMatcher legalBasisMatcher;
    private final FileStorageService fileStorageService;
    private final AttachmentExtractService attachmentExtractService;
    private final ChatFileRegistryService chatFileRegistryService;
    private final RagKnowledgeService ragKnowledgeService;
    private final ObjectMapper objectMapper;

    public ChatController(ChatSessionService sessionService,
                          ChatHistoryService historyService,
                          LegalAiService legalAiService,
                          LegalBasisMatcher legalBasisMatcher,
                          FileStorageService fileStorageService,
                          AttachmentExtractService attachmentExtractService,
                          ChatFileRegistryService chatFileRegistryService,
                          RagKnowledgeService ragKnowledgeService,
                          ObjectMapper objectMapper) {
        this.sessionService = sessionService;
        this.historyService = historyService;
        this.legalAiService = legalAiService;
        this.legalBasisMatcher = legalBasisMatcher;
        this.fileStorageService = fileStorageService;
        this.attachmentExtractService = attachmentExtractService;
        this.chatFileRegistryService = chatFileRegistryService;
        this.ragKnowledgeService = ragKnowledgeService;
        this.objectMapper = objectMapper;
    }

    private Long currentUserId() {
        Long userId = AuthContext.getUserId();
        return userId == null ? 1L : userId;
    }

    @PostMapping("/session/create")
    public ApiResponse<?> createSession(@Valid @RequestBody CreateSessionRequest req) {
        String sid = sessionService.createSession(currentUserId(), req.getTitle());
        var session = sessionService.requireSession(currentUserId(), sid);
        return ApiResponse.ok(new Object() {
            public final String sessionId = sid;
            public final LocalDateTime createdAt = session.getCreatedAt();
        });
    }

    @GetMapping("/session/list")
    public ApiResponse<?> listSessions() {
        return ApiResponse.ok(sessionService.listSessions(currentUserId()));
    }

    @PostMapping("/session/ensure-empty")
    public ApiResponse<?> ensureEmptySession() {
        String keep = sessionService.ensureSingleEmptySession(currentUserId());
        return ApiResponse.ok(new Object() {
            public final String sessionId = keep;
        });
    }

    @DeleteMapping("/session/{sessionId}")
    public ApiResponse<?> deleteSession(@PathVariable("sessionId") String sessionId) {
        sessionService.deleteSessionData(currentUserId(), sessionId);
        return ApiResponse.ok(new Object() {
            public final String deletedSessionId = sessionId;
        });
    }

    @GetMapping("/message/history/{sessionId}")
    public ApiResponse<?> history(@PathVariable("sessionId") String sessionId) {
        sessionService.requireSession(currentUserId(), sessionId);
        List<ChatMessage> list = historyService.list(sessionId);
        return ApiResponse.ok(list);
    }

    /**
     * 预览 / 下载智能咨询中用户上传的附件（需会话归属校验）。
     */
    @GetMapping("/files/{fileId}")
    public ResponseEntity<Resource> chatUploadedFile(@PathVariable("fileId") String fileId,
                                                     @RequestParam("sessionId") String sessionId) {
        try {
            sessionService.requireSession(currentUserId(), sessionId);
            var resolved = chatFileRegistryService.requireReadable(fileId, sessionId);
            Resource body = new FileSystemResource(resolved.path().toFile());
            MediaType media = MediaType.APPLICATION_OCTET_STREAM;
            if (StringUtils.hasText(resolved.contentType())) {
                try {
                    media = MediaType.parseMediaType(resolved.contentType());
                } catch (Exception ignored) {
                }
            }
            ContentDisposition disposition = ContentDisposition.inline()
                    .filename(resolved.originalName(), StandardCharsets.UTF_8)
                    .build();
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                    .contentType(media)
                    .body(body);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.notFound().build();
        } catch (Exception ex) {
            return ResponseEntity.internalServerError().build();
        }
    }

    @PostMapping(value = "/attachments/extract-text", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<List<AttachmentExtractItem>> extractAttachmentText(
            @RequestPart(value = "files", required = false) List<MultipartFile> files) {
        if (files == null || files.isEmpty()) {
            return ApiResponse.ok(List.of());
        }
        List<AttachmentExtractItem> out = new ArrayList<>();
        for (MultipartFile f : files) {
            if (f == null || f.isEmpty()) continue;
            out.add(attachmentExtractService.extractMultipart(f));
        }
        return ApiResponse.ok(out);
    }

    @PostMapping(value = "/message/send", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<?> send(@RequestParam("question") String question,
                               @RequestParam(value = "sessionId", required = false) String sessionId,
                               @RequestPart(value = "files", required = false) List<MultipartFile> files,
                               @RequestParam(value = "preExtractedTextsJson", required = false) String preExtractedTextsJson) throws Exception {
        SendMessageResponse resp = processMessage(question, sessionId, files, null, parsePreExtracted(preExtractedTextsJson));
        return ApiResponse.ok(resp);
    }

    @PostMapping(value = "/message/stream", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam("question") String question,
                             @RequestParam(value = "sessionId", required = false) String sessionId,
                             @RequestPart(value = "files", required = false) List<MultipartFile> files,
                             @RequestParam(value = "preExtractedTextsJson", required = false) String preExtractedTextsJson) {
        SseEmitter emitter = new SseEmitter(0L);
        CompletableFuture.runAsync(() -> {
            try {
                Consumer<String> tokenConsumer = chunk -> {
                    try {
                        emitter.send(SseEmitter.event().name("token").data(chunk));
                    } catch (Exception e) {
                        throw new IllegalStateException("流式响应发送失败", e);
                    }
                };
                SendMessageResponse resp = processMessage(question, sessionId, files, tokenConsumer, parsePreExtracted(preExtractedTextsJson));
                emitter.send(SseEmitter.event().name("done").data(resp));
                emitter.complete();
            } catch (Exception e) {
                try {
                    emitter.send(SseEmitter.event().name("error").data(e.getMessage()));
                } catch (Exception ignored) {
                }
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }

    private SendMessageResponse processMessage(String question,
                                               String sessionId,
                                               List<MultipartFile> files,
                                               Consumer<String> tokenConsumer,
                                               List<String> preExtractedTexts) throws Exception {
        if (!StringUtils.hasText(question)) throw new IllegalArgumentException("问题不能为空");

        String sid = sessionId;
        if (!StringUtils.hasText(sid)) {
            sid = sessionService.createSession(currentUserId(), briefTitle(question));
        } else {
            sessionService.requireSession(currentUserId(), sid);
        }
        boolean firstMessageInSession = historyService.isEmpty(sid);

        // 1) 处理文件：落盘 + 文本提取（图片走百度千帆 PaddleOCR-VL；文档走 Tika）。可与前端 preExtractedTextsJson 对齐避免重复识别。
        List<String> attachmentTexts = new ArrayList<>();
        List<String> refs = new ArrayList<>();
        List<ChatMessageAttachment> userAttachments = new ArrayList<>();
        List<String> preList = preExtractedTexts == null ? List.of() : preExtractedTexts;
        int fileIdx = 0;
        if (files != null) {
            for (MultipartFile f : files) {
                if (f == null || f.isEmpty()) continue;
                var stored = fileStorageService.store(f);
                chatFileRegistryService.register(sid, stored.fileId(), stored.path(), stored.originalName(), stored.contentType());
                userAttachments.add(toAttachment(stored));
                refs.add("附件：" + stored.originalName());

                String pre = fileIdx < preList.size() ? preList.get(fileIdx) : "";
                fileIdx++;

                if (StringUtils.hasText(pre)) {
                    attachmentTexts.add("【" + stored.originalName() + "】\n" + clip(pre, 4000));
                    continue;
                }

                var extracted = attachmentExtractService.extractFromStored(stored);
                if (extracted.ok() && StringUtils.hasText(extracted.text())) {
                    attachmentTexts.add("【" + extracted.filename() + "】\n" + clip(extracted.text(), 4000));
                } else if (!extracted.ok()) {
                    attachmentTexts.add("【" + extracted.filename() + "】解析失败：" + (extracted.error() == null ? "未知错误" : extracted.error()));
                }
            }
        }

        // 2) 组装最近多轮上下文（时间顺序）并匹配候选法条，再记录当前用户消息
        List<String> recentMessages = historyService.listChronological(sid, 12).stream()
                .map(m -> "[%s] %s".formatted(m.getRole(), clip(m.getContent(), 600)))
                .toList();
        List<String> lawRefs = legalBasisMatcher.matchReferences(question);
        List<String> ragRefs = ragKnowledgeService.referenceLines(question, "chat", 4);
        String ragContext = ragKnowledgeService.contextBlock(question, "chat", 4);
        if (StringUtils.hasText(ragContext)) {
            attachmentTexts.add("【RAG知识库检索片段】\n" + ragContext);
        }
        for (String ragRef : ragRefs) {
            if (!lawRefs.contains(ragRef)) {
                lawRefs.add(ragRef);
            }
        }

        ChatMessage userTurn = historyService.appendUser(sid, question, userAttachments);

        // 3) 调用模型
        var ai = tokenConsumer == null
                ? legalAiService.answer(question, attachmentTexts, recentMessages, lawRefs)
                : legalAiService.streamAnswer(question, attachmentTexts, recentMessages, lawRefs, tokenConsumer);

        // 4) 拼接“附件引用 + 法条要点引用”，写入历史并更新会话时间
        List<String> mergedRefs = new ArrayList<>(refs);
        for (String lawRef : lawRefs) {
            if (!mergedRefs.contains(lawRef)) {
                mergedRefs.add(lawRef);
            }
        }

        ChatMessage assistant = historyService.appendAssistant(
                sid,
                ai.content(),
                mergedRefs,
                ai.scenario(),
                ai.conclusion(),
                ai.legalBasis(),
                ai.actionSteps(),
                ai.evidenceChecklist(),
                ai.riskWarnings(),
                ai.followupQuestions()
        );
        String sessionTitle = firstMessageInSession
                ? sessionService.renameDefaultSession(currentUserId(), sid, question, ai.scenario())
                : sessionService.requireSession(currentUserId(), sid).getTitle();
        sessionService.touch(currentUserId(), sid);

        SendMessageResponse resp = new SendMessageResponse();
        resp.setSessionId(sid);
        resp.setSessionTitle(sessionTitle);
        resp.setUserMessageId(userTurn.getId());
        resp.setUserAttachments(userTurn.getAttachments());
        resp.setMessageId(assistant.getId());
        resp.setAnswer(ai.content());
        resp.setScenario(ai.scenario());
        resp.setConclusion(ai.conclusion());
        resp.setLegalBasis(ai.legalBasis());
        resp.setActionSteps(ai.actionSteps());
        resp.setEvidenceChecklist(ai.evidenceChecklist());
        resp.setRiskWarnings(ai.riskWarnings());
        resp.setFollowupQuestions(ai.followupQuestions());
        resp.setReferences(mergedRefs);
        return resp;
    }

    private String briefTitle(String question) {
        String q = question.trim();
        if (q.length() <= 18) return q;
        return q.substring(0, 18) + "…";
    }

    private String clip(String s, int max) {
        if (s == null) return "";
        String t = s.trim();
        if (t.length() <= max) return t;
        return t.substring(0, max) + "\n（内容过长已截断）";
    }

    private static ChatMessageAttachment toAttachment(FileStorageService.StoredFile stored) {
        ChatMessageAttachment a = new ChatMessageAttachment();
        a.setFileId(stored.fileId());
        a.setOriginalName(stored.originalName());
        a.setContentType(stored.contentType() == null ? "" : stored.contentType());
        a.setSize(stored.size());
        return a;
    }

    private List<String> parsePreExtracted(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            List<String> list = objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
            return list == null ? List.of() : list;
        } catch (Exception ignored) {
            return List.of();
        }
    }
}
