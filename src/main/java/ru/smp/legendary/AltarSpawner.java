package ru.smp.legendary;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Расстановка алтарей по карте: в указанных координатах или в случайных безопасных местах.
 * Случайный поиск идёт по одной попытке за тик, чтобы не вешать сервер подгрузкой чанков.
 */
public final class AltarSpawner {

    private final LegendaryPlugin plugin;
    private final Random rnd = new Random();
    private boolean busy = false;

    public AltarSpawner(LegendaryPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean busy() {
        return busy;
    }

    private void say(CommandSender s, String text) {
        if (s != null) {
            s.sendMessage(Util.color(plugin.getConfig().getString("messages.prefix", "")) + Util.color(text));
        }
    }

    /** Строит и регистрирует алтарь на самой верхней поверхности в точке (x, z). */
    public AltarManager.Altar spawnAt(CommandSender by, String type, World w, int x, int z) {
        AltarManager am = plugin.altars();
        AltarManager.Theme t = am.theme(type);
        if (t == null) {
            return null;
        }
        w.getChunkAt(x >> 4, z >> 4).load(true);
        int y = w.getHighestBlockYAt(x, z);
        Block ground = w.getBlockAt(x, y, z);
        Block center = AltarBuilder.build(plugin, ground, t, type);
        AltarManager.Altar a = am.create(type, center);
        plugin.adminLog(by == null ? "system" : by.getName(), "altar_spawn",
                type + " #" + (a == null ? -1 : a.id) + " " + w.getName() + " " + x + " " + y + " " + z);
        return a;
    }

    private boolean suitable(World w, int x, int z, int R) {
        int y = w.getHighestBlockYAt(x, z);
        Block g = w.getBlockAt(x, y, z);
        Material m = g.getType();
        if (m == Material.WATER || m == Material.LAVA || g.isLiquid() || m == Material.ICE || m == Material.PACKED_ICE
                || m == Material.BLUE_ICE || m.name().endsWith("LEAVES") || m.name().contains("LOG") || m.name().contains("MUSHROOM")) {
            return false;
        }
        String biome = g.getBiome().name();
        if (biome.contains("OCEAN") || biome.contains("RIVER") || biome.contains("BEACH")) {
            return false;
        }
        if (y < w.getMinHeight() + 5 || y > w.getMaxHeight() - 40) {
            return false;
        }
        int[][] corners = {{R, 0}, {-R, 0}, {0, R}, {0, -R}};
        for (int[] c : corners) {
            int cy = w.getHighestBlockYAt(x + c[0], z + c[1]);
            Block cg = w.getBlockAt(x + c[0], cy, z + c[1]);
            if (cg.isLiquid() || Math.abs(cy - y) > plugin.getConfig().getInt("altars.random.max_slope", 6)) {
                return false;
            }
        }
        return true;
    }

    private boolean farEnough(World w, int x, int z, int minDist, List<int[]> pending) {
        for (AltarManager.Altar a : plugin.altars().all()) {
            if (a.world.equals(w.getName())) {
                long dx = a.x - x, dz = a.z - z;
                if (dx * dx + dz * dz < (long) minDist * minDist) {
                    return false;
                }
            }
        }
        for (int[] p : pending) {
            long dx = p[0] - x, dz = p[1] - z;
            if (dx * dx + dz * dz < (long) minDist * minDist) {
                return false;
            }
        }
        return true;
    }

    /**
     * Случайная расстановка. Для каждого типа из списка ищется подходящее место в кольце вокруг центра мира.
     * @param types типы алтарей (id оружия)
     */
    public void random(CommandSender by, List<String> types, World w) {
        if (busy) {
            say(by, "&cРасстановка уже идёт, подождите.");
            return;
        }
        final int radius = plugin.getConfig().getInt("altars.random.radius", 2500);
        final int minFromSpawn = plugin.getConfig().getInt("altars.random.min_from_spawn", 300);
        final int minDist = plugin.getConfig().getInt("altars.random.min_distance", 600);
        final int maxTries = plugin.getConfig().getInt("altars.random.max_tries", 400);
        final int R = AltarBuilder.radius(plugin);
        final int cx = w.getSpawnLocation().getBlockX();
        final int cz = w.getSpawnLocation().getBlockZ();
        final List<String> queue = new ArrayList<>(types);
        final List<int[]> placed = new ArrayList<>();
        busy = true;
        say(by, "&7Ищу места для алтарей (" + queue.size() + " шт.), радиус " + radius + " блоков от центра...");

        new BukkitRunnable() {
            int tries = 0;

            @Override
            public void run() {
                if (queue.isEmpty() || tries >= maxTries) {
                    busy = false;
                    cancel();
                    if (!queue.isEmpty()) {
                        say(by, "&eНе нашлось места для: &f" + String.join(", ", queue)
                                + "&e. Увеличьте altars.random.radius или уменьшите min_distance.");
                    }
                    say(by, "&aГотово: расставлено " + placed.size() + ". Список: &f/legendary altar list");
                    return;
                }
                tries++;
                double ang = rnd.nextDouble() * Math.PI * 2;
                double dist = minFromSpawn + rnd.nextDouble() * Math.max(1, radius - minFromSpawn);
                int x = cx + (int) Math.round(Math.cos(ang) * dist);
                int z = cz + (int) Math.round(Math.sin(ang) * dist);
                if (!farEnough(w, x, z, minDist, placed)) {
                    return;
                }
                w.getChunkAt(x >> 4, z >> 4).load(true);
                if (!suitable(w, x, z, R)) {
                    return;
                }
                String type = queue.remove(0);
                AltarManager.Altar a = spawnAt(by, type, w, x, z);
                if (a == null) {
                    return;
                }
                placed.add(new int[]{x, z});
                tries = 0;
                say(by, "&aАлтарь &f" + type + " &a#" + a.id + ": &f" + a.x + " " + a.y + " " + a.z);
            }
        }.runTaskTimer(plugin, 1L, 2L);
    }

    public static List<String> typeList(LegendaryPlugin plugin, String arg) {
        List<String> all = new ArrayList<>(plugin.items().ritualItems().keySet());
        if (arg == null || arg.equalsIgnoreCase("all")) {
            return all;
        }
        String id = arg.toLowerCase(Locale.ROOT);
        return all.contains(id) ? new ArrayList<>(List.of(id)) : new ArrayList<>();
    }
}
