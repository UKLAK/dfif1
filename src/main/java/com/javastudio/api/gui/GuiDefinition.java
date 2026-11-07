package com.javastudio.api.gui;

import java.util.List;
import java.util.Map;

public record GuiDefinition(
        String id,
        String title,
        int rows,
        Map<Integer, GuiItemConfig> items
) {}

