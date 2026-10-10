package dev.divinemace;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.LightningStrike;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Port moda "Divine Thunder Mace" (Fabric 26.1) na plugin Paper.
 *
 *  - PPM:        skok w gore (predkosc Y = 1.3), cooldown 10 s
 *  - W rece:     Sila III (main hand lub off hand)
 *  - Trafienie:  piorun w cel
 *  - Niezniszczalny, Density VI, Wind Burst IV, stack 1, bazowo mace (5 dmg, -3.4 speed)
 */
public final class DivineMacePlugin extends JavaPlugin implements Listener {

    private static final double LAUNCH_VELOCITY = 1.3;
    private static final long LAUNCH_COOLDOWN_MS = 10_000L;   // 200 tickow
    private static final int STRENGTH_AMPLIFIER = 2;          // Sila III

    private NamespacedKey markerKey;
    private NamespacedKey recipeKey;
    private NamespacedKey modelKey;
    private final Map<UUID, Long> cooldowns = new HashMap<>();

    @Override
    public void onEnable() {
        markerKey = new NamespacedKey(this, "divine_thunder_mace");
        recipeKey = new NamespacedKey(this, "divine_thunder_mace");
        modelKey = new NamespacedKey("divine-thunder-mace-hbfhtnw3", "divine_thunder_mace_hbfhtnw3");

        registerRecipe();
        getServer().getPluginManager().registerEvents(this, this);

        // Odswiezanie Sily III co 5 tickow dla graczy trzymajacych buławę
        getServer().getScheduler().runTaskTimer(this, this::tickStrength, 20L, 5L);
    }

    @Override
    public void onDisable() {
        getServer().removeRecipe(recipeKey);
    }

    // ---------------------------------------------------------------- item

    public ItemStack createMace() {
        ItemStack item = new ItemStack(Material.MACE);
        item.editMeta(meta -> {
            meta.displayName(
                    Component.text("Divine ", NamedTextColor.GOLD)
                            .append(Component.text("Thunder ", NamedTextColor.AQUA))
                            .append(Component.text("Mace", NamedTextColor.LIGHT_PURPLE))
                            .decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(
                    Component.text("A storm-forged ", NamedTextColor.AQUA)
                            .append(Component.text("weapon that ", NamedTextColor.GOLD))
                            .append(Component.text("empowers its wielder.", NamedTextColor.LIGHT_PURPLE))
                            .decoration(TextDecoration.ITALIC, false),
                    Component.text("Right-click: leap skyward (10s cooldown). Held: Strength III. Hits: lightning.",
                            NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false)));
            meta.setUnbreakable(true);
            meta.addEnchant(Enchantment.DENSITY, 6, true);
            meta.addEnchant(Enchantment.WIND_BURST, 4, true);
            // Wlasny model/tekstura - wymaga resource packa z folderu resourcepack/
            meta.setItemModel(modelKey);
            meta.getPersistentDataContainer().set(markerKey, PersistentDataType.BYTE, (byte) 1);
        });
        return item;
    }

    private boolean isMace(ItemStack item) {
        return item != null
                && item.getType() == Material.MACE
                && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(markerKey, PersistentDataType.BYTE);
    }

    private void registerRecipe() {
        ShapedRecipe recipe = new ShapedRecipe(recipeKey, createMace());
        recipe.shape(" AB", "CDA", "EC ");
        recipe.setIngredient('A', Material.NETHERITE_INGOT);
        recipe.setIngredient('B', Material.TOTEM_OF_UNDYING);
        recipe.setIngredient('C', Material.DIAMOND_BLOCK);
        recipe.setIngredient('D', Material.MACE);
        recipe.setIngredient('E', Material.BLAZE_ROD);
        getServer().addRecipe(recipe);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        event.getPlayer().discoverRecipe(recipeKey);
    }

    // ------------------------------------------------------------ abilities

    /** PPM: skok w gore. */
    @EventHandler(ignoreCancelled = false)
    public void onUse(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;

        Player player = event.getPlayer();
        ItemStack item = player.getInventory().getItemInMainHand();
        if (!isMace(item)) return;

        // Nie przeszkadzaj w otwieraniu skrzynek, drzwi itp.
        if (action == Action.RIGHT_CLICK_BLOCK && !player.isSneaking()) {
            Block block = event.getClickedBlock();
            if (block != null && block.getType().isInteractable()) return;
        }

        long now = System.currentTimeMillis();
        Long readyAt = cooldowns.get(player.getUniqueId());
        if (readyAt != null && now < readyAt) return;
        cooldowns.put(player.getUniqueId(), now + LAUNCH_COOLDOWN_MS);

        Vector v = player.getVelocity();
        player.setVelocity(new Vector(v.getX(), LAUNCH_VELOCITY, v.getZ()));
        event.setCancelled(true);
    }

    /** Trafienie: piorun w cel. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) return;
        if (!(event.getEntity() instanceof LivingEntity target)) return;
        if (!isMace(player.getInventory().getItemInMainHand())) return;

        LightningStrike bolt = target.getWorld().strikeLightning(target.getLocation());
        bolt.setCausingPlayer(player);
    }

    /** Sila III dla trzymajacych buławę w main hand lub off hand. */
    private void tickStrength() {
        for (Player player : getServer().getOnlinePlayers()) {
            if (!isMace(player.getInventory().getItemInMainHand())
                    && !isMace(player.getInventory().getItemInOffHand())) continue;

            PotionEffect existing = player.getPotionEffect(PotionEffectType.STRENGTH);
            if (existing != null && existing.getAmplifier() > STRENGTH_AMPLIFIER) continue;
            player.addPotionEffect(new PotionEffect(
                    PotionEffectType.STRENGTH, 20, STRENGTH_AMPLIFIER, true, false, true));
        }
    }

    // -------------------------------------------------------------- command

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || !args[0].equalsIgnoreCase("give")) {
            sender.sendMessage(Component.text("Uzycie: /" + label + " give [gracz]", NamedTextColor.RED));
            return true;
        }
        if (!sender.hasPermission("divinemace.give")) {
            sender.sendMessage(Component.text("Brak uprawnien.", NamedTextColor.RED));
            return true;
        }
        Player target;
        if (args.length >= 2) {
            target = getServer().getPlayerExact(args[1]);
        } else if (sender instanceof Player p) {
            target = p;
        } else {
            sender.sendMessage(Component.text("Podaj gracza.", NamedTextColor.RED));
            return true;
        }
        if (target == null) {
            sender.sendMessage(Component.text("Nie ma takiego gracza online.", NamedTextColor.RED));
            return true;
        }
        target.getInventory().addItem(createMace()).values()
                .forEach(left -> target.getWorld().dropItemNaturally(target.getLocation(), left));
        sender.sendMessage(Component.text("Dano Divine Thunder Mace graczowi " + target.getName() + ".",
                NamedTextColor.GREEN));
        return true;
    }
}
