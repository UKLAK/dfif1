package com.javastudio.api;

import org.bukkit.command.CommandSender;

import java.util.Collections;
import java.util.List;

/**
 * Interface hỗ trợ tạo lệnh động trong dynamic plugin.
 */
@FunctionalInterface
public interface DynamicCommand {

    /**
     * Thực thi lệnh khi người chơi hoặc console gõ.
     *
     * @param sender Người gửi lệnh (Player hoặc Console)
     * @param args   Các đối số truyền vào
     * @return true nếu lệnh thành công, false nếu sai cú pháp
     */
    boolean execute(CommandSender sender, String[] args);

    /**
     * Gợi ý tab-complete khi gõ lệnh (tùy chọn ghi đè)
     */
    default List<String> tabComplete(CommandSender sender, String[] args) {
        return Collections.emptyList();
    }
}
