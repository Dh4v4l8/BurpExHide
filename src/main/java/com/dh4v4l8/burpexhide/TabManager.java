package com.dh4v4l8.burpexhide;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.persistence.Preferences;

import javax.swing.*;
import javax.swing.Timer;
import java.awt.*;
import java.util.*;
import java.util.List;

public class TabManager {

    public static final String STYLE_BLOCK = "Block Characters";
    public static final String STYLE_ASTERISK = "Asterisks (***)";
    public static final String STYLE_DOTS = "Dots (...)";
    public static final String STYLE_UNDERSCORES = "Underscores (___)";
    public static final String STYLE_BLANK = "Blank (empty)";

    public static final String[] ALL_STYLES = {
        STYLE_BLOCK, STYLE_ASTERISK, STYLE_DOTS, STYLE_UNDERSCORES, STYLE_BLANK
    };

    private static final String PREF_BLUR_ENABLED = "burpexhide.blurEnabled";
    private static final String PREF_BLUR_STYLE = "burpexhide.blurStyle";
    private static final String PREF_IGNORED_TABS = "burpexhide.ignoredTabs";
    private static final String PREF_CUSTOM_PREFIX = "burpexhide.custom.";
    private static final String PREF_TAB_BLUR_PREFIX = "burpexhide.tabblur.";

    // Built-in Burp Suite tabs (including Academy, Learn, Burp AI)
    private static final Set<String> BUILTIN_TABS = new HashSet<>(Arrays.asList(
        "Dashboard", "Target", "Proxy", "Intruder", "Repeater",
        "Collaborator", "Sequencer", "Decoder", "Comparer",
        "Logger", "Organizer", "Extensions", "Discover",
        "Academy", "Learn", "Burp AI", "BurpExHide"
    ));

    public static class TabRecord {
        public final String originalName;
        public String customName = "";
        public boolean blurEnabled = true;

        public TabRecord(String originalName) {
            this.originalName = originalName;
        }
    }

    private final MontoyaApi api;
    private final Preferences prefs;
    private final Map<Component, TabRecord> tabRegistry = new LinkedHashMap<>();
    private final Set<String> userIgnoredTabs = new HashSet<>();

    private JTabbedPane burpTabbedPane;
    private Component ownPanel;
    private Timer refreshTimer;
    private Runnable onStateChanged;

    private boolean blurEnabled = true;
    private String blurStyle = STYLE_BLOCK;

    public TabManager(MontoyaApi api) {
        this.api = api;
        this.prefs = api.persistence().preferences();
        loadGlobalPreferences();
    }

    public void setOwnPanel(Component panel) {
        this.ownPanel = panel;
    }

    public void setOnStateChanged(Runnable callback) {
        this.onStateChanged = callback;
    }

    // =========================================================================
    //  Initialization & Discovery
    // =========================================================================

    public void start() {
        Timer initTimer = new Timer(1200, e -> tryInit(5));
        initTimer.setRepeats(false);
        initTimer.start();
    }

