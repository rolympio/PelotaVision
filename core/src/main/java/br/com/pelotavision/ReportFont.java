package br.com.pelotavision;

/**
 * 0.18.0: métricas da Helvetica (Standard 14 do PDF) para o layout do relatório, comuns a Android e Windows.
 * O layout quebra linhas e alinha textos com ESTAS larguras; o Windows desenha com a própria Helvetica
 * (PDFBox) e o Android ajusta a largura da fonte do aparelho (textScaleX) a elas, para os dois PDFs
 * terem as mesmas quebras. Larguras em milésimos de em, códigos 32–255 (WinAnsi/Latin-1), extraídas do PDFBox 3.0.5.
 */
public final class ReportFont {
    private ReportFont() {}
    private static final short[] REGULAR = {278,278,355,556,556,889,667,191,333,333,389,584,278,333,278,278,556,556,556,556,556,556,556,556,556,556,278,278,584,584,584,556,1015,667,667,722,722,667,611,778,722,278,500,667,556,833,722,778,667,778,722,667,611,722,667,944,667,667,611,278,278,278,469,556,333,556,556,500,556,556,278,556,556,222,222,500,222,833,556,556,556,556,333,500,278,556,500,722,500,500,500,334,260,334,584,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,278,333,556,556,556,556,260,556,333,737,370,556,584,333,737,333,400,584,333,333,333,556,537,278,333,333,365,556,834,834,834,611,667,667,667,667,667,667,1000,722,667,667,667,667,278,278,278,278,722,722,778,778,778,778,778,584,778,722,722,722,722,667,667,611,556,556,556,556,556,556,889,500,556,556,556,556,278,278,278,278,556,556,556,556,556,556,556,584,611,556,556,556,556,500,556,500};
    private static final short[] BOLD = {278,333,474,556,556,889,722,238,333,333,389,584,278,333,278,278,556,556,556,556,556,556,556,556,556,556,333,333,584,584,584,611,975,722,722,722,722,667,611,778,722,278,556,722,611,833,722,778,667,778,722,667,611,722,667,944,667,667,611,333,278,333,584,556,333,556,611,556,611,556,333,611,611,278,278,556,278,889,611,611,611,611,389,556,333,611,556,778,556,556,500,389,280,389,584,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,278,333,556,556,556,556,280,556,333,737,370,556,584,333,737,333,400,584,333,333,333,611,556,278,333,333,365,556,834,834,834,611,722,722,722,722,722,722,1000,722,667,667,667,667,278,278,278,278,722,722,778,778,778,778,778,584,778,722,722,722,722,667,667,611,556,556,556,556,556,556,889,556,556,556,556,556,278,278,278,278,611,611,611,611,611,611,611,584,611,611,611,611,611,556,611,556};

    /** Largura do texto em pontos. O texto deve ter passado por {@link #safe}. */
    public static float width(String s, float size, boolean bold) {
        if (s == null) return 0;
        short[] t = bold ? BOLD : REGULAR;
        float sum = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            int w;
            switch (c) {
                case '\u2013': w = 556; break;              // –
                case '\u2014': case '\u2026': case '\u2030': w = 1000; break; // — … ‰
                case '\u2022': w = 350; break;              // •
                case '\u2018': case '\u2019': w = bold ? 278 : 222; break;
                case '\u201C': case '\u201D': w = bold ? 500 : 333; break;
                case '\u20AC': w = 556; break;
                default: w = c >= 32 && c <= 255 ? t[c - 32] : 556;
            }
            sum += w < 0 ? 556 : w;
        }
        return sum * size / 1000f;
    }

    /** Troca o que a Helvetica padrão (WinAnsi) não tem por equivalentes legíveis; o resto vira '?'. */
    public static String safe(String s) {
        if (s == null) return "";
        s = s.replace("√", "raiz").replace("≤", "<=").replace("≥", ">=").replace("→", "->").replace("\u202F", " ")
                .replace("−", "-").replace("′", "'").replace("ⁿ", "^n").replace("∝", "~").replace("≈", "~").replace("Δ", "D")
                .replace("ρ", "rho").replace("·", "·").replace("\t", " ");
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 32 && c <= 126) || (c >= 160 && c <= 255 && REGULAR[c - 32] > 0)
                    || "\u2013\u2014\u2026\u2030\u2022\u2018\u2019\u201C\u201D\u20AC".indexOf(c) >= 0;
            b.append(ok ? c : '?');
        }
        return b.toString();
    }
}
