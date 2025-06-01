package me.leonohnex.carryOnPlugin;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.*;

public class CarryOnPlugin extends JavaPlugin implements Listener {

    private final Map<UUID, CarriedEntity> carriedEntities = new HashMap<>();
    private final Set<EntityType> blacklistedEntities = new HashSet<>();
    // Map für Sneak-Timestamps (für Doppelsneak)
    private final Map<UUID, Long> sneakTimestamps = new HashMap<>();
    // Zeitfenster für Doppelsneak in Millisekunden
    private static final long DOUBLE_SNEAK_WINDOW = 1500;

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        setupBlacklist();
        getLogger().info("CarryOn Mob Plugin wurde aktiviert!");

        // Command für Reload
        Objects.requireNonNull(getCommand("carryreload")).setExecutor((sender, command, label, args) -> {
            if (sender.hasPermission("carryon.reload")) {
                setupBlacklist();
                sender.sendMessage(ChatColor.GREEN + "CarryOn Plugin wurde neu geladen!");
            }
            return true;
        });
    }

    @Override
    public void onDisable() {
        // Alle getragenen Entities fallen lassen
        for (UUID playerId : new HashSet<>(carriedEntities.keySet())) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) {
                dropCarriedEntity(player);
            }
        }
        getLogger().info("CarryOn Mob Plugin wurde deaktiviert!");
    }

    private void setupBlacklist() {
        blacklistedEntities.clear();
        blacklistedEntities.addAll(Arrays.asList(
            EntityType.PLAYER,
            EntityType.ENDER_DRAGON,
            EntityType.WITHER,
            EntityType.GIANT,
            EntityType.WARDEN,
            EntityType.ELDER_GUARDIAN,
            EntityType.GHAST,
            EntityType.RAVAGER,
            EntityType.IRON_GOLEM,
            EntityType.SNOW_GOLEM,
            EntityType.VILLAGER,
            EntityType.WANDERING_TRADER,
            EntityType.WITCH,
            EntityType.EVOKER,
            EntityType.VINDICATOR,
            EntityType.PILLAGER
        ));
    }

    @EventHandler
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player)) return;

        Player player = (Player) event.getDamager();
        Entity entity = event.getEntity();

        // Nur bei Shift+Rechtsklick (Punch mit leerem Hand während sneaken)
        if (!player.isSneaking()) return;
        if (player.getInventory().getItemInMainHand().getType() != org.bukkit.Material.AIR) return;

        // Prüfe ob Spieler bereits etwas trägt
        if (carriedEntities.containsKey(player.getUniqueId())) {
            player.sendMessage(ChatColor.RED + "Du trägst bereits ein Mob!");
            event.setCancelled(true);
            return;
        }

        // Prüfe Entity-Typ
        if (blacklistedEntities.contains(entity.getType())) {
            player.sendMessage(ChatColor.RED + "Dieser Mob kann nicht getragen werden!");
            event.setCancelled(true);
            return;
        }

        // Prüfe ob Entity zu groß/schwer ist
        if (entity instanceof LivingEntity) {
            LivingEntity living = (LivingEntity) entity;
            if (living.getHealth() > 100) { // Sehr starke Mobs
                player.sendMessage(ChatColor.RED + "Dieser Mob ist zu stark zum Tragen!");
                event.setCancelled(true);
                return;
            }
        }

        // Prüfe Permissions
        if (!player.hasPermission("carryon.entity")) {
            player.sendMessage(ChatColor.RED + "Du hast keine Berechtigung, Mobs zu tragen!");
            event.setCancelled(true);
            return;
        }

        event.setCancelled(true);
        pickupEntity(player, entity);
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();

        if (!carriedEntities.containsKey(playerId)) return;

        CarriedEntity carried = carriedEntities.get(playerId);

        // Update Entity Position über dem Spieler
        Location playerLoc = player.getLocation();
        Location entityLoc = playerLoc.clone().add(0, 2.2, 0);

        // Sanfte Bewegung des Entities
        carried.entity.teleport(entityLoc);
        carried.entity.setVelocity(new Vector(0, 0, 0));

        // Verhindere Entity AI
        if (carried.entity instanceof LivingEntity) {
            LivingEntity living = (LivingEntity) carried.entity;
            living.setAI(false);
        }

        // Partikel-Effekt
        if (Math.random() < 0.3) { // 30% Chance pro Move
            player.getWorld().spawnParticle(
                org.bukkit.Particle.HEART,
                entityLoc,
                1,
                0.2, 0.2, 0.2,
                0.1
            );
        }
    }

    @EventHandler
    public void onPlayerToggleSneak(PlayerToggleSneakEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();

        if (!carriedEntities.containsKey(playerId)) return;

        // Nur auf Sneak-Start reagieren (nicht Sneak-Ende)
        if (event.isSneaking()) {
            long now = System.currentTimeMillis();
            Long lastSneak = sneakTimestamps.get(playerId);

            if (lastSneak != null && (now - lastSneak) <= DOUBLE_SNEAK_WINDOW) {
                // Doppelsneak erkannt, Mob loslassen
                dropCarriedEntity(player);
                sneakTimestamps.remove(playerId);
            } else {
                // Erstes Sneaken, Zeit merken
                sneakTimestamps.put(playerId, now);
                player.sendMessage(ChatColor.GRAY + "Nochmal sneaken zum Loslassen!");
            }
        }
    }

    @EventHandler
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        if (carriedEntities.containsKey(player.getUniqueId())) {
            dropCarriedEntity(player);
            sneakTimestamps.remove(player.getUniqueId());
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (carriedEntities.containsKey(player.getUniqueId())) {
            dropCarriedEntity(player);
            sneakTimestamps.remove(player.getUniqueId());
        }
    }

    private void pickupEntity(Player player, Entity entity) {
        // Speichere ursprüngliche Entity-Daten
        Location originalLoc = entity.getLocation().clone();
        boolean originalAI = true;
        if (entity instanceof LivingEntity) {
            originalAI = ((LivingEntity) entity).hasAI();
        }

        CarriedEntity carried = new CarriedEntity(entity, originalLoc, originalAI);
        carriedEntities.put(player.getUniqueId(), carried);

        // Deaktiviere Entity AI und Physik
        entity.setGravity(false);
        if (entity instanceof LivingEntity) {
            ((LivingEntity) entity).setAI(false);
        }

        // Visual feedback
        String entityName = entity.getType().name().toLowerCase().replace("_", " ");
        player.sendMessage(ChatColor.GREEN + "Mob aufgehoben: " + entityName);
        player.sendMessage(ChatColor.YELLOW + "Hör auf zu sneaken oder drücke Q zum Loslassen!");
        player.sendTitle("", ChatColor.YELLOW + "Trägst: " + entityName, 10, 60, 10);

        // Effekt-Sound
        player.getWorld().playSound(player.getLocation(), org.bukkit.Sound.ENTITY_ITEM_PICKUP, 1.0f, 1.2f);

        // Überwache das Tragen
        startCarryMonitoring(player);
    }

    private void dropCarriedEntity(Player player) {
        CarriedEntity carried = carriedEntities.remove(player.getUniqueId());
        if (carried == null) return;

        // Finde sicheren Landeplatz
        Location dropLoc = findSafeDropLocation(player.getLocation());

        // Teleportiere Entity
        carried.entity.teleport(dropLoc);

        // Restauriere Entity-Eigenschaften
        carried.entity.setGravity(true);
        if (carried.entity instanceof LivingEntity) {
            ((LivingEntity) carried.entity).setAI(carried.originalAI);
        }

        // Kleine Aufprall-Bewegung
        carried.entity.setVelocity(new Vector(
            (Math.random() - 0.5) * 0.2,
            0.1,
            (Math.random() - 0.5) * 0.2
        ));

        String entityName = carried.entity.getType().name().toLowerCase().replace("_", " ");
        player.sendMessage(ChatColor.YELLOW + "Mob losgelassen: " + entityName);
        player.sendTitle("", "", 0, 0, 0);

        // Sound
        player.getWorld().playSound(dropLoc, org.bukkit.Sound.ENTITY_ITEM_PICKUP, 1.0f, 0.8f);
    }

    private Location findSafeDropLocation(Location playerLoc) {
        Location safeLoc = playerLoc.clone();

        // Suche nach festem Boden unter dem Spieler
        for (int y = 0; y < 10; y++) {
            Location checkLoc = safeLoc.clone().subtract(0, y, 0);
            if (checkLoc.getBlock().getType().isSolid()) {
                return checkLoc.add(0, 1, 0); // Einen Block über dem festen Boden
            }
        }

        return safeLoc; // Fallback zur Spielerposition
    }

    private void startCarryMonitoring(Player player) {
        new BukkitRunnable() {
            int ticks = 0;

            @Override
            public void run() {
                if (!carriedEntities.containsKey(player.getUniqueId()) || !player.isOnline()) {
                    cancel();
                    return;
                }

                ticks++;

                // Alle 5 Sekunden prüfen ob Entity noch existiert
                if (ticks % 100 == 0) {
                    CarriedEntity carried = carriedEntities.get(player.getUniqueId());
                    if (carried != null && (carried.entity.isDead() || !carried.entity.isValid())) {
                        carriedEntities.remove(player.getUniqueId());
                        player.sendMessage(ChatColor.RED + "Das getragene Mob ist verschwunden!");
                        player.sendTitle("", "", 0, 0, 0);
                        cancel();
                    }
                }

                // Geschwindigkeits-Begrenzung beim Tragen
                if (player.getVelocity().length() > 0.5) {
                    Vector limited = player.getVelocity().normalize().multiply(0.5);
                    player.setVelocity(limited);
                }
            }
        }.runTaskTimer(this, 0, 1); // Jede Tick
    }

    // Inner Class
    private static class CarriedEntity {
        final Entity entity;
        final Location originalLocation;
        final boolean originalAI;

        CarriedEntity(Entity entity, Location originalLocation, boolean originalAI) {
            this.entity = entity;
            this.originalLocation = originalLocation;
            this.originalAI = originalAI;
        }
    }
}
