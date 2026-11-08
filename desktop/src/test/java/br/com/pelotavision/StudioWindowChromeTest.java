package br.com.pelotavision;

import org.junit.Test;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class StudioWindowChromeTest {
    @Test public void captionColorUsesWindowsColorRefOrder() {
        assertEquals(0x0020120b, StudioWindowChrome.colorRef(StudioTheme.BACKGROUND));
        assertEquals(0x00563412, StudioWindowChrome.colorRef(new Color(0x12, 0x34, 0x56, 0x78)));
    }

    @Test public void recordsUnsupportedCaptionWithoutReportingExactColor() {
        String status = StudioWindowChrome.applyAttributes((attribute, value) -> attribute == 35 ? -1 : 0, StudioTheme.BACKGROUND);
        assertTrue(status.contains("modo_escuro=aceito"));
        assertTrue(status.contains("cor_exata=nao suportada"));
    }

    @Test public void modernDarkModeAndCaptionColorAreRequested() {
        List<String> calls = new ArrayList<>();
        StudioWindowChrome.applyAttributes((attribute, value) -> {
            calls.add(attribute + ":" + value);
            return 0;
        }, StudioTheme.BACKGROUND);
        assertEquals(List.of("20:1", "35:2101771"), calls);
    }

    @Test public void retriesLegacyDarkModeAndStillRequestsColorOnFailure() {
        List<Integer> calls = new ArrayList<>();
        StudioWindowChrome.applyAttributes((attribute, value) -> {
            calls.add(attribute);
            return -1;
        }, StudioTheme.BACKGROUND);
        assertEquals(List.of(20, 19, 35), calls);
    }

    @Test public void nonnegativeHresultDoesNotTriggerLegacyFallback() {
        List<Integer> calls = new ArrayList<>();
        StudioWindowChrome.applyAttributes((attribute, value) -> {
            calls.add(attribute);
            return 1;
        }, Color.BLACK);
        assertEquals(List.of(20, 35), calls);
    }

    @Test public void recognizesOnlyWindowsOperatingSystems() {
        assertTrue(StudioWindowChrome.isWindows("Windows 10"));
        assertTrue(StudioWindowChrome.isWindows("Windows 11"));
        assertFalse(StudioWindowChrome.isWindows("Darwin"));
        assertFalse(StudioWindowChrome.isWindows(null));
    }

    @Test public void unavailableNativeApiDoesNotCauseFatalError() {
        StudioWindowChrome.tryNative(() -> { throw new UnsatisfiedLinkError("dwmapi ausente"); });
        StudioWindowChrome.tryNative(() -> { throw new IllegalStateException("peer indisponivel"); });
        StudioWindowChrome.tryNative(() -> { throw new Error("biblioteca nativa JNA incompativel"); });
    }

    @Test(expected = OutOfMemoryError.class)
    public void virtualMachineErrorsAreNotSuppressed() {
        StudioWindowChrome.tryNative(() -> { throw new OutOfMemoryError(); });
    }
}
