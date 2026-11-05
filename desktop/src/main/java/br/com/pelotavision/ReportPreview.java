package br.com.pelotavision;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;

/** Prévia de PDF exportado, independente dos cálculos e sem modificar o arquivo. */
final class ReportPreview extends JPanel {
    private final JLabel page = new JLabel("Salve uma análise ou abra um PDF para visualizá-lo aqui.", SwingConstants.CENTER);
    private final JLabel status = new JLabel("Nenhum relatório aberto");
    private final JButton previous = new JButton("‹ Anterior"), next = new JButton("Próxima ›");
    private File file;
    private int index, count, generation;

    ReportPreview() {
        super(new BorderLayout(8, 8));
        setBackground(StudioTheme.BACKGROUND);
        page.setForeground(StudioTheme.MUTED);
        JScrollPane scroll = new JScrollPane(page);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setBackground(StudioTheme.BACKGROUND);
        add(scroll, BorderLayout.CENTER);
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 4));
        JButton open = new JButton("Abrir PDF…");
        open.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Relatório PDF", "pdf"));
            if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) open(chooser.getSelectedFile());
        });
        previous.addActionListener(e -> render(index - 1));
        next.addActionListener(e -> render(index + 1));
        toolbar.add(open); toolbar.add(previous); toolbar.add(status); toolbar.add(next);
        add(toolbar, BorderLayout.NORTH);
        buttons(false);
    }

    /** Páginas da prévia carregada; 0 enquanto carrega ou após falha. */
    int pageCount() { return page.getIcon() == null ? 0 : count; }

    void open(File pdf) {
        page.setIcon(null);
        file = pdf; index = 0; count = 0;
        render(0);
    }

    private void buttons(boolean ready) {
        previous.setEnabled(ready && index > 0);
        next.setEnabled(ready && index + 1 < count);
    }

    private void render(int requested) {
        File selected = file;
        if (selected == null) return;
        int current = ++generation;
        buttons(false);
        status.setText("Carregando relatório…");
        new SwingWorker<Page, Void>() {
            @Override protected Page doInBackground() throws Exception {
                try (PDDocument document = Loader.loadPDF(selected)) {
                    int pages = document.getNumberOfPages();
                    if (requested < 0 || requested >= pages) throw new IllegalArgumentException("Página indisponível");
                    return new Page(new PDFRenderer(document).renderImageWithDPI(requested, 110), pages);
                }
            }
            @Override protected void done() {
                if (current != generation) return;
                try {
                    Page result = get();
                    index = requested; count = result.count;
                    page.setText(null); page.setIcon(new ImageIcon(result.image));
                    status.setText("Página " + (index + 1) + " de " + count);
                    buttons(true);
                } catch (Exception ex) {
                    page.setIcon(null); page.setText("Não foi possível abrir este PDF.");
                    status.setText("Falha ao abrir relatório"); buttons(false);
                }
            }
        }.execute();
    }

    private record Page(BufferedImage image, int count) {}
}
