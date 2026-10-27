package br.com.pelotavision;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.Component;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.File;
import java.util.Date;

/**
 * Diálogo de "Salvar foto + dados" (Windows 0.5.3): nome do teste, pasta-mãe e nome da pasta
 * desta gravação, sugerido com a data e a hora do momento (SaveNaming). Enquanto o usuário não
 * editar o nome da pasta, ele acompanha o nome do teste digitado.
 */
final class SaveDialog {
    /** Escolha confirmada: pasta final (ainda não criada) e nome do teste (null se vazio). */
    static final class Choice {
        final File folder; final String testName; final File parent;
        Choice(File folder, String testName, File parent) { this.folder = folder; this.testName = testName; this.parent = parent; }
    }

    private SaveDialog() {}

    static Choice show(Component owner, File defaultParent, String lastTestName) {
        Date now = new Date();
        JTextField test = new JTextField(lastTestName == null ? "" : lastTestName, 32);
        JTextField parent = new JTextField(defaultParent.getPath(), 32);
        JTextField folder = new JTextField(SaveNaming.suggestedFolder(now, lastTestName), 32);
        JLabel preview = new JLabel();
        boolean[] userEditedFolder = {false};
        boolean[] programmatic = {false};
        Date[] stamp = {now};

        Runnable refreshPreview = () -> {
            String name;
            try { name = SaveNaming.sanitizeFolder(folder.getText()); }
            catch (IllegalArgumentException e) { preview.setText("<html><font color='#c0392b'>" + e.getMessage() + "</font></html>"); return; }
            File target = new File(parent.getText().trim(), name);
            String note = target.exists() ? "  (já existe: será criada " + SaveNaming.unique(name, n -> new File(parent.getText().trim(), n).exists()) + ")" : "";
            preview.setText("<html>Arquivos em: <b>" + escape(target.getPath()) + "</b>" + escape(note) + "</html>");
        };
        Runnable suggest = () -> {
            programmatic[0] = true;
            folder.setText(SaveNaming.suggestedFolder(stamp[0], test.getText()));
            programmatic[0] = false;
            refreshPreview.run();
        };
        test.getDocument().addDocumentListener(listener(() -> { if (!userEditedFolder[0]) suggest.run(); }));
        folder.getDocument().addDocumentListener(listener(() -> { if (!programmatic[0]) userEditedFolder[0] = true; refreshPreview.run(); }));
        parent.getDocument().addDocumentListener(listener(refreshPreview));

        JButton browse = new JButton("Escolher…");
        browse.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            chooser.setDialogTitle("Pasta onde as gravações são criadas");
            File current = new File(parent.getText().trim());
            chooser.setCurrentDirectory(current.isDirectory() ? current : current.getParentFile());
            chooser.setSelectedFile(current);
            if (chooser.showDialog(SwingUtilities.getWindowAncestor(browse), "Usar esta pasta") == JFileChooser.APPROVE_OPTION)
                parent.setText(chooser.getSelectedFile().getPath());
        });
        JButton nowButton = new JButton("Hora atual");
        nowButton.setToolTipText("Refaz a sugestão com a data e a hora deste instante");
        nowButton.addActionListener(e -> { stamp[0] = new Date(); userEditedFolder[0] = false; suggest.run(); });

        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(BorderFactory.createEmptyBorder(4, 2, 4, 2));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4); c.anchor = GridBagConstraints.WEST; c.fill = GridBagConstraints.HORIZONTAL;
        row(p, c, 0, "Nome do teste", test, null);
        JLabel hint = new JLabel("<html><font color='#7f8c8d'>Opcional. Ex.: Disco 3 · umidade 9,2% · amostra 2. Vai para o PDF, o JSON e o nome da pasta.</font></html>");
        c.gridx = 1; c.gridy = 1; c.gridwidth = 2; p.add(hint, c); c.gridwidth = 1;
        row(p, c, 2, "Salvar dentro de", parent, browse);
        row(p, c, 3, "Pasta desta gravação", folder, nowButton);
        c.gridx = 0; c.gridy = 4; c.gridwidth = 3; p.add(preview, c);
        refreshPreview.run();
        SwingUtilities.invokeLater(() -> { test.requestFocusInWindow(); test.selectAll(); });

        while (true) {
            int answer = JOptionPane.showConfirmDialog(owner, p, "Salvar foto + dados", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (answer != JOptionPane.OK_OPTION) return null;
            String root = parent.getText().trim();
            if (root.isEmpty()) { JOptionPane.showMessageDialog(owner, "Informe a pasta onde salvar.", "Salvar", JOptionPane.WARNING_MESSAGE); continue; }
            String name;
            try { name = SaveNaming.sanitizeFolder(folder.getText()); }
            catch (IllegalArgumentException ex) { JOptionPane.showMessageDialog(owner, ex.getMessage(), "Salvar", JOptionPane.WARNING_MESSAGE); continue; }
            File rootDir = new File(root);
            name = SaveNaming.unique(name, n -> new File(rootDir, n).exists());
            return new Choice(new File(rootDir, name), SaveNaming.cleanTestName(test.getText()), rootDir);
        }
    }

    private static void row(JPanel p, GridBagConstraints c, int y, String label, Component field, Component extra) {
        c.gridy = y; c.gridx = 0; c.weightx = 0; p.add(new JLabel(label), c);
        c.gridx = 1; c.weightx = 1; p.add(field, c);
        c.gridx = 2; c.weightx = 0; if (extra != null) p.add(extra, c);
    }

    private static DocumentListener listener(Runnable r) {
        return new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { r.run(); }
            public void removeUpdate(DocumentEvent e) { r.run(); }
            public void changedUpdate(DocumentEvent e) { r.run(); }
        };
    }

    private static String escape(String s) { return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }
}
