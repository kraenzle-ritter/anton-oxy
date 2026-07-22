package ch.kr.anton.oxy;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/**
 * Modal dialog that searches Anton live (one HTTP request per query, debounced) and
 * lets the editor pick an actor, place or keyword. The chosen {@link AntonEntity#fullId}
 * is what gets written into the TEI reference attribute.
 *
 * <p>Two modes:</p>
 * <ul>
 *   <li><b>Edit</b> — the caret sits in a mapped element; the top combo switches the
 *       search register, the reference is written onto the existing element.</li>
 *   <li><b>Wrap</b> — a bare text selection is being tagged; the top combo picks which
 *       element to wrap the selection in ({@link #chosenElement()} /
 *       {@link #chosenAttr()}), and the register follows that element.</li>
 * </ul>
 *
 * <p>"Einfügen &amp; weiter" ({@link #wantsNext()}) lets the caller jump to the next
 * occurrence of the tagged text and tag it too, for fast serial tagging.</p>
 */
final class SearchDialog extends JDialog {

    private static final long serialVersionUID = 1L;

    private final AntonClient client;
    private final Config config;
    private final boolean wrapMode;

    private final JComboBox<Choice> choiceCombo;
    private final JTextField searchField;
    private final DefaultListModel<AntonEntity> model = new DefaultListModel<AntonEntity>();
    private final JList<AntonEntity> resultList = new JList<AntonEntity>(model);
    private final JLabel status = new JLabel(" ");
    private final JButton okButton = new JButton("Einfügen");
    private final JButton nextButton = new JButton("Einfügen & weiter");
    private final JButton skipButton = new JButton("Überspringen");

    private final Timer debounce;
    private SwingWorker<List<AntonEntity>, Void> running;
    private long querySeq = 0;

    private AntonEntity result;      // null = cancelled or skipped
    private Choice chosenChoice;     // the combo entry active when accepted
    private boolean andNext;         // true = user asked to continue with the next occurrence
    private boolean skipped;         // true = user asked to skip this occurrence and continue

