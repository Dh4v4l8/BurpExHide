package burp;

import burp.IBurpExtender;
import burp.IBurpExtenderCallbacks;
import burp.IExtensionStateListener;
import burp.ITab;

import javax.swing.*;
import javax.swing.border.*;
import javax.swing.table.*;
import java.awt.*;
import java.awt.event.*;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * BurpExHide v2.1
 * Author: dh4v4l8
 *
 * Hides, blurs, or renames extension tab names in Burp Suite
 * using reliable Component-based tracking to survive tab shifting and BApp installs.
 */
public class BurpExtender implements IBurpExtender, ITab, IExtensionStateListener {

    private IBurpExtenderCallbacks callbacks;
    private JPanel mainPanel;
    private JTabbedPane burpTabbedPane;
    private boolean blurEnabled = true;
    private Timer refreshTimer;
    private JToggleButton toggleButton;
    private JLabel statusLabel;
    private JLabel countLabel;
    private DefaultTableModel tableModel;
    private JTable extensionTable;
    private JTextField renameAllField;
    private JComboBox<String> blurStyleCombo;

    // Guard flag to prevent recursive table listener loops
    private boolean isUpdatingTable = false;

    // Map each extension's underlying GUI Component directly to its TabRecord
    // This is 100% reliable even when tab index numbers change or wrap!
    private final Map<Component, TabRecord> tabRegistry = new LinkedHashMap<>();

    // Known built-in Burp Suite tab names (never blurred)
    private static final Set<String> BUILTIN_TABS = new HashSet<>(Arrays.asList(
        "Dashboard", "Target", "Proxy", "Intruder", "Repeater",
        "Collaborator", "Sequencer", "Decoder", "Comparer",
        "Logger", "Organizer", "Extensions", "Discover",
        "BurpExHide"
    ));

    // Blur styles
    private static final String STYLE_BLOCK = "Block Characters";
    private static final String STYLE_ASTERISK = "Asterisks (***)";
    private static final String STYLE_DOTS = "Dots (...)";
    private static final String STYLE_UNDERSCORES = "Underscores (___)";
    private static final String STYLE_BLANK = "Blank (empty)";

    /**
     * Stores settings and state for a single extension tab.
     */
    private static class TabRecord {
        String originalName;
        String customName = "";
        boolean blurEnabled = true;

        TabRecord(String originalName) {
            this.originalName = originalName;
        }
    }

    // =========================================================================
    //  Entry Point & Initialization
    // =========================================================================

    @Override
    public void registerExtenderCallbacks(IBurpExtenderCallbacks callbacks) {
        this.callbacks = callbacks;
        callbacks.setExtensionName("BurpExHide");
        callbacks.registerExtensionStateListener(this);

        SwingUtilities.invokeLater(() -> {
            mainPanel = buildUI();
            callbacks.addSuiteTab(BurpExtender.this);

            Timer initTimer = new Timer(1500, e -> {
                findBurpTabbedPane();
                if (burpTabbedPane != null) {
                    scanAndSyncTabs(true);
                    startRefreshTimer();
                    callbacks.printOutput("[BurpExHide] Loaded successfully. Component tracking initialized.");
                } else {
                    retryInit(2500, 4);
                }
            });
            initTimer.setRepeats(false);
            initTimer.start();
        });
    }

    private void retryInit(int delayMs, int retriesLeft) {
        if (retriesLeft <= 0) {
            callbacks.printError("[BurpExHide] Could not locate Burp main tab pane.");
            updateStatusText("Error: Tab pane not found");
            return;
        }
        Timer t = new Timer(delayMs, e -> {
            findBurpTabbedPane();
            if (burpTabbedPane != null) {
                scanAndSyncTabs(true);
                startRefreshTimer();
                callbacks.printOutput("[BurpExHide] Initialized on retry.");
            } else {
                retryInit(delayMs + 2000, retriesLeft - 1);
            }
        });
        t.setRepeats(false);
        t.start();
    }

