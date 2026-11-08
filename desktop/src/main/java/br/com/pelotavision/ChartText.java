package br.com.pelotavision;

import java.awt.FontMetrics;
import java.util.ArrayList;
import java.util.List;

/** Quebra legendas sem alterar valores, inclusive em paineis estreitos. */
final class ChartText {
    private ChartText() {}

    static List<String> wrap(String text, FontMetrics fm, int maxWidth) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        int width = Math.max(1, maxWidth);
        for (String word : text.trim().split("\\s+")) {
            if (word.isEmpty()) continue;
            String combined = line.length() == 0 ? word : line + " " + word;
            if (fm.stringWidth(combined) <= width) { line.setLength(0); line.append(combined); continue; }
            if (line.length() != 0) { lines.add(line.toString()); line.setLength(0); }
            for (int i = 0; i < word.length(); i++) {
                char ch = word.charAt(i);
                if (line.length() > 0 && fm.stringWidth(line.toString() + ch) > width) {
                    lines.add(line.toString()); line.setLength(0);
                }
                line.append(ch);
            }
        }
        if (line.length() > 0) lines.add(line.toString());
        return lines;
    }
}
