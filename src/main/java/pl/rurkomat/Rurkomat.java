package pl.rurkomat;

import com.earth2me.essentials.Essentials;
import com.earth2me.essentials.Kit;
import com.earth2me.essentials.Trade;
import com.earth2me.essentials.User;
import com.earth2me.essentials.utils.NumberUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

public final class Rurkomat extends JavaPlugin implements Listener {
    private static final Component MENU_TITLE = MiniMessage.miniMessage()
        .deserialize("<gradient:#58d68d:#2ecc71><bold>Rurkomat</bold></gradient>");
    private static final Material BORDER = Material.BLACK_STAINED_GLASS_PANE;
    private static final int PAGE_SIZE = 28;
    private static final int ROW_WIDTH = 9;
    private static final int KITS_PER_ROW = 7;
    private static final Pattern KIT_ARGUMENT = Pattern.compile("[^,§\\s\\p{Cntrl}]+", Pattern.UNICODE_CHARACTER_CLASS);
    private Essentials essentials;

    @Override
    public void onEnable() {
        essentials = (Essentials) getServer().getPluginManager().getPlugin("Essentials");
        getServer().getPluginManager().registerEvents(this, this);
    }

    @Override
    public void onDisable() {
        for (Player player : getServer().getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder(false) instanceof KitMenu) {
                player.closeInventory();
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onKitsCommand(PlayerCommandPreprocessEvent event) {
        if (!event.getMessage().stripTrailing().equalsIgnoreCase("/kits")) {
            return;
        }
        if (!isEssentialsAlias()) {
            return;
        }
        if (openMenu(event.getPlayer(), 0)) {
            event.setCancelled(true);
        }
    }

    private boolean isEssentialsAlias() {
        var commands = getServer().getCommandMap();
        var kitCommand = commands.getCommand("essentials:kit");
        return kitCommand != null && commands.getCommand("kits") == kitCommand;
    }

    private boolean openMenu(Player player, int requestedPage) {
        User user = essentials.getUser(player);
        if (user == null || !user.isAuthorized("essentials.kit") || essentials.getSettings().isCommandDisabled("kit")) {
            return false;
        }

        List<KitEntry> kits = new ArrayList<>();
        for (String key : essentials.getKits().getKitKeys()) {
            if (!KIT_ARGUMENT.matcher(key).matches() || !user.isAuthorized("essentials.kits." + key.toLowerCase(Locale.ROOT))) {
                continue;
            }
            try {
                Kit kit = new Kit(key, essentials);
                long nextUse = kit.getNextUse(user);
                if (nextUse == -1 && essentials.getSettings().isSkippingUsedOneTimeKitsFromKitList()) {
                    continue;
                }
                BigDecimal cost = new Trade("kit-" + kit.getName(), essentials).getCommandCost(user);
                kits.add(new KitEntry(key, nextUse, cost, findIcon(kit)));
            } catch (Exception exception) {
                getLogger().fine("Cannot show EssentialsX kit " + key + ": " + exception.getMessage());
            }
        }

        int pageCount = Math.max(1, Math.ceilDiv(kits.size(), PAGE_SIZE));
        int page = Math.min(requestedPage, pageCount - 1);
        int visibleRows = Math.max(1, Math.ceilDiv(Math.min(kits.size(), PAGE_SIZE), KITS_PER_ROW));
        KitMenu menu = new KitMenu(page, pageCount, visibleRows + 2);
        Inventory inventory = menu.getInventory();
        ItemStack border = item(BORDER, Component.empty(), List.of());
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            int column = slot % ROW_WIDTH;
            if (slot < ROW_WIDTH || slot >= menu.footer || column == 0 || column == ROW_WIDTH - 1) {
                inventory.setItem(slot, border);
            }
        }
        long now = System.currentTimeMillis();
        int start = page * PAGE_SIZE;
        int end = Math.min(start + PAGE_SIZE, kits.size());
        for (int index = start; index < end; index++) {
            KitEntry entry = kits.get(index);
            int position = index - start;
            int slot = ROW_WIDTH + 1 + position / KITS_PER_ROW * ROW_WIDTH + position % KITS_PER_ROW;
            inventory.setItem(slot, kitItem(entry, now));
            menu.entries.put(slot, entry.key());
        }
        if (kits.isEmpty()) {
            inventory.setItem(13, item(Material.FLOWER_POT, text("Brak dostępnych zestawów", NamedTextColor.GRAY), List.of()));
        }
        inventory.setItem(menu.footer + 4, item(Material.BARRIER, text("Zamknij", NamedTextColor.RED), List.of()));
        if (page > 0) {
            inventory.setItem(menu.footer, item(Material.ARROW, text("Poprzednia strona", NamedTextColor.GREEN), List.of()));
        }
        if (page + 1 < pageCount) {
            inventory.setItem(menu.footer + 8, item(Material.ARROW, text("Następna strona", NamedTextColor.GREEN), List.of()));
        }
        player.openInventory(inventory);
        return true;
    }

    private Material findIcon(Kit kit) throws Exception {
        for (String line : kit.getItems()) {
            String entry = line.strip();
            if (entry.isEmpty() || entry.startsWith("/") || entry.startsWith("@")) {
                continue;
            }
            String[] arguments = entry.split("\\s+", 3);
            int itemIndex = arguments[0].startsWith("slot:") ? 1 : 0;
            if (itemIndex < arguments.length) {
                try {
                    Material material = essentials.getItemDb().get(arguments[itemIndex]).getType();
                    if (material.isItem() && !material.isAir()) {
                        return material;
                    }
                } catch (Exception ignored) {
                    // Serialized or custom items may have no material token to use as an icon.
                }
            }
        }
        return Material.CHEST;
    }

    private ItemStack kitItem(KitEntry kit, long now) {
        List<Component> lore = new ArrayList<>();
        if (kit.nextUse() == -1) {
            lore.add(text("Odebrano już ten zestaw", NamedTextColor.RED));
        } else if (kit.nextUse() > now) {
            Duration remaining = Duration.ofMillis(kit.nextUse() - now).plusMillis(999);
            String duration = remaining.toHours() > 0
                ? remaining.toHours() + " godz. " + remaining.toMinutesPart() + " min"
                : remaining.toMinutesPart() + " min " + remaining.toSecondsPart() + " s";
            lore.add(text("Ponowny odbiór za " + duration, NamedTextColor.YELLOW));
        } else {
            lore.add(text("Gotowy do odbioru", NamedTextColor.GREEN));
        }
        if (kit.cost().signum() > 0) {
            lore.add(text("Koszt: " + NumberUtil.displayCurrency(kit.cost(), essentials), NamedTextColor.GOLD));
        }
        lore.add(text("Kliknij, aby odebrać", NamedTextColor.AQUA));
        String name = kit.key().replace('_', ' ').replace('-', ' ');
        name = name.substring(0, 1).toUpperCase(Locale.ROOT) + name.substring(1);
        return item(kit.icon(), text(name, NamedTextColor.WHITE).decorate(TextDecoration.BOLD), lore);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMenuClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder(false) instanceof KitMenu menu)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)
            || (event.getClick() != ClickType.LEFT && event.getClick() != ClickType.RIGHT)) {
            return;
        }
        int slot = event.getRawSlot();
        String kit = menu.entries.get(slot);
        int targetPage = menu.page;
        boolean close = slot == menu.footer + 4;
        if (slot == menu.footer) {
            targetPage--;
        } else if (slot == menu.footer + 8) {
            targetPage++;
        }
        boolean changePage = targetPage != menu.page && targetPage >= 0 && targetPage < menu.pageCount;
        if (kit == null && !close && !changePage) {
            return;
        }
        int page = targetPage;
        getServer().getScheduler().runTask(this, () -> {
            if (!player.isOnline() || player.getOpenInventory().getTopInventory().getHolder(false) != menu) {
                return;
            }
            if (changePage && openMenu(player, page)) {
                return;
            }
            player.closeInventory();
            if (kit != null && isEssentialsAlias() && essentials.getKits().getKitKeys().contains(kit)) {
                player.chat("/kits " + kit);
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMenuDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof KitMenu) {
            event.setCancelled(true);
        }
    }

    private static ItemStack item(Material material, Component name, List<Component> lore) {
        ItemStack stack = ItemStack.of(material);
        stack.editMeta(meta -> {
            meta.displayName(name);
            meta.lore(lore);
            meta.setHideTooltip(material == BORDER);
        });
        return stack;
    }

    private static Component text(String content, NamedTextColor color) {
        return Component.text(content, color).decoration(TextDecoration.ITALIC, false);
    }

    private record KitEntry(String key, long nextUse, BigDecimal cost, Material icon) {}

    private static final class KitMenu implements InventoryHolder {
        private final int page;
        private final int pageCount;
        private final int footer;
        private final Map<Integer, String> entries = new HashMap<>();
        private final Inventory inventory;

        private KitMenu(int page, int pageCount, int rows) {
            this.page = page;
            this.pageCount = pageCount;
            this.footer = (rows - 1) * ROW_WIDTH;
            Component title = MENU_TITLE;
            if (pageCount > 1) {
                title = title.append(text(" • " + (page + 1) + "/" + pageCount, NamedTextColor.DARK_GRAY));
            }
            inventory = Bukkit.createInventory(this, rows * ROW_WIDTH, title);
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
