package br.com.pelotavision;

import static org.junit.Assert.*;

import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

/** 0.5.3 / 0.15.3: pasta de cada gravação = data e hora do momento + nome do teste (núcleo, sem OpenCV). */
public class SaveNamingTest {
    private static Date at(int y, int mo, int d, int h, int mi, int s) {
        Calendar c = Calendar.getInstance(); c.clear(); c.set(y, mo - 1, d, h, mi, s); return c.getTime();
    }

    @Test public void folderStartsWithSortableTimestamp() {
        Date when = at(2026, 10, 27, 8, 40, 5);
        assertEquals("2026-10-27_08-40-05", SaveNaming.suggestedFolder(when, null));
        assertEquals("2026-10-27_08-40-05", SaveNaming.suggestedFolder(when, "   "));
        assertEquals("2026-10-27_08-40-05_Disco3_umidade", SaveNaming.suggestedFolder(when, "Disco3 umidade"));
    }

    @Test public void testNameIsMadeSafeForWindowsAndAndroidFolders() {
        assertEquals("Disco_3_umidade_9,2_amostra_2", SaveNaming.folderPart("Disco 3 · umidade 9,2% amostra 2"));
        assertEquals("Pelotizacao_U8", SaveNaming.folderPart("Pelotização U8"));
        assertEquals("a-b-c-d", SaveNaming.folderPart("a/b\\c:d"));
        assertEquals("teste", SaveNaming.folderPart("  ..teste.. "));
        assertEquals("_CON", SaveNaming.folderPart("CON"));
        assertEquals("", SaveNaming.folderPart("???"));
        assertTrue(SaveNaming.folderPart("x".repeat(200)).length() <= SaveNaming.MAX_TEST_PART);
    }

    @Test public void testNameKeptAsTypedForReports() {
        assertEquals("Disco 3 · umidade 9,2%", SaveNaming.cleanTestName("  Disco 3   · umidade 9,2%  "));
        assertNull(SaveNaming.cleanTestName("  "));
        assertNull(SaveNaming.cleanTestName(null));
    }

    @Test public void editedFolderIsValidatedAndNeverOverwritten() {
        assertEquals("minha_pasta", SaveNaming.sanitizeFolder(" minha pasta "));
        assertEquals("a-b", SaveNaming.sanitizeFolder("a:b"));
        try { SaveNaming.sanitizeFolder("  "); fail(); } catch (IllegalArgumentException expected) {}
        try { SaveNaming.sanitizeFolder(".."); fail(); } catch (IllegalArgumentException expected) {}
        Set<String> existing = new HashSet<>(); existing.add("p"); existing.add("p_2");
        assertEquals("p_3", SaveNaming.unique("p", existing::contains));
        assertEquals("q", SaveNaming.unique("q", existing::contains));
    }
}
