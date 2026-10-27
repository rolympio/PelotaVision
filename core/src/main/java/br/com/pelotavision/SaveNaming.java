package br.com.pelotavision;

import java.text.Normalizer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Nome do teste e da pasta de cada gravação "Salvar foto + dados" (comum a Android e Windows).
 *
 * <p>Pasta sugerida = data e hora do momento de salvar, mais o nome do teste quando informado:
 * {@code 2026-10-27_08-40-12_Disco3_umidade9}. A data vem primeiro para a ordem alfabética
 * coincidir com a cronológica no Explorer e na Galeria. O nome do teste é gravado sem alteração
 * no JSON e no PDF; só a versão usada como nome de pasta é saneada.</p>
 */
public final class SaveNaming {
    private SaveNaming() {}

    /** Limite do trecho do teste no nome da pasta (o nome completo continua no JSON/PDF). */
    public static final int MAX_TEST_PART = 60;
    /** Limite do nome digitado do teste. */
    public static final int MAX_TEST_NAME = 120;

    private static final Pattern FORBIDDEN = Pattern.compile("[<>:\"/\\\\|?*\\p{Cntrl}]");
    private static final Pattern SPACES = Pattern.compile("\\s+");
    private static final Pattern REPEATED = Pattern.compile("([_\\-.]){2,}");
    private static final Pattern RESERVED = Pattern.compile("(?i)^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\\..*)?$");

    /** Data e hora no formato da pasta: {@code yyyy-MM-dd_HH-mm-ss}. */
    public static String timestamp(Date when) {
        return new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.ROOT).format(when);
    }

    /** Nome do teste como o usuário digitou, só sem espaços nas pontas e com o limite de tamanho; vazio vira null. */
    public static String cleanTestName(String raw) {
        if (raw == null) return null;
        String t = SPACES.matcher(raw.trim()).replaceAll(" ");
        if (t.isEmpty()) return null;
        return t.length() > MAX_TEST_NAME ? t.substring(0, MAX_TEST_NAME).trim() : t;
    }

    /**
     * Trecho do nome do teste seguro para pasta no Windows e no Android: remove caracteres
     * proibidos e acentos (evita problemas em rede, zip e scripts), troca espaços por "_".
     * Retorna "" se nada sobrar.
     */
    public static String folderPart(String testName) {
        String t = cleanTestName(testName);
        if (t == null) return "";
        t = Normalizer.normalize(t, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        t = FORBIDDEN.matcher(t).replaceAll("-");
        t = SPACES.matcher(t).replaceAll("_");
        t = t.replaceAll("[^A-Za-z0-9_\\-.()+,=@#&]", "-");
        t = REPEATED.matcher(t).replaceAll("$1");
        t = trimEdges(t);
        if (t.length() > MAX_TEST_PART) t = trimEdges(t.substring(0, MAX_TEST_PART));
        if (RESERVED.matcher(t).matches()) t = "_" + t;
        return t;
    }

    /** Pasta sugerida para este momento: data_hora[_teste]. */
    public static String suggestedFolder(Date when, String testName) {
        String part = folderPart(testName);
        return part.isEmpty() ? timestamp(when) : timestamp(when) + "_" + part;
    }

    /**
     * Valida e saneia o nome de pasta editado pelo usuário. Lança IllegalArgumentException com
     * mensagem para o diálogo quando não sobra nome utilizável.
     */
    public static String sanitizeFolder(String raw) {
        if (raw == null || raw.trim().isEmpty()) throw new IllegalArgumentException("Informe o nome da pasta.");
        String t = FORBIDDEN.matcher(raw.trim()).replaceAll("-");
        t = SPACES.matcher(t).replaceAll("_");
        t = trimEdges(t);
        if (t.length() > 120) t = trimEdges(t.substring(0, 120));
        if (t.isEmpty() || t.equals(".") || t.equals("..")) throw new IllegalArgumentException("Nome de pasta inválido.");
        if (RESERVED.matcher(t).matches()) t = "_" + t;
        return t;
    }

    /** Sufixo para não sobrescrever: nome, nome_2, nome_3… conforme {@code exists}. */
    public static String unique(String folder, java.util.function.Predicate<String> exists) {
        if (!exists.test(folder)) return folder;
        for (int i = 2; i < 1000; i++) {
            String candidate = folder + "_" + i;
            if (!exists.test(candidate)) return candidate;
        }
        throw new IllegalStateException("Muitas pastas com o nome " + folder);
    }

    private static String trimEdges(String t) {
        int a = 0, b = t.length();
        while (a < b && "._- ".indexOf(t.charAt(a)) >= 0) a++;
        while (b > a && "._- ".indexOf(t.charAt(b - 1)) >= 0) b--;
        return t.substring(a, b);
    }
}
