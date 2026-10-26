package vn.camhub.device.infrastructure;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Đọc bảng tính để nhập camera (US-07): CSV (UTF-8, dấu phẩy hoặc chấm phẩy, có ngoặc kép) và XLSX (sheet đầu tiên,
 * chuỗi dùng chung) mà không cần thư viện ngoài. Trả về các dòng dạng danh sách ô; dòng đầu là tiêu đề.
 */
public final class SpreadsheetReader {

    static final int MAX_BYTES = 5 * 1024 * 1024;
    static final int MAX_ROWS = 2000;

    private SpreadsheetReader() {
    }

    public static List<List<String>> read(String fileName, byte[] content) throws IOException {
        if (content.length > MAX_BYTES) {
            throw new IOException("Tệp lớn hơn 5 MB");
        }
        String lower = fileName == null ? "" : fileName.toLowerCase();
        List<List<String>> rows = lower.endsWith(".xlsx") ? xlsx(content) : csv(new String(stripBom(content),
                StandardCharsets.UTF_8));
        if (rows.size() > MAX_ROWS + 1) {
            throw new IOException("Tối đa " + MAX_ROWS + " dòng mỗi lần nhập");
        }
        return rows;
    }

    // ---------------------------------------------------------------- CSV

    static List<List<String>> csv(String text) {
        char sep = detectSeparator(text);
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < text.length() && text.charAt(i + 1) == '"') {
                    cell.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    cell.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == sep) {
                row.add(cell.toString().trim());
                cell.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                row.add(cell.toString().trim());
                cell.setLength(0);
                addRow(rows, row);
                row = new ArrayList<>();
            } else {
                cell.append(c);
            }
        }
        if (cell.length() > 0 || !row.isEmpty()) {
            row.add(cell.toString().trim());
            addRow(rows, row);
        }
        return rows;
    }

    private static char detectSeparator(String text) {
        int nl = text.indexOf('\n');
        String first = nl < 0 ? text : text.substring(0, nl);
        return first.chars().filter(ch -> ch == ';').count() > first.chars().filter(ch -> ch == ',').count() ? ';' : ',';
    }

    private static void addRow(List<List<String>> rows, List<String> row) {
        if (row.stream().anyMatch(s -> !s.isEmpty())) {
            rows.add(row);
        }
    }

    private static byte[] stripBom(byte[] b) {
        if (b.length >= 3 && (b[0] & 0xff) == 0xEF && (b[1] & 0xff) == 0xBB && (b[2] & 0xff) == 0xBF) {
            byte[] out = new byte[b.length - 3];
            System.arraycopy(b, 3, out, 0, out.length);
            return out;
        }
        return b;
    }

    // ---------------------------------------------------------------- XLSX (OOXML tối giản)

    static List<List<String>> xlsx(byte[] content) throws IOException {
        Map<String, byte[]> parts = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(content))) {
            ZipEntry e;
            long total = 0;
            while ((e = zip.getNextEntry()) != null) {
                String name = e.getName();
                if (name.equals("xl/sharedStrings.xml") || name.equals("xl/worksheets/sheet1.xml")) {
                    byte[] data = zip.readNBytes(20 * 1024 * 1024);
                    total += data.length;
                    if (total > 40L * 1024 * 1024) {
                        throw new IOException("Tệp XLSX quá lớn sau giải nén"); // chống zip bomb
                    }
                    parts.put(name, data);
                }
            }
        }
        byte[] sheet = parts.get("xl/worksheets/sheet1.xml");
        if (sheet == null) {
            throw new IOException("Không tìm thấy sheet đầu tiên trong tệp XLSX");
        }
        List<String> shared = new ArrayList<>();
        if (parts.containsKey("xl/sharedStrings.xml")) {
            NodeList sis = parse(parts.get("xl/sharedStrings.xml")).getElementsByTagName("si");
            for (int i = 0; i < sis.getLength(); i++) {
                NodeList ts = ((Element) sis.item(i)).getElementsByTagName("t");
                StringBuilder sb = new StringBuilder();
                for (int j = 0; j < ts.getLength(); j++) {
                    sb.append(ts.item(j).getTextContent());
                }
                shared.add(sb.toString());
            }
        }
        List<List<String>> rows = new ArrayList<>();
        NodeList rs = parse(sheet).getElementsByTagName("row");
        for (int i = 0; i < rs.getLength(); i++) {
            NodeList cs = ((Element) rs.item(i)).getElementsByTagName("c");
            List<String> row = new ArrayList<>();
            for (int j = 0; j < cs.getLength(); j++) {
                Element c = (Element) cs.item(j);
                int col = column(c.getAttribute("r"));
                while (row.size() < col) {
                    row.add("");
                }
                row.add(value(c, shared).trim());
            }
            addRow(rows, row);
        }
        return rows;
    }

    private static String value(Element c, List<String> shared) {
        String type = c.getAttribute("t");
        if ("inlineStr".equals(type)) {
            NodeList t = c.getElementsByTagName("t");
            return t.getLength() == 0 ? "" : t.item(0).getTextContent();
        }
        NodeList v = c.getElementsByTagName("v");
        if (v.getLength() == 0) {
            return "";
        }
        String raw = v.item(0).getTextContent();
        if ("s".equals(type)) {
            int idx = Integer.parseInt(raw.trim());
            return idx < shared.size() ? shared.get(idx) : "";
        }
        // Số nguyên lưu dạng 554.0 → 554
        return raw.endsWith(".0") ? raw.substring(0, raw.length() - 2) : raw;
    }

    /** "C12" → 2 (cột bắt đầu từ 0). */
    static int column(String ref) {
        int n = 0;
        for (char ch : (ref == null ? "" : ref).toCharArray()) {
            if (!Character.isLetter(ch)) {
                break;
            }
            n = n * 26 + (Character.toUpperCase(ch) - 'A' + 1);
        }
        return Math.max(0, n - 1);
    }

    private static Document parse(byte[] xml) throws IOException {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); // chặn XXE
            f.setExpandEntityReferences(false);
            try (InputStream in = new ByteArrayInputStream(xml)) {
                return f.newDocumentBuilder().parse(in);
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Tệp XLSX không hợp lệ", e);
        }
    }
}
