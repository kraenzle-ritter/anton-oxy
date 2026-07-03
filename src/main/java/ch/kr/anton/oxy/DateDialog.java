package ch.kr.anton.oxy;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/**
 * Small modal dialog for wrapping a selection in a TEI {@code <date>} element. A date needs
 * no Anton lookup — only a normalised value — so this best-effort parses the selection into
 * an ISO 8601 value (see {@link #guessIso}) that the user confirms or corrects, and lets
 * them pick which date attribute carries it ({@code when} by default, or a range endpoint).
 */
final class DateDialog extends JDialog {

    private static final long serialVersionUID = 1L;

    /** TEI date attributes offered, {@code when} first (the common single-date case). */
    private static final String[] ATTRS = { "when", "from", "to", "notBefore", "notAfter" };

    private final JComboBox<String> attrCombo = new JComboBox<String>(ATTRS);
    private final JTextField valueField;
    private final JButton okButton = new JButton("Taggen");
    private boolean accepted;

    DateDialog(Window owner, String selectedText) {
        super(owner, "Datum taggen", ModalityType.APPLICATION_MODAL);

        valueField = new JTextField(guessIso(selectedText), 20);

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(12, 12, 6, 12));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.anchor = GridBagConstraints.WEST;

        c.gridx = 0; c.gridy = 0;
        form.add(new JLabel("Markierung:"), c);
        c.gridx = 1;
        form.add(new JLabel("„" + trim(selectedText) + "“"), c);

        c.gridx = 0; c.gridy = 1;
        form.add(new JLabel("Element:"), c);
        c.gridx = 1;
        form.add(new JLabel("<date>"), c);

        c.gridx = 0; c.gridy = 2;
        form.add(new JLabel("Attribut:"), c);
        c.gridx = 1; c.fill = GridBagConstraints.HORIZONTAL;
        form.add(attrCombo, c);

        c.gridx = 0; c.gridy = 3; c.fill = GridBagConstraints.NONE;
        form.add(new JLabel("Wert (ISO 8601):"), c);
        c.gridx = 1; c.fill = GridBagConstraints.HORIZONTAL;
        form.add(valueField, c);

        c.gridx = 1; c.gridy = 4; c.fill = GridBagConstraints.NONE;
        JLabel hint = new JLabel("z. B. 2026, 2026-07 oder 2026-07-03");
        hint.setEnabled(false);
        form.add(hint, c);

        JButton cancel = new JButton("Abbrechen");
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 6));
        buttons.add(cancel);
        buttons.add(okButton);

        setLayout(new BorderLayout());
        add(form, BorderLayout.CENTER);
        add(buttons, BorderLayout.SOUTH);

        okButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                if (!value().isEmpty()) {
                    accepted = true;
                    dispose();
                }
            }
        });
        cancel.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) { accepted = false; dispose(); }
        });
        valueField.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { updateOk(); }
            public void removeUpdate(DocumentEvent e) { updateOk(); }
            public void changedUpdate(DocumentEvent e) { updateOk(); }
        });

        updateOk();
        getRootPane().setDefaultButton(okButton);
        pack();
        setLocationRelativeTo(owner);

        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                valueField.requestFocusInWindow();
                valueField.selectAll();
            }
        });
    }

    /** @return true if the user confirmed (Taggen), false if cancelled. */
    boolean showDialog() {
        setVisible(true);
        return accepted;
    }

    String element() { return "date"; }

    String attr() { return (String) attrCombo.getSelectedItem(); }

    String value() { return valueField.getText().trim(); }

    private void updateOk() {
        okButton.setEnabled(!value().isEmpty());
    }

    private static String trim(String s) {
        if (s == null) {
            return "";
        }
        s = s.trim();
        return s.length() > 60 ? s.substring(0, 60).trim() + "…" : s;
    }

    private static String pad2(String n) {
        return n.length() == 1 ? "0" + n : n;
    }

    /**
     * Best-effort parse of a free-text date into an ISO 8601 value ({@code YYYY},
     * {@code YYYY-MM} or {@code YYYY-MM-DD}). Handles values already in ISO form, numeric
     * German dates ({@code 3.7.2026}, {@code 07.2026}) and German month names
     * ({@code 3. Juli 2026}, {@code Juli 2026}). Falls back to a lone four-digit year, or
     * returns {@code ""} when nothing recognisable is found (so the user fills it in).
     */
    static String guessIso(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();
        if (s.isEmpty()) {
            return "";
        }
        // Already ISO 8601 (year, year-month or full date).
        if (s.matches("\\d{4}(-\\d{2}(-\\d{2})?)?")) {
            return s;
        }
        // Numeric d.m.yyyy / dd.mm.yyyy
        Matcher m = Pattern.compile("^(\\d{1,2})\\.\\s*(\\d{1,2})\\.\\s*(\\d{4})$").matcher(s);
        if (m.matches()) {
            return m.group(3) + "-" + pad2(m.group(2)) + "-" + pad2(m.group(1));
        }
        // Numeric m.yyyy / mm.yyyy
        m = Pattern.compile("^(\\d{1,2})\\.\\s*(\\d{4})$").matcher(s);
        if (m.matches()) {
            return m.group(2) + "-" + pad2(m.group(1));
        }
        // German "[d]d. Monat yyyy" or "Monat yyyy"
        m = Pattern.compile("^(?:(\\d{1,2})\\.?\\s+)?([A-Za-zäöüÄÖÜ]+)\\s+(\\d{4})$").matcher(s);
        if (m.matches()) {
            String mon = germanMonth(m.group(2));
            if (mon != null) {
                String ym = m.group(3) + "-" + mon;
                return m.group(1) != null ? ym + "-" + pad2(m.group(1)) : ym;
            }
        }
        // Fallback: a lone four-digit year somewhere in the text.
        m = Pattern.compile("\\b(\\d{4})\\b").matcher(s);
        if (m.find()) {
            return m.group(1);
        }
        return "";
    }

    /** Two-digit month for a German month name (full or common abbreviation), else null. */
    private static String germanMonth(String name) {
        String n = name.toLowerCase();
        if (n.startsWith("jan")) return "01";
        if (n.startsWith("feb")) return "02";
        if (n.startsWith("mär") || n.startsWith("maer") || n.equals("mrz")) return "03";
        if (n.startsWith("apr")) return "04";
        if (n.equals("mai")) return "05";
        if (n.startsWith("jun")) return "06";
        if (n.startsWith("jul")) return "07";
        if (n.startsWith("aug")) return "08";
        if (n.startsWith("sep")) return "09";
        if (n.startsWith("okt")) return "10";
        if (n.startsWith("nov")) return "11";
        if (n.startsWith("dez")) return "12";
        return null;
    }
}
