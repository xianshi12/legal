package com.fatongai.legalassistant.file;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.UUID;

@Service
public class FileStorageService {

    @Value("${app.storage.root:./storage}")
    private String root;

    public StoredFile store(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("文件为空");
        String original = StringUtils.cleanPath(file.getOriginalFilename() == null ? "file" : file.getOriginalFilename());
        String date = LocalDate.now().toString().replace("-", "");
        Path dir = Path.of(root, "uploads", date);
        Files.createDirectories(dir);
        String id = UUID.randomUUID().toString().replace("-", "");
        String name = id + "_" + original;
        Path p = dir.resolve(name);
        file.transferTo(p);
        return new StoredFile(id, original, p.toAbsolutePath().toString(), file.getContentType(), file.getSize());
    }

    public record StoredFile(String fileId, String originalName, String path, String contentType, long size) {
    }
}

