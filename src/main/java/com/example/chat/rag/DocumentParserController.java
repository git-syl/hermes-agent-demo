package com.example.chat.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * 文档解析入库接口。
 *
 * <pre>
 * curl -X POST 'http://localhost:8080/document-parser/parse-and-save' \
 *   --form 'file=@"/path/短篇小说.docx"' --form 'tenantId=acme' \
 *   --form 'metadata={"category":"novel","lang":"zh"}'
 * </pre>
 */
@RestController
@RequestMapping("/document-parser")
public class DocumentParserController {

    private final RagService ragService;
    private final ObjectMapper objectMapper;

    public DocumentParserController(RagService ragService, ObjectMapper objectMapper) {
        this.ragService = ragService;
        this.objectMapper = objectMapper;
    }

    /**
     * @param tenantId 租户标识，写入文档元数据用于后续隔离检索；不传则归入 {@code default} 租户。
     * @param metadata 附加业务元数据的 JSON 对象（如 {@code {"category":"novel"}}），随每个切块一起入库，
     *                 供检索时按需过滤；可不传。{@code tenantId} 为保留键，以独立入参为准。
     */
    @PostMapping("/parse-and-save")
    public Map<String, Object> parseAndSave(
            @RequestParam("file") MultipartFile file,
            @RequestParam(name = "tenantId", defaultValue = "default") String tenantId,
            @RequestParam(name = "metadata", required = false) String metadata) {
        int chunks = ragService.parseAndSave(file, tenantId, RagParams.parseMetadata(objectMapper, metadata));
        return Map.of(
                "fileName", file.getOriginalFilename(),
                "tenantId", tenantId,
                "chunks", chunks);
    }
}