    // =========================================================================
    //  UI Construction
    // =========================================================================

    private JPanel buildUI() {
        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(new Color(30, 30, 30));

        // -- Header --
        JPanel header = new JPanel();
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        header.setBackground(new Color(30, 30, 30));
        header.setBorder(BorderFactory.createEmptyBorder(20, 25, 12, 25));

        JLabel title = makeLabel("BurpExHide", 26, true, new Color(255, 120, 50));
        JLabel subtitle = makeLabel("Hide your extension names from prying eyes", 12, false, new Color(140, 140, 140));
        subtitle.setBorder(BorderFactory.createEmptyBorder(3, 0, 0, 0));

        header.add(title);
        header.add(subtitle);

        // -- Controls Bar --
        JPanel controlsBar = new JPanel();
        controlsBar.setLayout(new BoxLayout(controlsBar, BoxLayout.Y_AXIS));
        controlsBar.setBackground(new Color(38, 38, 38));
        controlsBar.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 1, 0, new Color(55, 55, 55)),
            BorderFactory.createEmptyBorder(14, 25, 14, 25)
        ));

        JPanel row1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
        row1.setOpaque(false);
        row1.setAlignmentX(Component.LEFT_ALIGNMENT);

        toggleButton = new JToggleButton("Blur ON");
        toggleButton.setSelected(true);
        toggleButton.setFont(new Font("SansSerif", Font.BOLD, 12));
        toggleButton.setPreferredSize(new Dimension(120, 32));
        toggleButton.setFocusPainted(false);
        toggleButton.setBackground(new Color(255, 120, 50));
        toggleButton.setForeground(Color.WHITE);
        toggleButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        toggleButton.addActionListener(e -> onToggleBlur());

        statusLabel = makeLabel("\u25CF Initializing...", 12, false, new Color(200, 200, 80));
        countLabel = makeLabel("", 11, false, new Color(120, 120, 120));

        row1.add(toggleButton);
        row1.add(statusLabel);
        row1.add(Box.createHorizontalStrut(25));
        row1.add(countLabel);

        JPanel row2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
        row2.setOpaque(false);
        row2.setAlignmentX(Component.LEFT_ALIGNMENT);
        row2.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));

        JLabel styleLabel = makeLabel("Blur Style:", 12, false, new Color(180, 180, 180));
        blurStyleCombo = new JComboBox<>(new String[]{
            STYLE_BLOCK, STYLE_ASTERISK, STYLE_DOTS, STYLE_UNDERSCORES, STYLE_BLANK
        });
        blurStyleCombo.setFont(new Font("SansSerif", Font.PLAIN, 12));
        blurStyleCombo.setBackground(new Color(55, 55, 55));
        blurStyleCombo.setForeground(new Color(210, 210, 210));
        blurStyleCombo.setPreferredSize(new Dimension(200, 28));
        blurStyleCombo.addActionListener(e -> {
            if (!isUpdatingTable && blurEnabled) {
                applyAllTitles();
            }
        });

        row2.add(styleLabel);
        row2.add(blurStyleCombo);

        controlsBar.add(row1);
        controlsBar.add(row2);

        // -- Extension Table Section --
        JPanel tableSection = buildTableSection();

        // -- Rename All Section --
        JPanel renameSection = buildRenameAllSection();

        JPanel center = new JPanel();
        center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));
        center.setBackground(new Color(30, 30, 30));
        center.add(controlsBar);
        center.add(tableSection);
        center.add(renameSection);

        root.add(header, BorderLayout.NORTH);
        root.add(center, BorderLayout.CENTER);

        JLabel footer = makeLabel("  BurpExHide v2.1 by dh4v4l8", 11, false, new Color(90, 90, 90));
        footer.setFont(footer.getFont().deriveFont(Font.ITALIC));
        footer.setBorder(BorderFactory.createEmptyBorder(8, 25, 10, 25));
        footer.setOpaque(true);
        footer.setBackground(new Color(30, 30, 30));
        root.add(footer, BorderLayout.SOUTH);

        return root;
    }

    private JPanel buildTableSection() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(new Color(30, 30, 30));
        panel.setBorder(BorderFactory.createEmptyBorder(14, 25, 8, 25));

        JLabel sectionTitle = makeLabel("Extension Tabs (Component Tracked)", 13, true, new Color(210, 210, 210));
        sectionTitle.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));

        String[] cols = {"#", "Original Name", "Custom Display Name", "Blur"};
        tableModel = new DefaultTableModel(cols, 0) {
            @Override
            public boolean isCellEditable(int row, int col) {
                return col == 2 || col == 3;
            }
            @Override
            public Class<?> getColumnClass(int col) {
                if (col == 3) return Boolean.class;
                return String.class;
            }
        };

        extensionTable = new JTable(tableModel);
        styleTable(extensionTable);

        extensionTable.getColumnModel().getColumn(0).setMaxWidth(35);
        extensionTable.getColumnModel().getColumn(0).setMinWidth(35);
        extensionTable.getColumnModel().getColumn(1).setPreferredWidth(220);
        extensionTable.getColumnModel().getColumn(2).setPreferredWidth(220);
        extensionTable.getColumnModel().getColumn(3).setMaxWidth(45);
        extensionTable.getColumnModel().getColumn(3).setMinWidth(45);

        tableModel.addTableModelListener(e -> {
            if (isUpdatingTable) return;
            int row = e.getFirstRow();
            int col = e.getColumn();
            if (row < 0 || col < 0 || row >= tableModel.getRowCount()) return;
            if (col == 2 || col == 3) {
                syncTableRowToRegistry(row);
                if (blurEnabled) {
                    applyAllTitles();
                }
            }
        });

        JScrollPane scroll = new JScrollPane(extensionTable);
        scroll.setBackground(new Color(30, 30, 30));
        scroll.getViewport().setBackground(new Color(42, 42, 42));
        scroll.setBorder(BorderFactory.createLineBorder(new Color(55, 55, 55)));
        scroll.setPreferredSize(new Dimension(600, 180));

        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        btnRow.setOpaque(false);
        btnRow.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));

        JButton blurAllBtn = styledBtn("Blur All");
        blurAllBtn.addActionListener(e -> setAllBlur(true));

        JButton unblurAllBtn = styledBtn("Unblur All");
        unblurAllBtn.addActionListener(e -> setAllBlur(false));

        JButton refreshBtn = styledBtn("Refresh List");
        refreshBtn.addActionListener(e -> scanAndSyncTabs(true));

        JButton clearNamesBtn = styledBtn("Clear Renames");
        clearNamesBtn.addActionListener(e -> clearAllRenames());

        JButton resetBtn = styledBtn("Reset Everything");
        resetBtn.setBackground(new Color(120, 40, 35));
        resetBtn.setForeground(Color.WHITE);
        resetBtn.addActionListener(e -> resetEverything());

        btnRow.add(blurAllBtn);
        btnRow.add(unblurAllBtn);
        btnRow.add(refreshBtn);
        btnRow.add(clearNamesBtn);
        btnRow.add(Box.createHorizontalStrut(15));
        btnRow.add(resetBtn);

        panel.add(sectionTitle, BorderLayout.NORTH);
        panel.add(scroll, BorderLayout.CENTER);
        panel.add(btnRow, BorderLayout.SOUTH);

        return panel;
    }

    private JPanel buildRenameAllSection() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(new Color(38, 38, 38));
        panel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(55, 55, 55)),
            BorderFactory.createEmptyBorder(14, 25, 14, 25)
        ));

        JLabel sectionTitle = makeLabel("Rename All Extensions", 13, true, new Color(210, 210, 210));
        JLabel hint = makeLabel(
            "Set a display name for all extension tabs. Use {n} for auto-numbering (e.g. \"Tool {n}\" = Tool 1, Tool 2, ...)",
            11, false, new Color(130, 130, 130)
        );
        hint.setBorder(BorderFactory.createEmptyBorder(3, 0, 8, 0));

        JPanel inputRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        inputRow.setOpaque(false);
        inputRow.setAlignmentX(Component.LEFT_ALIGNMENT);

        renameAllField = new JTextField(18);
        renameAllField.setFont(new Font("SansSerif", Font.PLAIN, 12));
        renameAllField.setBackground(new Color(50, 50, 50));
        renameAllField.setForeground(new Color(210, 210, 210));
        renameAllField.setCaretColor(new Color(255, 120, 50));
        renameAllField.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(70, 70, 70)),
            BorderFactory.createEmptyBorder(5, 8, 5, 8)
        ));
        renameAllField.setText("Tool {n}");
        renameAllField.addActionListener(e -> doRenameAll());

        JButton applyBtn = styledBtn("Apply to All");
        applyBtn.setBackground(new Color(255, 120, 50));
        applyBtn.setForeground(Color.WHITE);
        applyBtn.addActionListener(e -> doRenameAll());

        inputRow.add(renameAllField);
        inputRow.add(applyBtn);

        panel.add(sectionTitle);
        panel.add(hint);
        panel.add(inputRow);

        return panel;
    }

    // =========================================================================
    //  Core Component-Based Tracking & Sync Logic
    // =========================================================================

    /**
     * Scans the JTabbedPane, registers new tabs by their Component object,
     * removes closed tabs, and updates the UI table.
     */
    private void scanAndSyncTabs(boolean forceTableUpdate) {
        if (burpTabbedPane == null) return;

        SwingUtilities.invokeLater(() -> {
            boolean structureChanged = false;
            Set<Component> activeComponents = new HashSet<>();

            for (int i = 0; i < burpTabbedPane.getTabCount(); i++) {
                Component comp = burpTabbedPane.getComponentAt(i);
                String title = burpTabbedPane.getTitleAt(i);
                if (comp == null || title == null) continue;

                // Skip our own tab and built-in Burp tools
                if (comp == mainPanel || isBuiltinTab(title)) {
                    continue;
                }

                activeComponents.add(comp);

                // If this is a newly opened extension tab
                if (!tabRegistry.containsKey(comp)) {
                    // If the title happens to be leftover blur characters from a previous session,
                    // we still register it so the user can unblur/reset/rename it without errors!
                    tabRegistry.put(comp, new TabRecord(title.trim()));
                    structureChanged = true;
                    callbacks.printOutput("[BurpExHide] Registered new tab: " + title);
                }
            }

            // Remove tabs that have been closed by the user
            if (tabRegistry.keySet().removeIf(c -> !activeComponents.contains(c))) {
                structureChanged = true;
            }

            if (structureChanged || forceTableUpdate) {
                populateTable();
            }

            if (blurEnabled) {
                applyAllTitles();
            }
        });
    }

    /**
     * Applies desired titles to all registered extension tabs in Burp Suite.
     * Only alters tabs whose title actually needs changing, avoiding unwanted focus shifts.
     */
    private void applyAllTitles() {
        if (burpTabbedPane == null) return;

        SwingUtilities.invokeLater(() -> {
            int changes = 0;
            for (int i = 0; i < burpTabbedPane.getTabCount(); i++) {
                Component comp = burpTabbedPane.getComponentAt(i);
                TabRecord record = tabRegistry.get(comp);
                if (record == null) continue; // Built-in tab or not registered

                String currentTitle = burpTabbedPane.getTitleAt(i);
                String desiredTitle = getDesiredTitle(record);

                if (!desiredTitle.equals(currentTitle)) {
                    burpTabbedPane.setTitleAt(i, desiredTitle);
                    changes++;
                }
            }

            if (changes > 0) {
                callbacks.printOutput("[BurpExHide] Updated titles for " + changes + " tab(s).");
            }
            updateStatusLabel();
            updateCountLabel();
        });
    }

    private String getDesiredTitle(TabRecord record) {
        if (!blurEnabled || !record.blurEnabled) {
            return record.originalName;
        }
        if (record.customName != null && !record.customName.trim().isEmpty()) {
            return record.customName.trim();
        }
        return generateBlurString(record.originalName.length());
    }

    /**
     * Restores real original names unconditionally on all extension tabs.
     */
    private void restoreOriginalTitles() {
        if (burpTabbedPane == null) return;
        for (int i = 0; i < burpTabbedPane.getTabCount(); i++) {
            Component comp = burpTabbedPane.getComponentAt(i);
            TabRecord record = tabRegistry.get(comp);
            if (record != null && record.originalName != null) {
                burpTabbedPane.setTitleAt(i, record.originalName);
            }
        }
    }

    // =========================================================================
    //  UI Table Synchronization
    // =========================================================================

    private void populateTable() {
        isUpdatingTable = true;
        try {
            tableModel.setRowCount(0);
            int idx = 0;
            for (TabRecord rec : tabRegistry.values()) {
                idx++;
                tableModel.addRow(new Object[]{
                    String.valueOf(idx),
                    rec.originalName,
                    rec.customName,
                    rec.blurEnabled
                });
            }
        } finally {
            isUpdatingTable = false;
        }
        updateCountLabel();
        updateStatusLabel();
    }

    private void syncTableRowToRegistry(int row) {
        String origName = (String) tableModel.getValueAt(row, 1);
        String custom = (String) tableModel.getValueAt(row, 2);
        Boolean blur = (Boolean) tableModel.getValueAt(row, 3);

        for (TabRecord rec : tabRegistry.values()) {
            if (rec.originalName.equals(origName)) {
                rec.customName = (custom == null) ? "" : custom;
                rec.blurEnabled = (blur != null) && blur;
                break;
            }
        }
    }

    // =========================================================================
    //  Button Actions
    // =========================================================================

    private void onToggleBlur() {
        blurEnabled = toggleButton.isSelected();
        if (blurEnabled) {
            toggleButton.setText("Blur ON");
            toggleButton.setBackground(new Color(255, 120, 50));
            applyAllTitles();
        } else {
            toggleButton.setText("Blur OFF");
            toggleButton.setBackground(new Color(100, 100, 100));
            restoreOriginalTitles();
            updateStatusLabel();
        }
    }

    private void setAllBlur(boolean state) {
        for (TabRecord rec : tabRegistry.values()) {
            rec.blurEnabled = state;
        }
        populateTable();
        if (blurEnabled) {
            applyAllTitles();
        } else if (!state) {
            restoreOriginalTitles();
        }
    }

    private void doRenameAll() {
        String pattern = renameAllField.getText();
        if (pattern == null) pattern = "";

        int counter = 1;
        for (TabRecord rec : tabRegistry.values()) {
            if (pattern.trim().isEmpty()) {
                rec.customName = "";
            } else if (pattern.contains("{n}")) {
                rec.customName = pattern.replace("{n}", String.valueOf(counter));
            } else {
                rec.customName = pattern;
            }
            counter++;
        }
        populateTable();
        if (blurEnabled) applyAllTitles();
        callbacks.printOutput("[BurpExHide] Applied custom rename pattern: " + pattern);
    }

    private void clearAllRenames() {
        for (TabRecord rec : tabRegistry.values()) {
            rec.customName = "";
        }
        populateTable();
        if (blurEnabled) applyAllTitles();
        callbacks.printOutput("[BurpExHide] Cleared all custom display names.");
    }

    /**
     * Resets everything cleanly without errors or stuck tabs.
     */
    private void resetEverything() {
        blurEnabled = false;
        toggleButton.setSelected(false);
        toggleButton.setText("Blur OFF");
        toggleButton.setBackground(new Color(100, 100, 100));

        // Restore real titles first
        restoreOriginalTitles();

        // Clear customizations
        for (TabRecord rec : tabRegistry.values()) {
            rec.customName = "";
            rec.blurEnabled = true;
        }

        renameAllField.setText("Tool {n}");
        blurStyleCombo.setSelectedIndex(0);

        populateTable();
        callbacks.printOutput("[BurpExHide] Reset completed. All tabs unblurred and restored.");
    }

    // =========================================================================
    //  Tab Pane Discovery & Self-Healing Timer
    // =========================================================================

    private void findBurpTabbedPane() {
        for (Window window : Window.getWindows()) {
            if (window instanceof JFrame && window.isVisible()) {
                JFrame frame = (JFrame) window;
                String title = frame.getTitle();
                if (title != null && title.contains("Burp Suite")) {
                    List<JTabbedPane> panes = new ArrayList<>();
                    collectTabbedPanes(frame.getContentPane(), panes);

                    JTabbedPane best = null;
                    int bestScore = 0;

                    for (JTabbedPane p : panes) {
                        int score = 0;
                        for (int i = 0; i < p.getTabCount(); i++) {
                            String t = p.getTitleAt(i);
                            if (t != null && BUILTIN_TABS.contains(t.trim())) {
                                score++;
                            }
                        }
                        if (score > bestScore) {
                            bestScore = score;
                            best = p;
                        }
                    }

                    if (best != null && bestScore >= 3) {
                        burpTabbedPane = best;
                        return;
                    }
                }
            }
        }
    }

    private void collectTabbedPanes(Component c, List<JTabbedPane> list) {
        if (c instanceof JTabbedPane) {
            list.add((JTabbedPane) c);
        }
        if (c instanceof Container) {
            for (Component child : ((Container) c).getComponents()) {
                collectTabbedPanes(child, list);
            }
        }
    }

    /**
     * Self-healing background check:
     * Runs every 1.5 seconds. If a user loads a new extension from BApp Store,
     * or if Burp Suite resets tab titles to real names during a redraw, this timer
     * detects the discrepancy instantly and heals the tab labels automatically!
     */
    private void startRefreshTimer() {
        if (refreshTimer != null) refreshTimer.stop();

        refreshTimer = new Timer(1500, e -> {
            if (burpTabbedPane == null || !blurEnabled) return;

            boolean newTabDiscovered = false;
            boolean titleNeedsHealing = false;

            for (int i = 0; i < burpTabbedPane.getTabCount(); i++) {
                Component comp = burpTabbedPane.getComponentAt(i);
                String currentTitle = burpTabbedPane.getTitleAt(i);
                if (comp == null || currentTitle == null || comp == mainPanel || isBuiltinTab(currentTitle)) {
                    continue;
                }

                TabRecord rec = tabRegistry.get(comp);
                if (rec == null) {
                    newTabDiscovered = true;
                    break;
                } else {
                    String desired = getDesiredTitle(rec);
                    if (!desired.equals(currentTitle)) {
                        titleNeedsHealing = true;
                    }
                }
            }

            if (newTabDiscovered) {
                scanAndSyncTabs(false);
            } else if (titleNeedsHealing) {
                applyAllTitles();
            }
        });
        refreshTimer.setRepeats(true);
        refreshTimer.start();
    }

    // =========================================================================
    //  Helper Utilities
    // =========================================================================

    private boolean isBuiltinTab(String title) {
        if (title == null) return true;
        String t = title.trim();
        if (BUILTIN_TABS.contains(t)) return true;
        for (String b : BUILTIN_TABS) {
            if (b.equalsIgnoreCase(t)) return true;
        }
        return false;
    }

    private String generateBlurString(int len) {
        int targetLen = Math.max(len, 4);
        char ch = '\u2588'; // block character by default
        String style = (String) blurStyleCombo.getSelectedItem();

        if (STYLE_ASTERISK.equals(style)) {
            ch = '*';
        } else if (STYLE_DOTS.equals(style)) {
            ch = '\u2022';
        } else if (STYLE_UNDERSCORES.equals(style)) {
            ch = '_';
        } else if (STYLE_BLANK.equals(style)) {
            ch = ' ';
        }

        char[] arr = new char[targetLen];
        Arrays.fill(arr, ch);
        return new String(arr);
    }

    private JLabel makeLabel(String text, int size, boolean bold, Color color) {
        JLabel l = new JLabel(text);
        l.setFont(new Font("SansSerif", bold ? Font.BOLD : Font.PLAIN, size));
        l.setForeground(color);
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        return l;
    }

    private JButton styledBtn(String text) {
        JButton b = new JButton(text);
        b.setFont(new Font("SansSerif", Font.PLAIN, 11));
        b.setBackground(new Color(60, 60, 60));
        b.setForeground(new Color(210, 210, 210));
        b.setFocusPainted(false);
        b.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(75, 75, 75)),
            BorderFactory.createEmptyBorder(5, 12, 5, 12)
        ));
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return b;
    }

    private void styleTable(JTable table) {
        table.setBackground(new Color(42, 42, 42));
        table.setForeground(new Color(200, 200, 200));
        table.setGridColor(new Color(55, 55, 55));
        table.setSelectionBackground(new Color(65, 65, 65));
        table.setSelectionForeground(new Color(255, 180, 100));
        table.setFont(new Font("SansSerif", Font.PLAIN, 12));
        table.setRowHeight(26);
        table.setShowGrid(true);
        table.setIntercellSpacing(new Dimension(1, 1));

        JTableHeader header = table.getTableHeader();
        header.setBackground(new Color(50, 50, 50));
        header.setForeground(new Color(190, 190, 190));
        header.setFont(new Font("SansSerif", Font.BOLD, 11));
        header.setReorderingAllowed(false);
    }

    private void updateStatusLabel() {
        if (statusLabel == null) return;
        SwingUtilities.invokeLater(() -> {
            if (blurEnabled) {
                int activeCount = 0;
                for (TabRecord r : tabRegistry.values()) {
                    if (r.blurEnabled) activeCount++;
                }
                statusLabel.setText("\u25CF Active - " + activeCount + " of " + tabRegistry.size() + " tab(s) hidden");
                statusLabel.setForeground(new Color(80, 200, 120));
            } else {
                statusLabel.setText("\u25CB Inactive - all extension names visible");
                statusLabel.setForeground(new Color(160, 160, 160));
            }
        });
    }

    private void updateStatusText(String text) {
        if (statusLabel == null) return;
        SwingUtilities.invokeLater(() -> {
            statusLabel.setText("\u25CF " + text);
            statusLabel.setForeground(new Color(255, 80, 80));
        });
    }

    private void updateCountLabel() {
        if (countLabel == null) return;
        SwingUtilities.invokeLater(() -> {
            countLabel.setText(tabRegistry.size() + " extension(s) tracked");
        });
    }

    // =========================================================================
    //  ITab & IExtensionStateListener
    // =========================================================================

    @Override
    public String getTabCaption() {
        return "BurpExHide";
    }

    @Override
    public Component getUiComponent() {
        return mainPanel;
    }

    @Override
    public void extensionUnloaded() {
        if (refreshTimer != null) {
            refreshTimer.stop();
            refreshTimer = null;
        }

        try {
            SwingUtilities.invokeAndWait(() -> restoreOriginalTitles());
        } catch (Exception e) {
            restoreOriginalTitles();
        }

        tabRegistry.clear();
        callbacks.printOutput("[BurpExHide] Extension unloaded and all tabs unblurred.");
    }
}
