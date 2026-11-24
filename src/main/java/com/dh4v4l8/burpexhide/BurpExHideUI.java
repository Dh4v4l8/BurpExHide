package com.dh4v4l8.burpexhide;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.Collection;

public class BurpExHideUI extends JPanel {

    private static final Color ACCENT_ORANGE = new Color(255, 102, 51);
    private static final Color STATUS_GREEN = new Color(46, 160, 67);
    private static final Color DANGER_RED = new Color(185, 55, 50);

    private final TabManager tabManager;

    private JToggleButton toggleButton;
    private JLabel statusLabel;
    private JLabel countLabel;
    private JComboBox<String> blurStyleCombo;
    private DefaultTableModel tableModel;
    private JTable extensionTable;
    private JTextField renameAllField;

    private boolean isUpdatingUI = false;

    public BurpExHideUI(TabManager tabManager) {
        this.tabManager = tabManager;
        setLayout(new BorderLayout(0, 10));
        setBorder(BorderFactory.createEmptyBorder(16, 20, 12, 20));

        add(buildHeader(), BorderLayout.NORTH);
        add(buildCenter(), BorderLayout.CENTER);
        add(buildFooter(), BorderLayout.SOUTH);

        refreshUI();
    }

    private JPanel buildHeader() {
        JPanel header = new JPanel();
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        header.setOpaque(false);

        JLabel title = new JLabel("BurpExHide");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 24f));
        title.setForeground(ACCENT_ORANGE);
        title.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel subtitle = new JLabel("Hide, blur, or rename extension tabs for clean screen shares and recordings");
        subtitle.setFont(subtitle.getFont().deriveFont(Font.PLAIN, 12f));
        subtitle.setForeground(UIManager.getColor("Label.disabledForeground"));
        subtitle.setBorder(BorderFactory.createEmptyBorder(2, 0, 8, 0));
        subtitle.setAlignmentX(Component.LEFT_ALIGNMENT);

        header.add(title);
        header.add(subtitle);
        header.add(new JSeparator());

        return header;
    }

    private JPanel buildCenter() {
        JPanel center = new JPanel(new BorderLayout(0, 12));
        center.setOpaque(false);

        center.add(buildControlsPanel(), BorderLayout.NORTH);
        center.add(buildTablePanel(), BorderLayout.CENTER);
        center.add(buildRenameAllPanel(), BorderLayout.SOUTH);

        return center;
    }

    private JPanel buildControlsPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createTitledBorder("Privacy Controls"));

        JPanel row1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 6));
        row1.setOpaque(false);
        row1.setAlignmentX(Component.LEFT_ALIGNMENT);

        toggleButton = new JToggleButton("Blur ON");
        toggleButton.setFont(toggleButton.getFont().deriveFont(Font.BOLD, 12f));
        toggleButton.setPreferredSize(new Dimension(115, 30));
        toggleButton.setFocusPainted(false);
        toggleButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        toggleButton.addActionListener(e -> {
            if (!isUpdatingUI) {
                tabManager.setBlurEnabled(toggleButton.isSelected());
                updateToggleStyle();
            }
        });

        statusLabel = new JLabel("Initializing...");
        statusLabel.setFont(statusLabel.getFont().deriveFont(Font.BOLD, 12f));

        countLabel = new JLabel("0 extension(s) tracked");
        countLabel.setForeground(UIManager.getColor("Label.disabledForeground"));

        row1.add(toggleButton);
        row1.add(statusLabel);
        row1.add(Box.createHorizontalStrut(20));
        row1.add(countLabel);

        JPanel row2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 4));
        row2.setOpaque(false);
        row2.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel styleLabel = new JLabel("Blur Style:");
        blurStyleCombo = new JComboBox<>(TabManager.ALL_STYLES);
        blurStyleCombo.setPreferredSize(new Dimension(200, 28));
        blurStyleCombo.addActionListener(e -> {
            if (!isUpdatingUI) {
                String selected = (String) blurStyleCombo.getSelectedItem();
                if (selected != null) {
                    tabManager.setBlurStyle(selected);
                }
            }
        });

        row2.add(styleLabel);
        row2.add(blurStyleCombo);

        panel.add(row1);
        panel.add(row2);

        return panel;
    }

    private JPanel buildTablePanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setBorder(BorderFactory.createTitledBorder("Tracked Extension Tabs"));

        String[] cols = {"#", "Original Name", "Custom Display Name", "Blur"};
        tableModel = new DefaultTableModel(cols, 0) {
            @Override
            public boolean isCellEditable(int row, int col) {
                return col == 2 || col == 3;
            }

            @Override
            public Class<?> getColumnClass(int col) {
                return (col == 3) ? Boolean.class : String.class;
            }
        };

        extensionTable = new JTable(tableModel);
        extensionTable.setRowHeight(26);
        extensionTable.getTableHeader().setReorderingAllowed(false);
        extensionTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        extensionTable.getColumnModel().getColumn(0).setMaxWidth(40);
        extensionTable.getColumnModel().getColumn(0).setMinWidth(40);
        extensionTable.getColumnModel().getColumn(1).setPreferredWidth(220);
        extensionTable.getColumnModel().getColumn(2).setPreferredWidth(220);
        extensionTable.getColumnModel().getColumn(3).setMaxWidth(50);
        extensionTable.getColumnModel().getColumn(3).setMinWidth(50);

        tableModel.addTableModelListener(e -> {
            if (isUpdatingUI) return;
            int row = e.getFirstRow();
            int col = e.getColumn();
            if (row < 0 || col < 0 || row >= tableModel.getRowCount()) return;

            if (col == 2 || col == 3) {
                String origName = (String) tableModel.getValueAt(row, 1);
                String customName = (String) tableModel.getValueAt(row, 2);
                Boolean blur = (Boolean) tableModel.getValueAt(row, 3);
                tabManager.updateTabRecord(origName, customName, blur != null && blur);
            }
        });

        JScrollPane scrollPane = new JScrollPane(extensionTable);

        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        btnRow.setOpaque(false);

        JButton blurAllBtn = new JButton("Blur All");
        blurAllBtn.addActionListener(e -> tabManager.setAllTabsBlur(true));

        JButton unblurAllBtn = new JButton("Unblur All");
        unblurAllBtn.addActionListener(e -> tabManager.setAllTabsBlur(false));

        JButton refreshBtn = new JButton("Refresh List");
        refreshBtn.addActionListener(e -> tabManager.scanAndSyncTabs(true));

        JButton clearNamesBtn = new JButton("Clear Renames");
        clearNamesBtn.addActionListener(e -> tabManager.clearAllRenames());

        JButton ignoreBtn = new JButton("Ignore Selected");
        ignoreBtn.setToolTipText("Exclude the selected tab permanently (useful for new built-in Burp tabs)");
        ignoreBtn.addActionListener(e -> {
            int row = extensionTable.getSelectedRow();
            if (row >= 0) {
                String origName = (String) tableModel.getValueAt(row, 1);
                tabManager.ignoreTab(origName);
            }
        });

        JButton resetBtn = new JButton("Reset Everything");
        resetBtn.setBackground(DANGER_RED);
        resetBtn.setForeground(Color.WHITE);
        resetBtn.addActionListener(e -> {
            int confirm = JOptionPane.showConfirmDialog(
                this,
                "Restore all original tab names, clear custom renames, and reset ignored tabs?",
                "Reset BurpExHide",
                JOptionPane.YES_NO_OPTION
            );
            if (confirm == JOptionPane.YES_OPTION) {
                renameAllField.setText("Tool {n}");
                tabManager.resetEverything();
            }
        });

        btnRow.add(blurAllBtn);
        btnRow.add(unblurAllBtn);
        btnRow.add(refreshBtn);
        btnRow.add(clearNamesBtn);
        btnRow.add(ignoreBtn);
        btnRow.add(Box.createHorizontalStrut(12));
        btnRow.add(resetBtn);

        panel.add(scrollPane, BorderLayout.CENTER);
        panel.add(btnRow, BorderLayout.SOUTH);

        return panel;
    }

    private JPanel buildRenameAllPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createTitledBorder("Bulk Rename All Extensions"));

        JLabel hint = new JLabel("Use {n} for auto-numbering (e.g., \"Tool {n}\" -> Tool 1, Tool 2, ...)");
        hint.setForeground(UIManager.getColor("Label.disabledForeground"));
        hint.setBorder(BorderFactory.createEmptyBorder(2, 6, 6, 6));
        hint.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel inputRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        inputRow.setOpaque(false);
        inputRow.setAlignmentX(Component.LEFT_ALIGNMENT);

        renameAllField = new JTextField("Tool {n}", 20);
        renameAllField.addActionListener(e -> tabManager.renameAll(renameAllField.getText()));

        JButton applyBtn = new JButton("Apply to All");
        applyBtn.setBackground(ACCENT_ORANGE);
        applyBtn.setForeground(Color.WHITE);
        applyBtn.addActionListener(e -> tabManager.renameAll(renameAllField.getText()));

        inputRow.add(renameAllField);
        inputRow.add(applyBtn);

        panel.add(hint);
        panel.add(inputRow);

        return panel;
    }

    private JPanel buildFooter() {
        JPanel footer = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        footer.setOpaque(false);
        JLabel label = new JLabel("BurpExHide v3.0 (Montoya API) by dh4v4l8 — Settings auto-saved");
        label.setFont(label.getFont().deriveFont(Font.ITALIC, 11f));
        label.setForeground(UIManager.getColor("Label.disabledForeground"));
        footer.add(label);
        return footer;
    }

    public void refreshUI() {
        isUpdatingUI = true;
        try {
            boolean enabled = tabManager.isBlurEnabled();
            toggleButton.setSelected(enabled);
            updateToggleStyle();

            blurStyleCombo.setSelectedItem(tabManager.getBlurStyle());

            Collection<TabManager.TabRecord> records = tabManager.getTrackedTabs();
            tableModel.setRowCount(0);
            int idx = 1;
            for (TabManager.TabRecord rec : records) {
                tableModel.addRow(new Object[]{
                    String.valueOf(idx++),
                    rec.originalName,
                    rec.customName,
                    rec.blurEnabled
                });
            }

            int total = records.size();
            int hidden = tabManager.getActiveHiddenCount();
            countLabel.setText(total + " extension(s) tracked");

            if (enabled) {
                statusLabel.setText("\u25CF Active - " + hidden + " of " + total + " tab(s) hidden");
                statusLabel.setForeground(STATUS_GREEN);
            } else {
                statusLabel.setText("\u25CB Inactive - all extension names visible");
                statusLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
            }
        } finally {
            isUpdatingUI = false;
        }
    }

    private void updateToggleStyle() {
        if (toggleButton.isSelected()) {
            toggleButton.setText("Blur ON");
            toggleButton.setBackground(ACCENT_ORANGE);
            toggleButton.setForeground(Color.WHITE);
        } else {
            toggleButton.setText("Blur OFF");
            toggleButton.setBackground(UIManager.getColor("Button.background"));
            toggleButton.setForeground(UIManager.getColor("Button.foreground"));
        }
    }
}
