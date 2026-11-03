package br.com.pelotavision;

import org.junit.Test;
import javax.swing.*;
import static org.junit.Assert.*;

public class StudioShellTest {
    @Test public void allSixStagesKeepControlsAndRejectInvalidStage() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JPanel[] panels = new JPanel[6];
            JButton[] actions = new JButton[6];
            for (int i = 0; i < 6; i++) { actions[i] = new JButton("acao " + i); panels[i] = StudioShell.stack(actions[i]); }
            StudioShell shell = new StudioShell(new JPanel(), new JPanel(), panels);
            for (int i = 0; i < 6; i++) { shell.select(i); assertEquals(i, shell.stage()); assertSame(panels[i], actions[i].getParent()); }
            try { shell.select(6); fail("etapa invalida aceita"); } catch (IllegalArgumentException expected) { }
            assertEquals(5, shell.stage());
        });
    }
    @Test public void requiresExactlySixContextPanels() {
        try { new StudioShell(new JPanel(), new JPanel(), new JPanel[0]); fail(); }
        catch (IllegalArgumentException expected) { }
    }
}
