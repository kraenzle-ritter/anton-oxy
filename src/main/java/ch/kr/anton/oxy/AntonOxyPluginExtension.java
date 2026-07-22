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
import javax.swing.text.Document;

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
    private java.awt.Frame parentFrame;

    public void applicationStarted(StandalonePluginWorkspace pluginWorkspaceAccess) {
        this.workspace = pluginWorkspaceAccess;
        this.config = new Config(pluginWorkspaceAccess.getOptionsStorage());
        this.client = new AntonClient(config);
        // getParentFrame() is typed Object in the oXygen API but is the main java.awt.Frame
        // at runtime; keep it as a stable parent for message dialogs.
        Object pf = pluginWorkspaceAccess.getParentFrame();
        this.parentFrame = pf instanceof java.awt.Frame ? (java.awt.Frame) pf : null;
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
        JButton dateButton = new JButton("Datum");
        dateButton.setToolTipText("Markiertes Datum als <date when=\"…\"> taggen");
        dateButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                tagDate();
            }
        });
        toolbarInfo.setComponents(new JComponent[] { button, dateButton });
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

        JMenuItem date = new JMenuItem("Datum taggen …");
        date.setAccelerator(KeyStroke.getKeyStroke("control shift D"));
        date.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                tagDate();
            }
        });

        JMenuItem settings = new JMenuItem("Anton-Einstellungen …");
        settings.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                SettingsDialog.edit(activeWindow(), config);
            }
        });

        menu.add(insert);
        menu.add(date);
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

    /**
     * Wrap the current selection in a TEI {@code <date>} element. Unlike a reference, a date
     * needs no Anton lookup — just a normalised value — so this is a small local dialog that
     * best-effort parses the selection into an ISO 8601 value the user can confirm or fix.
     */
    private void tagDate() {
        WSEditor editor = workspace.getCurrentEditorAccess(PluginWorkspace.MAIN_EDITING_AREA);
        if (editor == null) {
            info("Kein XML-Dokument geöffnet.");
            return;
        }
        RefTargets.WrapTarget wrap = RefTargets.locateSelection(editor);
        if (wrap == null || wrap.selectedText().trim().isEmpty()) {
            info("Ein Datum markieren und die Aktion erneut auslösen.");
            return;
        }
        DateDialog dlg = new DateDialog(activeWindow(), collapse(wrap.selectedText()));
        if (!dlg.showDialog()) {
            return; // cancelled
        }
        try {
            wrap.wrap(dlg.element(), dlg.attr(), dlg.value());
        } catch (Exception ex) {
            error("Konnte Datum nicht taggen: " + ex.getMessage());
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
            // "Überspringen": don't tag this occurrence, but jump to the next one.
            if (dlg.wantsSkip()) {
                if (wrapMode) {
                    lastElement = dlg.chosenElement();
                    return continueNext(editor, wrap.selectedText(), wrap.searchAnchor());
                }
                lastElement = existing.elementName();
                return continueNext(editor, existing.currentText(), existing.afterOffset());
            }
            return false; // cancelled -> stop the loop
        }

        String surface;
        int anchor;
        String element;
        String attr;
        String value = config.formatRef(chosen);
        try {
            if (wrapMode) {
                surface = wrap.selectedText();
                element = dlg.chosenElement();
                attr = dlg.chosenAttr();
                anchor = wrap.wrap(element, attr, value);
                lastElement = element;
            } else {
                surface = existing.currentText();
                String oldElement = existing.elementName();
                Config.Target oldT = config.getTargets().get(oldElement);
                String oldAttr = oldT != null ? oldT.attribute : config.getAttribute();
                String wantElement = dlg.chosenElement();
                String newAttr = dlg.chosenAttr();
                if (wantElement != null && !wantElement.equals(oldElement)) {
                    // Picked entity belongs to a different element (e.g. a place chosen on an
                    // existing persName): retag the element and drop the now-stale attribute.
                    existing.renameElementTo(wantElement);
                    String useAttr = newAttr != null ? newAttr : oldAttr;
                    if (!useAttr.equals(oldAttr)) {
                        existing.removeAttribute(oldAttr);
                    }
                    existing.writeRef(useAttr, value);
                    element = wantElement;
                    attr = useAttr;
                } else {
                    existing.writeRef(value);
                    element = oldElement;
                    attr = oldAttr;
                }
                anchor = existing.afterOffset();
                lastElement = element;
            }
        } catch (Exception ex) {
            error("Konnte Referenz nicht setzen: " + ex.getMessage());
            return false;
        }

        if (!dlg.wantsNext()) {
            // The user chose a plain insert (not "Einfügen & weiter"): offer to also tag the
            // remaining occurrences of this actor/place in one batch (Text mode only).
            maybeTagFurther(editor, chosen, surface, element, attr, value);
            return false;
        }
        return continueNext(editor, surface, anchor);
    }

    /**
     * After a reference was set, scan the rest of the document (Text mode) for further
     * occurrences of the same actor/place — including genitive endings and the name variants
     * the API returned — and let the user tick which ones to tag as the same element.
     * A no-op in Author mode, for keywords, or when the feature is switched off.
     */
    private void maybeTagFurther(WSEditor editor, AntonEntity chosen, String markedText,
                                 String element, String attr, String value) {
        if (!config.isScanOccurrences()) {
            return;
        }
        if (!"actors".equals(chosen.register) && !"places".equals(chosen.register)) {
            return; // occurrence search only makes sense for names, not keywords
        }
        Document doc = RefTargets.textDocument(editor);
        if (doc == null) {
            return; // not a Text page — skip silently
        }
        String xml;
        try {
            xml = doc.getText(0, doc.getLength());
        } catch (Exception e) {
            return;
        }
        java.util.List<String> terms = Occurrences.terms(chosen, markedText);
        if (terms.isEmpty()) {
            return;
        }
        // Skip text already inside any mapped element, so nothing gets double-tagged.
        java.util.List<Occurrences.Match> matches =
                Occurrences.find(xml, config.getTargets().keySet(), terms, config.getContextChars());
        if (matches.isEmpty()) {
            return;
        }
        String label = (chosen.label != null && !chosen.label.isEmpty()) ? chosen.label : markedText;
        java.util.List<Occurrences.Match> picked =
                OccurrenceDialog.choose(activeWindow(), label, element, matches);
        if (picked == null || picked.isEmpty()) {
            return;
        }
        applyOccurrences(doc, picked, element, attr, value);
    }

    /**
     * Wrap the chosen occurrences in one edit over the covering span, so the whole batch is a
     * single undo step. Falls back to wrapping them one by one (from the end, so earlier
     * offsets stay valid) if the combined edit fails.
     */
    private void applyOccurrences(Document doc, java.util.List<Occurrences.Match> matches,
                                  String element, String attr, String value) {
        java.util.List<Occurrences.Match> sorted =
                new java.util.ArrayList<Occurrences.Match>(matches);
        sorted.sort((a, b) -> a.start - b.start); // ascending for the covering-span rebuild

        int done = 0;
        try {
            int[][] ranges = new int[sorted.size()][];
            for (int i = 0; i < sorted.size(); i++) {
                ranges[i] = new int[] { sorted.get(i).start, sorted.get(i).end };
            }
            RefTargets.wrapRanges(doc, ranges, element, attr, value);
            done = sorted.size();
        } catch (Exception combined) {
            // fall back to per-occurrence wrapping, working from the end so offsets stay valid
            for (int i = sorted.size() - 1; i >= 0; i--) {
                Occurrences.Match m = sorted.get(i);
                try {
                    RefTargets.wrapRange(doc, m.start, m.end, element, attr, value);
                    done++;
                } catch (Exception ex) {
                    // skip this occurrence, keep going
                }
            }
        }
        if (done > 0) {
            info(done + (done == 1 ? " weiteres Vorkommen" : " weitere Vorkommen") + " ausgezeichnet.");
        }
    }

    /**
     * Select the next occurrence of {@code surface} from {@code anchor} so the loop can tag
     * it too. Returns false (and tells the user) when there is none — which also ends the
     * serial-tagging loop.
     */
    private boolean continueNext(WSEditor editor, String surface, int anchor) {
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
        showMessage(msg, JOptionPane.INFORMATION_MESSAGE);
    }

    private void error(String msg) {
        showMessage(msg, JOptionPane.ERROR_MESSAGE);
    }

    /**
     * Show a message parented to the stable oXygen main frame, on a later EDT cycle.
     * Both matter: right after a modal search dialog closes {@link #activeWindow()} can be
     * {@code null} (parenting the message to a hidden frame that stays behind oXygen until
     * the user clicks), and showing it after the current event lets focus settle first.
     */
    private void showMessage(final String msg, final int type) {
        final java.awt.Window owner = parentFrame != null ? parentFrame : activeWindow();
        javax.swing.SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                JOptionPane.showMessageDialog(owner, msg, "Anton-Referenz", type);
            }
        });
    }
}
