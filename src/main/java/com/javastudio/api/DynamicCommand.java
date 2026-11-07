package com.javastudio.api;

import org.bukkit.command.CommandSender;

import java.util.Collections;
import java.util.List;

@FunctionalInterface
public interface DynamicCommand {

    boolean execute(CommandSender sender, String[] args);

    default List<String> tabComplete(CommandSender sender, String[] args) {
        return Collections.emptyList();
    }
}
