package com.javastudio.web;

import org.bukkit.command.CommandSender;

import java.util.List;

public interface WebExtensionController {

    void enable() throws Throwable;

    void disable();

    boolean handleCommand(CommandSender sender, String label, String[] args);

    List<String> handleTabComplete(CommandSender sender, String[] args);
}