    private void tryInit(int retriesLeft) {
        findBurpTabbedPane();
        if (burpTabbedPane != null) {
            scanAndSyncTabs(true);
            startRefreshTimer();
            api.logging().logToOutput("[BurpExHide] Initialized successfully.");
        } else if (retriesLeft > 0) {
            Timer retry = new Timer(2000, e -> tryInit(retriesLeft - 1));
            retry.setRepeats(false);
            retry.start();
        } else {
            api.logging().logToError("[BurpExHide] Could not locate Burp Suite main tab pane.");
        }
    }

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
                            if (t != null && isBuiltinTab(t)) {
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

    private void startRefreshTimer() {
        if (refreshTimer != null) refreshTimer.stop();

        refreshTimer = new Timer(1500, e -> {
            if (burpTabbedPane == null) return;

            boolean needsSync = false;
            boolean needsHeal = false;

            for (int i = 0; i < burpTabbedPane.getTabCount(); i++) {
                Component comp = burpTabbedPane.getComponentAt(i);
                String currentTitle = burpTabbedPane.getTitleAt(i);
                if (comp == null || currentTitle == null || comp == ownPanel || isIgnoredOrBuiltin(currentTitle)) {
                    continue;
                }

                TabRecord rec = tabRegistry.get(comp);
                if (rec == null) {
                    needsSync = true;
                    break;
                } else if (blurEnabled && !getDesiredTitle(rec).equals(currentTitle)) {
                    needsHeal = true;
                }
            }

            if (needsSync) {
                scanAndSyncTabs(false);
            } else if (needsHeal) {
                applyAllTitles();
            }
        });
        refreshTimer.setRepeats(true);
        refreshTimer.start();
    }

    // =========================================================================
    //  Core Tracking & Title Updates
    // =========================================================================

    public void scanAndSyncTabs(boolean forceNotify) {
        if (burpTabbedPane == null) return;

        SwingUtilities.invokeLater(() -> {
            boolean changed = false;
            Set<Component> active = new HashSet<>();

            for (int i = 0; i < burpTabbedPane.getTabCount(); i++) {
                Component comp = burpTabbedPane.getComponentAt(i);
                String title = burpTabbedPane.getTitleAt(i);
                if (comp == null || title == null || comp == ownPanel || isIgnoredOrBuiltin(title)) {
                    continue;
                }

                active.add(comp);

                if (!tabRegistry.containsKey(comp)) {
                    String cleanName = title.trim();
                    TabRecord rec = new TabRecord(cleanName);
                    loadTabPreferences(rec);
                    tabRegistry.put(comp, rec);
                    changed = true;
                }
            }

            if (tabRegistry.keySet().removeIf(c -> !active.contains(c))) {
                changed = true;
            }

            if (blurEnabled) {
                applyAllTitles();
            }

            if (changed || forceNotify) {
                notifyUI();
            }
        });
    }

    public void applyAllTitles() {
        if (burpTabbedPane == null) return;

        SwingUtilities.invokeLater(() -> {
            for (int i = 0; i < burpTabbedPane.getTabCount(); i++) {
                Component comp = burpTabbedPane.getComponentAt(i);
                TabRecord rec = tabRegistry.get(comp);
                if (rec == null) continue;

                String desired = getDesiredTitle(rec);
                if (!desired.equals(burpTabbedPane.getTitleAt(i))) {
                    burpTabbedPane.setTitleAt(i, desired);
                }
            }
            notifyUI();
        });
    }

    public void restoreOriginalTitles() {
        if (burpTabbedPane == null) return;

        Runnable restore = () -> {
            for (int i = 0; i < burpTabbedPane.getTabCount(); i++) {
                Component comp = burpTabbedPane.getComponentAt(i);
                TabRecord rec = tabRegistry.get(comp);
                if (rec != null) {
                    burpTabbedPane.setTitleAt(i, rec.originalName);
                }
            }
        };

        if (SwingUtilities.isEventDispatchThread()) {
            restore.run();
        } else {
            SwingUtilities.invokeLater(restore);
        }
    }

    // =========================================================================
    //  Actions & Settings
    // =========================================================================

    public void setBlurEnabled(boolean enabled) {
        this.blurEnabled = enabled;
        prefs.setBoolean(PREF_BLUR_ENABLED, enabled);
        if (enabled) {
            applyAllTitles();
        } else {
            restoreOriginalTitles();
            notifyUI();
        }
    }

    public boolean isBlurEnabled() {
        return blurEnabled;
    }

    public void setBlurStyle(String style) {
        this.blurStyle = style;
        prefs.setString(PREF_BLUR_STYLE, style);
        if (blurEnabled) {
            applyAllTitles();
        }
    }

    public String getBlurStyle() {
        return blurStyle;
    }

    public void updateTabRecord(String originalName, String customName, boolean tabBlur) {
        for (TabRecord rec : tabRegistry.values()) {
            if (rec.originalName.equals(originalName)) {
                rec.customName = (customName == null) ? "" : customName.trim();
                rec.blurEnabled = tabBlur;
                saveTabPreferences(rec);
                break;
            }
        }
        if (blurEnabled) {
            applyAllTitles();
        }
    }

    public void setAllTabsBlur(boolean state) {
        for (TabRecord rec : tabRegistry.values()) {
            rec.blurEnabled = state;
            saveTabPreferences(rec);
        }
        if (blurEnabled) {
            applyAllTitles();
        } else if (!state) {
            restoreOriginalTitles();
        }
        notifyUI();
    }

    public void renameAll(String pattern) {
        String p = (pattern == null) ? "" : pattern.trim();
        int counter = 1;
        for (TabRecord rec : tabRegistry.values()) {
            if (p.isEmpty()) {
                rec.customName = "";
            } else if (p.contains("{n}")) {
                rec.customName = p.replace("{n}", String.valueOf(counter));
            } else {
                rec.customName = p;
            }
            saveTabPreferences(rec);
            counter++;
        }
        if (blurEnabled) applyAllTitles();
        notifyUI();
    }

    public void clearAllRenames() {
        for (TabRecord rec : tabRegistry.values()) {
            rec.customName = "";
            saveTabPreferences(rec);
        }
        if (blurEnabled) applyAllTitles();
        notifyUI();
    }

    public void ignoreTab(String originalName) {
        if (originalName == null || originalName.isBlank()) return;
        userIgnoredTabs.add(originalName.trim());
        saveIgnoredTabs();

        Component toRemove = null;
        for (Map.Entry<Component, TabRecord> entry : tabRegistry.entrySet()) {
            if (entry.getValue().originalName.equals(originalName)) {
                toRemove = entry.getKey();
                break;
            }
        }
        if (toRemove != null && burpTabbedPane != null) {
            int idx = burpTabbedPane.indexOfComponent(toRemove);
            if (idx >= 0) {
                burpTabbedPane.setTitleAt(idx, originalName);
            }
            tabRegistry.remove(toRemove);
        }
        notifyUI();
    }

    public void resetEverything() {
        blurEnabled = false;
        blurStyle = STYLE_BLOCK;
        userIgnoredTabs.clear();

        prefs.setBoolean(PREF_BLUR_ENABLED, false);
        prefs.setString(PREF_BLUR_STYLE, STYLE_BLOCK);
        saveIgnoredTabs();

        restoreOriginalTitles();

        for (TabRecord rec : tabRegistry.values()) {
            rec.customName = "";
            rec.blurEnabled = true;
            saveTabPreferences(rec);
        }

        scanAndSyncTabs(true);
    }

    public Collection<TabRecord> getTrackedTabs() {
        return tabRegistry.values();
    }

    public int getActiveHiddenCount() {
        int count = 0;
        for (TabRecord r : tabRegistry.values()) {
            if (r.blurEnabled) count++;
        }
        return count;
    }

    public void unload() {
        if (refreshTimer != null) {
            refreshTimer.stop();
            refreshTimer = null;
        }
        restoreOriginalTitles();
        tabRegistry.clear();
        api.logging().logToOutput("[BurpExHide] Unloaded cleanly.");
    }

    // =========================================================================
    //  Helpers & Persistence
    // =========================================================================

    private String getDesiredTitle(TabRecord rec) {
        if (!blurEnabled || !rec.blurEnabled) {
            return rec.originalName;
        }
        if (rec.customName != null && !rec.customName.isEmpty()) {
            return rec.customName;
        }
        return generateBlurString(rec.originalName.length());
    }

    private String generateBlurString(int len) {
        int targetLen = Math.max(len, 4);
        char ch = '\u2588';
        if (STYLE_ASTERISK.equals(blurStyle)) {
            ch = '*';
        } else if (STYLE_DOTS.equals(blurStyle)) {
            ch = '\u2022';
        } else if (STYLE_UNDERSCORES.equals(blurStyle)) {
            ch = '_';
        } else if (STYLE_BLANK.equals(blurStyle)) {
            ch = ' ';
        }
        char[] arr = new char[targetLen];
        Arrays.fill(arr, ch);
        return new String(arr);
    }

    private boolean isBuiltinTab(String title) {
        String t = title.trim();
        for (String b : BUILTIN_TABS) {
            if (b.equalsIgnoreCase(t)) return true;
        }
        return false;
    }

    private boolean isIgnoredOrBuiltin(String title) {
        if (title == null) return true;
        if (isBuiltinTab(title)) return true;
        String t = title.trim();
        for (String ignored : userIgnoredTabs) {
            if (ignored.equalsIgnoreCase(t)) return true;
        }
        return false;
    }

    private void loadGlobalPreferences() {
        Boolean savedBlur = prefs.getBoolean(PREF_BLUR_ENABLED);
        if (savedBlur != null) blurEnabled = savedBlur;

        String savedStyle = prefs.getString(PREF_BLUR_STYLE);
        if (savedStyle != null && !savedStyle.isBlank()) blurStyle = savedStyle;

        String savedIgnored = prefs.getString(PREF_IGNORED_TABS);
        if (savedIgnored != null && !savedIgnored.isBlank()) {
            for (String s : savedIgnored.split("\\|\\|")) {
                if (!s.isBlank()) userIgnoredTabs.add(s.trim());
            }
        }
    }

    private void saveIgnoredTabs() {
        prefs.setString(PREF_IGNORED_TABS, String.join("||", userIgnoredTabs));
    }

    private void loadTabPreferences(TabRecord rec) {
        String custom = prefs.getString(PREF_CUSTOM_PREFIX + rec.originalName);
        if (custom != null) rec.customName = custom;

        Boolean tabBlur = prefs.getBoolean(PREF_TAB_BLUR_PREFIX + rec.originalName);
        if (tabBlur != null) rec.blurEnabled = tabBlur;
    }

    private void saveTabPreferences(TabRecord rec) {
        prefs.setString(PREF_CUSTOM_PREFIX + rec.originalName, rec.customName);
        prefs.setBoolean(PREF_TAB_BLUR_PREFIX + rec.originalName, rec.blurEnabled);
    }

    private void notifyUI() {
        if (onStateChanged != null) {
            SwingUtilities.invokeLater(onStateChanged);
        }
    }
}
