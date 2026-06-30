package ch.kr.anton.oxy;

import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;

import javax.swing.BorderFactory;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;

/**
 * Settings form: Anton base URL, page size, lenient TLS, the target attribute name,
 * the id value template and the element→register mapping. Persisted via {@link Config}.
 */
final class SettingsDialog {

    private SettingsDialog() { }

    /** @return true if the user saved changes. */
    static boolean edit(Component parent, Config config) {
        JTextField urlField = new JTextField(config.getBaseUrl(), 28);
        JSpinner perPage = new JSpinner(new SpinnerNumberModel(config.getPerPage(), 5, 200, 5));
        JCheckBox insecure = new JCheckBox(
                "Accept self-signed / DDEV certificates (local only)", config.isInsecureTls());
        JTextField attrField = new JTextField(config.getAttribute(), 10);
        JTextField templateField = new JTextField(config.getTemplate(), 18);
        JTextArea mappingArea = new JTextArea(config.getMappingRaw(), 4, 24);
        mappingArea.setLineWrap(false);
        JScrollPane mappingScroll = new JScrollPane(mappingArea);
        mappingScroll.setPreferredSize(new Dimension(280, 90));

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;

        int y = 0;
        addRow(form, c, y++, "Anton base URL:", urlField);
        addRow(form, c, y++, "Hits per search:", perPage);
        addRow(form, c, y++, "Target attribute:", attrField);
        addRow(form, c, y++, "ID value template:", templateField);

        c.gridx = 0; c.gridy = y; c.weightx = 0; c.anchor = GridBagConstraints.NORTHWEST;
        c.fill = GridBagConstraints.NONE;
        form.add(new JLabel("Element → register:"), c);
        c.gridx = 1; c.weightx = 1; c.fill = GridBagConstraints.BOTH;
        form.add(mappingScroll, c);
        c.anchor = GridBagConstraints.WEST;
        y++;

        c.gridx = 1; c.gridy = y++; c.fill = GridBagConstraints.HORIZONTAL;
        form.add(insecure, c);

        c.gridx = 0; c.gridy = y; c.gridwidth = 2;
        form.add(new JLabel("<html><small>Template placeholders: "
                + "<code>{fullId}</code> <code>{slug}</code> <code>{register}</code> "
                + "<code>{id}</code>. &nbsp;Mapping: one <code>element=register</code> per line.<br/>"
                + "Search endpoints need no login. E.g. URL "
                + "<code>https://kr.anton.ch</code>.</small></html>"), c);

        int ok = JOptionPane.showConfirmDialog(parent, form, "anton-oxy settings",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (ok == JOptionPane.OK_OPTION) {
            config.setBaseUrl(urlField.getText().trim());
            config.setPerPage(((Number) perPage.getValue()).intValue());
            config.setInsecureTls(insecure.isSelected());
            config.setAttribute(attrField.getText().trim());
            config.setTemplate(templateField.getText().trim());
            config.setMappingRaw(mappingArea.getText());
            return true;
        }
        return false;
    }

    private static void addRow(JPanel form, GridBagConstraints c, int y, String label, Component field) {
        c.gridx = 0; c.gridy = y; c.weightx = 0; c.fill = GridBagConstraints.NONE;
        form.add(new JLabel(label), c);
        c.gridx = 1; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL;
        form.add(field, c);
    }
}
