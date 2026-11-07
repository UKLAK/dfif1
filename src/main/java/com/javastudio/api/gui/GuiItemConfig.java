package com.javastudio.api.gui;

import java.util.List;

public record GuiItemConfig(
        int slot,
        String material,
        int amount,
        String displayName,
        List<String> lore,
        String actionType,
        String actionValue
) {}

