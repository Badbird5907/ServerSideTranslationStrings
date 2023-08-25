package net.octomc.translationstringfix.listener;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.octomc.translationstringfix.TranslationStringFix;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;

public class TranslationStringListener implements Listener {
    @EventHandler(priority = EventPriority.HIGH)
    public void onAdvancement(PlayerAdvancementDoneEvent event) {
        Component message = event.message();
        if (message != null) {
            // event.message(TranslationStringFix.getInstance().translate(message));
        }
    }

    @EventHandler
    public void onSpawn(EntitySpawnEvent event) {
        if (event.getEntity() instanceof LivingEntity livingEntity) {
            Component customName = livingEntity.customName();
            // System.out.println("Spawned entity - " + customName + " | " + livingEntity.getName() + " | " + livingEntity.getType().name() + " | " + livingEntity.getType().translationKey());
            if (customName instanceof TranslatableComponent translatableComponent) {
                Component translated = TranslationStringFix.getInstance().translate(translatableComponent);
                event.getEntity().customName(translated);
            }
        }
    }
    @EventHandler
    public void onDamage(EntityDamageByEntityEvent event) {
        if (event.getEntity() instanceof LivingEntity livingEntity) {
            Component customName = livingEntity.customName();
            System.out.println("entity - " + customName + " | " + livingEntity.getName() + " | " + livingEntity.getType().name() + " | " + livingEntity.getType().translationKey());
        }
    }
}
