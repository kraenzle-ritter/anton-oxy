package ch.kr.anton.oxy;

import java.awt.KeyboardFocusManager;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.KeyStroke;

import ro.sync.exml.plugin.workspace.WorkspaceAccessPluginExtension;
import ro.sync.exml.workspace.api.PluginWorkspace;
import ro.sync.exml.workspace.api.editor.WSEditor;
import ro.sync.exml.workspace.api.standalone.MenuBarCustomizer;
import ro.sync.exml.workspace.api.standalone.StandalonePluginWorkspace;
import ro.sync.exml.workspace.api.standalone.ToolbarComponentsCustomizer;
import ro.sync.exml.workspace.api.standalone.ToolbarInfo;

/**
 * Adds the "Anton-Referenz einfügen" action to a toolbar and to an "Anton" menu.
 *
 * <p>Workflow: put the caret inside a {@code persName}, {@code orgName} or
 * {@code placeName} (Text <i>or</i> Author mode), trigger the action, search Anton
 * live, pick an entry, and the plugin writes {@code ref="{slug}-actors-…"} /
 * {@code ref="{slug}-places-…"} onto that element.</p>
 */
public class AntonOxyPluginExtension
        implements WorkspaceAccessPluginExtension, ToolbarComponentsCustomizer, MenuBarCustomizer {

    static final String TOOLBAR_ID = "AntonOxyToolbarID";
    private static final String ACTION_LABEL = "Anton-Referenz einfügen";

    private StandalonePluginWorkspace workspace;
    private Config config;
    private AntonClient client;

    public void applicationStarted(StandalonePluginWorkspace pluginWorkspaceAccess) {
        this.workspace = pluginWorkspaceAccess;
        this.config = new Config(pluginWorkspaceAccess.getOptionsStorage());
        this.client = new AntonClient(config);
        pluginWorkspaceAccess.addToolbarComponentsCustomizer(this);
        pluginWorkspaceAccess.addMenuBarCustomizer(this);
    }

    public boolean applicationClosing() {
        return true;
    }

    // --- toolbar -----------------------------------------------------------

    public void customizeToolbar(ToolbarInfo toolbarInfo) {
        if (!TOOLBAR_ID.equals(toolbarInfo.getToolbarID())) {
            return;
        }
        JButton button = new JButton("Anton @ref");
        button.setToolTipText("Cursor in ein Element oder Text markieren: in Anton suchen und Referenz einfügen/umschließen");
        button.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                insertReference();
            }
        });
        toolbarInfo.setComponents(new JComponent[] { button });
    }

    // --- menu --------------------------------------------------------------

    public void customizeMainMenu(JMenuBar mainMenuBar) {
        JMenu menu = new JMenu("Anton");
        menu.setMnemonic('A');

        JMenuItem insert = new JMenuItem(ACTION_LABEL + " …");
        insert.setAccelerator(KeyStroke.getKeyStroke("control shift A"));
        insert.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                insertReference();
            }
        });

        JMenuItem settings = new JMenuItem("Anton-Einstellungen …");
        settings.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                SettingsDialog.edit(activeWindow(), config);
            }
        });

        menu.add(insert);
        menu.addSeparator();
        menu.add(settings);

        // Insert before the "Help" menu if present, otherwise append.
        int idx = mainMenuBar.getMenuCount();
        for (int i = 0; i < mainMenuBar.getMenuCount(); i++) {
            JMenu m = mainMenuBar.getMenu(i);
            if (m != null && ("Help".equals(m.getText()) || "Hilfe".equals(m.getText()))) {
                idx = i;
                break;
            }
        }
        mainMenuBar.add(menu, idx);
    }

    // --- the action --------------------------------------------------------

    private void insertReference() {
        WSEditor editor = workspace.getCurrentEditorAccess(PluginWorkspace.MAIN_EDITING_AREA);
        if (editor == null) {
            info("Kein XML-Dokument geöffnet.");
            return;
        }
        // Serial tagging: after each insert the user can ask to jump to the next
        // occurrence of the same text (Text mode), which re-enters this loop as a
        // fresh selection -> wrap flow. `preferElement` keeps the wrap element sticky.
        String preferElement = null;
        while (tagOnce(editor, preferElement)) {
            preferElement = lastElement;
        }
    }

    /** Set when {@link #tagOnce} succeeds — the element used, so the next round can preselect it. */
    private String lastElement;

    /**
     * Run one tag operation. If the caret is in a mapped element the reference is set on
     * it; otherwise a non-blank selection is wrapped in a chosen element. Returns true
     * only when the user asked to continue <em>and</em> a next occurrence was selected.
     */
    private boolean tagOnce(WSEditor editor, String preferElement) {
        RefTargets.RefTarget existing = RefTargets.locate(editor, config.getTargets());
        RefTargets.WrapTarget wrap = existing == null ? RefTargets.locateSelection(editor) : null;
        boolean wrapMode = existing == null;

        if (existing == null && (wrap == null || wrap.selectedText().trim().isEmpty())) {
            info("Cursor in ein konfiguriertes Element setzen ("
                    + String.join(", ", config.getTargets().keySet()) + ")\n"
                    + "— oder Text markieren, um ihn zu umschließen —\n"
                    + "und die Aktion erneut auslösen.");
            return false;
        }

        String prefill = wrapMode ? collapse(wrap.selectedText()) : existing.currentText();
        SearchDialog dlg = new SearchDialog(
                activeWindow(), client, config, wrapMode,
                wrapMode ? null : existing.register(),
                wrapMode ? null : existing.elementName(),
                prefill,
                wrapMode ? null : existing.currentRef(),
                preferElement);

        AntonEntity chosen = dlg.showDialog();
        if (chosen == null) {
            return false; // cancelled -> stop the loop
        }

        String surface;
        int anchor;
        try {
            String value = config.formatRef(chosen);
            if (wrapMode) {
                surface = wrap.selectedText();
                anchor = wrap.wrap(dlg.chosenElement(), dlg.chosenAttr(), value);
                lastElement = dlg.chosenElement();
            } else {
                surface = existing.currentText();
                existing.writeRef(value);
                anchor = existing.afterOffset();
                lastElement = existing.elementName();
            }
        } catch (Exception ex) {
            error("Konnte Referenz nicht setzen: " + ex.getMessage());
            return false;
        }

        if (!dlg.wantsNext()) {
            return false;
        }
        if (surface == null || surface.trim().isEmpty() || anchor < 0
                || !RefTargets.selectNext(editor, surface, anchor)) {
            info("Keine weiteren Vorkommen von „" + collapse(surface) + "“ gefunden\n"
                    + "(„Weiter“ funktioniert nur in der Textansicht).");
            return false;
        }
        return true; // next occurrence selected -> loop and tag it too
    }

    private static String collapse(String s) {
        if (s == null) {
            return "";
        }
        s = s.replaceAll("\\s+", " ").trim();
        return s.length() > 80 ? s.substring(0, 80).trim() : s;
    }

    // --- ui helpers --------------------------------------------------------

    private Window activeWindow() {
        return KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
    }

    private void info(String msg) {
        JOptionPane.showMessageDialog(activeWindow(), msg, "Anton-Referenz",
                JOptionPane.INFORMATION_MESSAGE);
    }

    private void error(String msg) {
        JOptionPane.showMessageDialog(activeWindow(), msg, "Anton-Referenz",
                JOptionPane.ERROR_MESSAGE);
    }
}
