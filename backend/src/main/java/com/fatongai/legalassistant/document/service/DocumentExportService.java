package com.fatongai.legalassistant.document.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class DocumentExportService {

    public byte[] export(String formatRaw, String titleRaw, String content) throws IOException {
        String format = formatRaw == null ? "" : formatRaw.trim().toUpperCase(Locale.ROOT);
        String title = StringUtils.hasText(titleRaw) ? titleRaw.trim() : "法律文书";
        return switch (format) {
            case "TXT" -> exportTxt(title, content);
            case "DOCX" -> exportDocx(title, content);
            case "PDF" -> exportPdf(title, content);
            default -> throw new IllegalArgumentException("不支持的导出格式");
        };
    }

    private byte[] exportTxt(String title, String content) {
        String body = title + System.lineSeparator() + System.lineSeparator() + (content == null ? "" : content);
        return body.getBytes(StandardCharsets.UTF_8);
    }

    private byte[] exportDocx(String title, String content) throws IOException {
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XWPFParagraph titlePara = doc.createParagraph();
            titlePara.setAlignment(ParagraphAlignment.CENTER);
            XWPFRun titleRun = titlePara.createRun();
            titleRun.setBold(true);
            titleRun.setFontSize(18);
            titleRun.setFontFamily("宋体");
            titleRun.setText(title);

            doc.createParagraph();

            String text = content == null ? "" : content;
            for (String line : text.split("\\R", -1)) {
                XWPFParagraph p = doc.createParagraph();
                XWPFRun run = p.createRun();
                run.setFontFamily("宋体");
                run.setFontSize(12);
                run.setText(line.isEmpty() ? " " : line);
            }
            doc.write(out);
            return out.toByteArray();
        }
    }

    private record TextChunk(String text, float fontSize) {}

    private byte[] exportPdf(String title, String content) throws IOException {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDFont font = loadUnicodeFont(document);
            float margin = 48f;
            float pageHeight = PDRectangle.A4.getHeight();
            float pageWidth = PDRectangle.A4.getWidth();
            float maxW = pageWidth - 2 * margin;
            float bottom = margin;
            float titleFs = 14f;
            float bodyFs = 11f;

            List<TextChunk> chunks = new ArrayList<>();
            for (String t : wrapLine(title, font, titleFs, maxW)) {
                chunks.add(new TextChunk(t, titleFs));
            }
            chunks.add(new TextChunk("", bodyFs));
            String rawBody = content == null ? "" : content;
            for (String raw : rawBody.split("\\R", -1)) {
                for (String w : wrapLine(raw, font, bodyFs, maxW)) {
                    chunks.add(new TextChunk(w, bodyFs));
                }
            }

            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            PDPageContentStream cs = new PDPageContentStream(document, page);
            float y = pageHeight - margin;

            try {
                for (TextChunk chunk : chunks) {
                    float fs = chunk.fontSize();
                    float lead = fs * 1.38f;
                    if (y - lead < bottom) {
                        cs.close();
                        page = new PDPage(PDRectangle.A4);
                        document.addPage(page);
                        cs = new PDPageContentStream(document, page);
                        y = pageHeight - margin;
                    }
                    String line = chunk.text();
                    cs.beginText();
                    cs.setFont(font, fs);
                    cs.newLineAtOffset(margin, y - fs);
                    cs.showText(line.isEmpty() ? " " : line);
                    cs.endText();
                    y -= lead;
                }
            } finally {
                cs.close();
            }
            document.save(out);
            return out.toByteArray();
        }
    }

    private PDFont loadUnicodeFont(PDDocument document) throws IOException {
        String env = System.getenv("LEGAL_PDF_FONT_PATH");
        if (StringUtils.hasText(env)) {
            Path p = Path.of(env.trim());
            if (Files.isRegularFile(p)) {
                try (InputStream in = Files.newInputStream(p)) {
                    return PDType0Font.load(document, in, true);
                }
            }
        }
        ClassPathResource res = new ClassPathResource("fonts/NotoSansSC-Regular.otf");
        if (res.exists()) {
            try (InputStream in = res.getInputStream()) {
                return PDType0Font.load(document, in, true);
            }
        }
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            String[] winFonts = {
                    "C:/Windows/Fonts/simhei.ttf",
                    "C:/Windows/Fonts/msyh.ttf",
            };
            for (String fp : winFonts) {
                File f = new File(fp);
                if (f.isFile()) {
                    try (InputStream in = Files.newInputStream(f.toPath())) {
                        return PDType0Font.load(document, in, true);
                    }
                }
            }
        }
        if (os.contains("linux")) {
            String[] linuxFonts = {
                    "/usr/share/fonts/truetype/wqy/wqy-microhei.ttc",
                    "/usr/share/fonts/truetype/arphic/uming.ttc",
                    "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
            };
            for (String fp : linuxFonts) {
                File f = new File(fp);
                if (f.isFile()) {
                    try (InputStream in = Files.newInputStream(f.toPath())) {
                        return PDType0Font.load(document, in, true);
                    }
                }
            }
        }
        if (os.contains("mac")) {
            File f = new File("/System/Library/Fonts/PingFang.ttc");
            if (f.isFile()) {
                try (InputStream in = Files.newInputStream(f.toPath())) {
                    return PDType0Font.load(document, in, true);
                }
            }
        }
        throw new IllegalStateException(
                "未找到可嵌入 PDF 的中文字体。请在 Windows 保留系统字体，或在 Linux 安装 fonts-wqy-microhei，"
                        + "或设置环境变量 LEGAL_PDF_FONT_PATH 指向 .ttf/.ttc 文件，或将字体放到 classpath:fonts/NotoSansSC-Regular.otf");
    }

    private List<String> wrapLine(String line, PDFont font, float fontSize, float maxWidth) throws IOException {
        List<String> out = new ArrayList<>();
        if (line == null || line.isEmpty()) {
            out.add("");
            return out;
        }
        StringBuilder cur = new StringBuilder();
        int i = 0;
        while (i < line.length()) {
            int cp = line.codePointAt(i);
            String ch = new String(Character.toChars(cp));
            i += Character.charCount(cp);
            String test = cur + ch;
            float w = font.getStringWidth(test) / 1000f * fontSize;
            if (w > maxWidth && !cur.isEmpty()) {
                out.add(cur.toString());
                cur = new StringBuilder(ch);
            } else {
                cur.append(ch);
            }
        }
        if (!cur.isEmpty()) {
            out.add(cur.toString());
        }
        return out;
    }
}
