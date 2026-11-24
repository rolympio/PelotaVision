package br.com.pelotavision;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/** Seis etapas de navegacao. Nao modifica nem duplica a logica cientifica. */
final class StudioShell extends JPanel {
    static final String[] STAGES = {"Aquisição", "Calibração", "Segmentação", "Análise", "Resultados", "Relatório"};
    private final CardLayout cards = new CardLayout();
    private final JPanel contexts = new JPanel(cards);
    private final List<JToggleButton> buttons = new ArrayList<>();
    private final JLabel contextTitle = new JLabel();
    private final JSplitPane controlsSplit;
    private final JSplitPane resultsSplit;
    private final CardLayout viewerCards = new CardLayout();
    private final JPanel viewerHost = new JPanel(viewerCards);
    private final JComponent inspector;
    private boolean inspectorWanted = true;
    private int stage;
    static final int CONTROLS_WIDTH = 290, INSPECTOR_WIDTH = 370;

    StudioShell(JComponent viewer, JComponent inspector, JPanel[] panels) {
        this(viewer, inspector, panels, null);
    }

    StudioShell(JComponent viewer, JComponent inspector, JPanel[] panels, JComponent reportViewer) {
        super(new BorderLayout(12, 12));
        if (panels.length != STAGES.length) throw new IllegalArgumentException("Esperadas seis etapas");
        this.inspector = inspector;
        setBorder(BorderFactory.createEmptyBorder(12, 14, 8, 14));
        setBackground(StudioTheme.BACKGROUND);
        JPanel header = new JPanel(new BorderLayout(12, 12));
        header.setBackground(StudioTheme.BACKGROUND);
        JLabel brand = new JLabel("PELOTAVISION STUDIO");
        brand.setFont(new Font("Segoe UI", Font.BOLD, 21));
        brand.setForeground(StudioTheme.TEXT);
        List<Image> icons = MainFrame.appIcons();
        if (!icons.isEmpty()) brand.setIcon(new ImageIcon(icons.get(icons.size() - 1).getScaledInstance(36, 36, Image.SCALE_SMOOTH)));
        brand.setIconTextGap(10);
        JLabel badge = new JLabel("P&D · medidas 2D · IA experimental");
        badge.setForeground(new Color(245, 184, 75));
        JPanel identity = new JPanel(new BorderLayout());
        identity.setBackground(StudioTheme.BACKGROUND);
        identity.add(brand, BorderLayout.WEST);
        identity.add(badge, BorderLayout.EAST);
        header.add(identity, BorderLayout.NORTH);
        JPanel steps = new JPanel(new GridLayout(1, 6, 6, 0));
        steps.setBackground(StudioTheme.BACKGROUND);
        ButtonGroup group = new ButtonGroup();
        for (int i = 0; i < STAGES.length; i++) {
            final int index = i;
            JToggleButton b = new JToggleButton((i + 1) + "  " + STAGES[i]);
            b.setName("studio-stage-" + i);
            b.setPreferredSize(new Dimension(130, 42));
            b.addActionListener(e -> select(index));
            group.add(b); buttons.add(b); steps.add(b);
            JScrollPane scroll = new JScrollPane(panels[i]);
            scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
            scroll.setBorder(BorderFactory.createEmptyBorder());
            scroll.getVerticalScrollBar().setUnitIncrement(20);
            contexts.add(scroll, Integer.toString(i));
        }
        header.add(steps, BorderLayout.CENTER);
        add(header, BorderLayout.NORTH);

        JPanel controlPanel = new JPanel(new BorderLayout(0, 10));
        controlPanel.setMinimumSize(new Dimension(260, 150));
        contextTitle.setFont(new Font("Segoe UI", Font.BOLD, 17));
        controlPanel.add(contextTitle, BorderLayout.NORTH);
        controlPanel.add(contexts, BorderLayout.CENTER);
        viewerHost.add(viewer, "imagem");
        if (reportViewer != null) viewerHost.add(reportViewer, "relatorio");
        resultsSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, viewerHost, inspector);
        resultsSplit.setResizeWeight(1);
        resultsSplit.setDividerSize(7);
        resultsSplit.setBorder(BorderFactory.createEmptyBorder());
        resultsSplit.addComponentListener(new java.awt.event.ComponentAdapter() {
            private boolean positioned;
            @Override public void componentResized(java.awt.event.ComponentEvent e) {
                if (!positioned && resultsSplit.getWidth() > 400) {
                    if (inspector.isVisible()) resultsSplit.setDividerLocation(Math.max(200, resultsSplit.getWidth() - INSPECTOR_WIDTH));
                    positioned = true;
                }
            }
        });
        inspector.setPreferredSize(new Dimension(INSPECTOR_WIDTH, 620));
        inspector.setMinimumSize(new Dimension(0, 0));
        controlsSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, controlPanel, resultsSplit);
        controlsSplit.setDividerLocation(CONTROLS_WIDTH);
        controlsSplit.setDividerSize(7);
        controlsSplit.setResizeWeight(0);
        controlsSplit.setBorder(BorderFactory.createEmptyBorder());
        add(controlsSplit, BorderLayout.CENTER);

        JPanel options = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        JCheckBox showControls = new JCheckBox("Controles", true);
        JCheckBox showResults = new JCheckBox("Indicadores e gráficos", true);
        showControls.addActionListener(e -> {
            controlPanel.setVisible(showControls.isSelected());
            controlsSplit.setDividerLocation(showControls.isSelected() ? CONTROLS_WIDTH : 0);
        });
        showResults.addActionListener(e -> {
            inspectorWanted = showResults.isSelected();
            updateInspector();
        });
        options.add(showControls); options.add(showResults);
        options.add(new JLabel("Roda: zoom · arraste: mover · duplo clique: ajustar"));
        add(options, BorderLayout.SOUTH);
        select(0);
    }

    void select(int index) {
        if (index < 0 || index >= STAGES.length) throw new IllegalArgumentException("Etapa invalida");
        stage = index;
        buttons.get(index).setSelected(true);
        contextTitle.setText((index + 1) + ". " + STAGES[index]);
        cards.show(contexts, Integer.toString(index));
        viewerCards.show(viewerHost, reportStage() ? "relatorio" : "imagem");
        updateInspector();
    }

    /** No relatório, o PDF usa a área central e os gráficos laterais ficam recolhidos. */
    boolean reportStage() { return stage == STAGES.length - 1 && viewerHost.getComponentCount() > 1; }

    boolean inspectorVisible() { return inspector.isVisible(); }

    private void updateInspector() {
        boolean visible = inspectorWanted && !reportStage();
        inspector.setVisible(visible);
        if (visible) resultsSplit.setDividerLocation(Math.max(200, resultsSplit.getWidth() - INSPECTOR_WIDTH));
        else resultsSplit.setDividerLocation(1.0);
        resultsSplit.revalidate();
    }

    int stage() { return stage; }

    static JPanel stack(Component... components) {
        // Acompanha a largura da coluna: conteúdo longo não empurra botões para fora da tela.
        JPanel p = new StudioScrollablePanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBorder(BorderFactory.createEmptyBorder(4, 4, 8, 8));
        for (Component c : components) {
            if (c instanceof JComponent jc) {
                jc.setAlignmentX(Component.LEFT_ALIGNMENT);
                Dimension preferred = jc.getPreferredSize();
                jc.setMaximumSize(new Dimension(Integer.MAX_VALUE, Math.max(32, preferred.height)));
                if (jc instanceof JScrollPane) jc.setPreferredSize(new Dimension(160, preferred.height));
                else if (!(jc instanceof JLabel)) jc.setMinimumSize(new Dimension(0, preferred.height));
            }
            p.add(c); p.add(Box.createVerticalStrut(8));
        }
        return p;
    }

    /** Cartão lateral padronizado: título discreto, borda fina e largura total do painel. */
    static JPanel card(String title, JComponent content) {
        JPanel card = new JPanel(new BorderLayout(0, 6)) {
            // Altura acompanha o conteúdo atual (ex.: texto da escala que muda após calibrar).
            @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); }
        };
        card.setBackground(StudioTheme.SURFACE);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 1, 1, 1, StudioTheme.BORDER),
                BorderFactory.createEmptyBorder(8, 10, 10, 10)));
        JLabel label = new JLabel(title.toUpperCase(java.util.Locale.ROOT));
        label.setFont(new Font("Segoe UI", Font.BOLD, 11));
        label.setForeground(StudioTheme.MUTED);
        card.add(label, BorderLayout.NORTH);
        card.add(content, BorderLayout.CENTER);
        card.setAlignmentX(Component.LEFT_ALIGNMENT);
        return card;
    }

    static JLabel note(String text) {
        JLabel l = new JLabel("<html><body style='width:205px'>" + text + "</body></html>");
        l.setForeground(StudioTheme.MUTED);
        return l;
    }
}
