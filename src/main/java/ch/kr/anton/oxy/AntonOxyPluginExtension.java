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
        button.setToolTipText("Akteur/Ort in Anton suchen und @ref einfügen");
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
        final RefTargets.RefTarget target =
                RefTargets.locate(editor, config.getTargets());
        if (target == null) {
            info("Cursor in ein konfiguriertes Element setzen ("
                    + String.join(", ", config.getTargets().keySet()) + "),\n"
                    + "dann die Aktion erneut auslösen.");
            return;
        }

        AntonEntity chosen = new SearchDialog(
                activeWindow(), client, config,
                target.register(), target.elementName(),
                target.currentText(), target.currentRef()
        ).showDialog();

        if (chosen == null) {
            return; // cancelled
        }
        try {
            target.writeRef(config.formatRef(chosen));
        } catch (Exception ex) {
            error("Konnte @ref nicht setzen: " + ex.getMessage());
        }
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