    /**
     * @param wrapMode      true to wrap a selection (element chooser), false to edit an existing element
     * @param initialRegister register to preselect in edit mode (ignored in wrap mode)
     * @param elementName   the located element name (edit mode) — shown in the context line
     * @param prefill       text to seed the search field with
     * @param currentRef    existing reference value to show (edit mode), may be null
     * @param preferElement element to preselect in wrap mode (e.g. the previously tagged one), may be null
     */
    SearchDialog(Window owner, AntonClient client, Config config,
                 boolean wrapMode, String initialRegister, String elementName,
                 String prefill, String currentRef, String preferElement) {
        super(owner, wrapMode ? "Anton-Referenz – Markierung taggen" : "Anton-Referenz einfügen",
                ModalityType.APPLICATION_MODAL);
        this.client = client;
        this.config = config;
        this.wrapMode = wrapMode;

        choiceCombo = new JComboBox<Choice>(
                wrapMode ? buildElementChoices(config) : buildRegisterChoices(config));
        if (wrapMode) {
            selectElement(preferElement);
        } else {
            selectRegister(initialRegister);
        }

        searchField = new JTextField(prefill == null ? "" : prefill, 32);

        // --- north: context + register/element + search field ---
        JPanel north = new JPanel(new BorderLayout(6, 6));
        north.setBorder(BorderFactory.createEmptyBorder(10, 10, 4, 10));
        north.add(new JLabel(contextHtml(wrapMode, elementName, prefill, currentRef)), BorderLayout.NORTH);

        JPanel row = new JPanel(new BorderLayout(6, 0));
        if (wrapMode) {
            JPanel west = new JPanel(new BorderLayout(6, 0));
            west.add(new JLabel("Als Element: "), BorderLayout.WEST);
            west.add(choiceCombo, BorderLayout.CENTER);
            row.add(west, BorderLayout.WEST);
        } else {
            row.add(choiceCombo, BorderLayout.WEST);
        }
        row.add(searchField, BorderLayout.CENTER);
        north.add(row, BorderLayout.SOUTH);

        // --- center: results ---
        resultList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        resultList.setVisibleRowCount(12);
        JScrollPane scroll = new JScrollPane(resultList);
        scroll.setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 10));

        // --- south: status + buttons ---
        JButton settings = new JButton("Einstellungen…");
        JButton cancel = new JButton("Abbrechen");
        nextButton.setToolTipText("Einfügen und danach das nächste Vorkommen dieses Textes markieren (nur Textansicht)");
        skipButton.setToolTipText("Dieses Vorkommen nicht taggen und zum nächsten Vorkommen springen (nur Textansicht)");

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 6));
        buttons.add(settings);
        buttons.add(cancel);
        buttons.add(skipButton);
        buttons.add(nextButton);
        buttons.add(okButton);

        JPanel south = new JPanel(new BorderLayout());
        status.setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 6));
        south.add(status, BorderLayout.CENTER);
        south.add(buttons, BorderLayout.EAST);

        setLayout(new BorderLayout());
        add(north, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
        add(south, BorderLayout.SOUTH);

        okButton.setEnabled(false);
        nextButton.setEnabled(false);

        // --- behaviour ---
        debounce = new Timer(300, new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                runSearch();
            }
        });
        debounce.setRepeats(false);

        searchField.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { debounce.restart(); }
            public void removeUpdate(DocumentEvent e) { debounce.restart(); }
            public void changedUpdate(DocumentEvent e) { debounce.restart(); }
        });

        choiceCombo.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) { runSearch(); }
        });

        searchField.addKeyListener(new KeyAdapter() {
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER) {
                    debounce.stop();
                    runSearch();
                    if (!model.isEmpty()) {
                        resultList.requestFocusInWindow();
                        resultList.setSelectedIndex(0);
                    }
                } else if (e.getKeyCode() == KeyEvent.VK_DOWN && !model.isEmpty()) {
                    resultList.requestFocusInWindow();
                    resultList.setSelectedIndex(0);
                }
            }
        });

        resultList.addListSelectionListener(e -> updateButtons());

        resultList.addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && resultList.getSelectedValue() != null) {
                    accept(false);
                }
            }
        });

        resultList.addKeyListener(new KeyAdapter() {
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER && resultList.getSelectedValue() != null) {
                    accept(false);
                }
            }
        });

        okButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) { accept(false); }
        });
        nextButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) { accept(true); }
        });
        skipButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                result = null;
                skipped = true;
                chosenChoice = (Choice) choiceCombo.getSelectedItem();
                dispose();
            }
        });
        cancel.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) { result = null; dispose(); }
        });
        settings.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                if (SettingsDialog.edit(SearchDialog.this, config)) {
                    runSearch();
                }
            }
        });

        getRootPane().setDefaultButton(okButton);
        setMinimumSize(new Dimension(580, 440));
        pack();
        setLocationRelativeTo(owner);

        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                searchField.requestFocusInWindow();
                searchField.selectAll();
                if (searchField.getText().trim().length() > 0) {
                    runSearch();
                }
            }
        });
    }

    /** @return the chosen entity, or null if cancelled. */
    AntonEntity showDialog() {
        setVisible(true);
        return result;
    }

    /**
     * The element implied by the register chosen in the dialog (e.g. persName / placeName /
     * bibl). Set in both wrap and edit mode — in edit mode it drives retagging an existing
     * element when the user picks an entity from a different register. May be null.
     */
    String chosenElement() {
        return chosenChoice != null ? chosenChoice.element : null;
    }

    /** The attribute that receives the reference for the chosen register, or null. */
    String chosenAttr() {
        return chosenChoice != null ? chosenChoice.attr : null;
    }

    /** True if the editor asked to continue with the next occurrence after inserting. */
    boolean wantsNext() {
        return andNext;
    }

    /**
     * True if the user asked to <em>skip</em> this occurrence (no reference written) and
     * jump to the next one. Mutually exclusive with a chosen entity from {@link #showDialog}.
     */
    boolean wantsSkip() {
        return skipped;
    }

    private void accept(boolean next) {
        AntonEntity sel = resultList.getSelectedValue();
        if (sel != null) {
            result = sel;
            andNext = next;
            chosenChoice = (Choice) choiceCombo.getSelectedItem();
            dispose();
        }
    }

    private void updateButtons() {
        boolean has = resultList.getSelectedValue() != null;
        okButton.setEnabled(has);
        nextButton.setEnabled(has);
    }

    private String currentRegister() {
        Choice c = (Choice) choiceCombo.getSelectedItem();
        return c == null ? "actors" : c.register;
    }

    private static String contextHtml(boolean wrapMode, String elementName, String prefill, String currentRef) {
        StringBuilder ctx = new StringBuilder("<html>");
        if (wrapMode) {
            ctx.append("Markierung <b>umschließen</b>");
            if (prefill != null && !prefill.trim().isEmpty()) {
                ctx.append(" &nbsp;·&nbsp; „<i>").append(escape(prefill.trim())).append("</i>“");
            }
        } else {
            ctx.append("Element <b>&lt;").append(escape(elementName)).append("&gt;</b>");
            if (currentRef != null && !currentRef.isEmpty()) {
                ctx.append(" &nbsp;·&nbsp; aktuell: <code>").append(escape(currentRef)).append("</code>");
            }
        }
        ctx.append("</html>");
        return ctx.toString();
    }

    /** One combo entry per distinct register, labelled with the elements that map to it. */
    private static Choice[] buildRegisterChoices(Config config) {
        java.util.Map<String, String> mapping = config.getMapping();
        java.util.List<Choice> items = new java.util.ArrayList<Choice>();
        for (String reg : config.getRegisters()) {
            java.util.List<String> tags = new java.util.ArrayList<String>();
            for (java.util.Map.Entry<String, String> e : mapping.entrySet()) {
                if (e.getValue().equals(reg)) {
                    tags.add(e.getKey());
                }
            }
            items.add(new Choice(reg + " (" + String.join(" / ", tags) + ")", reg, null, null));
        }
        if (items.isEmpty()) {
            items.add(new Choice("actors", "actors", null, null));
        }
        return items.toArray(new Choice[0]);
    }

    /** One combo entry per mapped element, so a selection can be wrapped in it. */
    private static Choice[] buildElementChoices(Config config) {
        java.util.List<Choice> items = new java.util.ArrayList<Choice>();
        for (java.util.Map.Entry<String, Config.Target> e : config.getTargets().entrySet()) {
            String el = e.getKey();
            Config.Target t = e.getValue();
            String label = "<" + el + " " + t.attribute + "> → " + t.register;
            items.add(new Choice(label, t.register, el, t.attribute));
        }
        if (items.isEmpty()) {
            items.add(new Choice("<persName ref> → actors", "actors", "persName", "ref"));
        }
        return items.toArray(new Choice[0]);
    }

    private void selectRegister(String register) {
        for (int i = 0; i < choiceCombo.getItemCount(); i++) {
            if (choiceCombo.getItemAt(i).register.equals(register)) {
                choiceCombo.setSelectedIndex(i);
                return;
            }
        }
    }

    private void selectElement(String element) {
        if (element == null) {
            return;
        }
        for (int i = 0; i < choiceCombo.getItemCount(); i++) {
            if (element.equals(choiceCombo.getItemAt(i).element)) {
                choiceCombo.setSelectedIndex(i);
                return;
            }
        }
    }

    private void runSearch() {
        final String query = searchField.getText().trim();
        final String register = currentRegister();
        if (running != null) {
            running.cancel(true);
        }
        if (query.isEmpty()) {
            model.clear();
            status.setText("Suchbegriff eingeben…");
            updateButtons();
            return;
        }
        final long seq = ++querySeq;
        status.setText("Suche „" + query + "“ …");
        running = new SwingWorker<List<AntonEntity>, Void>() {
            protected List<AntonEntity> doInBackground() throws Exception {
                return client.search(register, query);
            }
            protected void done() {
                if (seq != querySeq || isCancelled()) {
                    return; // a newer query superseded this one
                }
                try {
                    List<AntonEntity> hits = get();
                    model.clear();
                    for (AntonEntity e : hits) {
                        model.addElement(e);
                    }
                    if (hits.isEmpty()) {
                        status.setText("Keine Treffer für „" + query + "“.");
                    } else {
                        status.setText(hits.size() + " Treffer"
                                + (hits.size() >= config.getPerPage() ? " (ggf. mehr — Suche verfeinern)" : ""));
                        resultList.setSelectedIndex(0);
                    }
                    updateButtons();
                } catch (Exception ex) {
                    model.clear();
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    status.setText("<html><font color='red'>Fehler: "
                            + escape(cause.getMessage()) + "</font></html>");
                    updateButtons();
                }
            }
        };
        running.execute();
    }

    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
     * Combo entry. In edit mode only {@link #register} matters. In wrap mode it also
     * carries the {@link #element} to wrap the selection in and the {@link #attr} that
     * receives the reference.
     */
    private static final class Choice {
        final String label;
        final String register;
        final String element; // wrap mode only, else null
        final String attr;    // wrap mode only, else null
        Choice(String label, String register, String element, String attr) {
            this.label = label;
            this.register = register;
            this.element = element;
            this.attr = attr;
        }
        public String toString() { return label; }
    }
}
