package com.javastudio.api.ability;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.function.BiConsumer;
import java.util.function.BiPredicate;

public final class ItemAbility {

    private final String id;
    private final String name;
    private final AbilityTrigger trigger;
    private final double cooldownSeconds;
    private final BiPredicate<Player, ItemStack> condition;
    private final BiConsumer<Player, ItemStack> action;

    private ItemAbility(Builder builder) {
        this.id = builder.id;
        this.name = builder.name != null ? builder.name : builder.id;
        this.trigger = builder.trigger != null ? builder.trigger : AbilityTrigger.RIGHT_CLICK;
        this.cooldownSeconds = builder.cooldownSeconds;
        this.condition = builder.condition;
        this.action = builder.action;
    }

    public static Builder builder(String id) {
        return new Builder(id);
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public AbilityTrigger getTrigger() { return trigger; }
    public double getCooldownSeconds() { return cooldownSeconds; }

    public boolean test(Player player, ItemStack item) {
        return condition == null || condition.test(player, item);
    }

    public void execute(Player player, ItemStack item) {
        if (action != null) {
            action.accept(player, item);
        }
    }

    public static final class Builder {
        private final String id;
        private String name;
        private AbilityTrigger trigger = AbilityTrigger.RIGHT_CLICK;
        private double cooldownSeconds = 0.0;
        private BiPredicate<Player, ItemStack> condition;
        private BiConsumer<Player, ItemStack> action;

        public Builder(String id) {
            this.id = id;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder trigger(AbilityTrigger trigger) {
            this.trigger = trigger;
            return this;
        }

        public Builder cooldown(double seconds) {
            this.cooldownSeconds = Math.max(0.0, seconds);
            return this;
        }

        public Builder condition(BiPredicate<Player, ItemStack> condition) {
            this.condition = condition;
            return this;
        }

        public Builder onActivate(BiConsumer<Player, ItemStack> action) {
            this.action = action;
            return this;
        }

        public ItemAbility build() {
            if (id == null || id.trim().isEmpty()) {
                throw new IllegalArgumentException("ID kỹ năng không được để trống!");
            }
            return new ItemAbility(this);
        }
    }
}
