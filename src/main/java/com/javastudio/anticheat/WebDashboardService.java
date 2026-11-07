package com.javastudio.anticheat;

import org.bukkit.entity.Player;

public interface WebDashboardService {

    void loadConfig();

    void start();

    void stop();

    boolean isRunning();

    int getPort();

    String getAuthToken();

    default int getInternalPort() {
        return getPort();
    }

    default boolean isSinglePortActive() {
        return false;
    }

    default String getPublicAccessUrl() {
        return "http://localhost:" + getPort() + "/anticheat?token=" + getAuthToken();
    }

    default String getPublicAccessUrl(Player viewer) {
        return getPublicAccessUrl();
    }
}
