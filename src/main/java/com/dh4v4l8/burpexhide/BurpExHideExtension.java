package com.dh4v4l8.burpexhide;

import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;

import javax.swing.*;

public class BurpExHideExtension implements BurpExtension {

    @Override
    public void initialize(MontoyaApi api) {
        api.extension().setName("BurpExHide");

        TabManager tabManager = new TabManager(api);

        SwingUtilities.invokeLater(() -> {
            BurpExHideUI ui = new BurpExHideUI(tabManager);
            tabManager.setOwnPanel(ui);
            tabManager.setOnStateChanged(ui::refreshUI);

            api.userInterface().registerSuiteTab("BurpExHide", ui);
            tabManager.start();
        });

        api.extension().registerUnloadingHandler(tabManager::unload);
        api.logging().logToOutput("[BurpExHide] v3.0 loaded (Montoya API).");
    }
}
