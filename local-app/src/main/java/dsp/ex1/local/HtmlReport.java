package dsp.ex1.local;

import dsp.ex1.common.messages.OcrResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * The output file: one paragraph per input URL, in input order.
 *  - success: the image (linking to the original) and its OCR text below it;
 *  - failure: a link to the original URL and a short description of the error.
 * Everything taken from outside (URLs, OCR text, errors) is HTML-escaped.
 */
final class HtmlReport {

    private HtmlReport() {}

    static void write(Path file, List<OcrResult> results, Map<Integer, String> texts) throws IOException {
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html>\n<html>\n<head>\n<meta charset=\"utf-8\">\n")
                .append("<title>OCR results</title>\n</head>\n<body>\n");
        for (OcrResult result : results) {
            String url = escape(result.imageUrl());
            html.append("<p>\n");
            if (result.failed()) {
                html.append("<a href=\"").append(url).append("\">").append(url).append("</a> ")
                        .append(escape(result.error())).append('\n');
            } else {
                html.append("<a href=\"").append(url).append("\"><img src=\"").append(url).append("\"></a><br>\n")
                        .append("<pre>").append(escape(texts.getOrDefault(result.index(), ""))).append("</pre>\n");
            }
            html.append("</p>\n");
        }
        html.append("</body>\n</html>\n");
        Files.writeString(file, html, StandardCharsets.UTF_8);
    }

    private static String escape(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
