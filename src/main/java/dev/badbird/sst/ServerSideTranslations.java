package dev.badbird.sst;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.reflect.StructureModifier;
import com.comphenix.protocol.wrappers.*;
import com.google.gson.*;
import dev.badbird.sst.util.Metrics;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class ServerSideTranslations extends JavaPlugin {
    private Map<String, Component> translations = new HashMap<>();
    private ProtocolManager protocolManager;
    private static ServerSideTranslations instance;

    private static Gson gson = new GsonBuilder().setLenient().create();
    private static Pattern mcFunctionJsonPattern = Pattern.compile("data modify entity .* set value \\'\\{.*\\}\\'");


    public static ServerSideTranslations getInstance() {
        return instance;
    }

    @Override
    public void onLoad() {
        instance = this;
        protocolManager = ProtocolLibrary.getProtocolManager();
    }

    @Override
    public void onEnable() {
        String folderOverride = System.getProperty("sst.datapackFolder", "");
        File datapackFolder;
        if (folderOverride.isEmpty()) {
            datapackFolder = new File(getServer().getWorlds().get(0).getWorldFolder(), "datapacks");
        } else {
            datapackFolder = new File(folderOverride);
        }
        getLogger().info("Loading translations from " + datapackFolder.getAbsolutePath());
        for (File file : datapackFolder.listFiles()) {
            if (file.getName().endsWith(".zip")) {
                // go through all files recursively and find .json files
                // then recursively go through all the json files and find this pattern that can be nested:
                try (ZipInputStream zipInputStream = new ZipInputStream(new FileInputStream(file))) {
                    ZipEntry entry;
                    while ((entry = zipInputStream.getNextEntry()) != null) {
                        if (entry.getName().endsWith(".json")) {
                            try {
                                StringBuilder jsonContent = new StringBuilder();
                                byte[] buffer = new byte[1024];
                                int length;
                                while ((length = zipInputStream.read(buffer)) > 0) {
                                    jsonContent.append(new String(buffer, 0, length));
                                }
                                JsonElement element = gson.fromJson(jsonContent.toString(), JsonElement.class);
                                traverseJsonElement(element);
                            } catch (Exception e) {
                                getLogger().warning("Failed to parse json file " + entry.getName() + " in datapack " + file.getName());
                                getLogger().warning(e.getMessage());
                            }
                        } else if (entry.getName().endsWith(".mcfunction")) {
                            try {
                                // match the json pattern
                                StringBuilder jsonContent = new StringBuilder();
                                byte[] buffer = new byte[1024];
                                int length;
                                while ((length = zipInputStream.read(buffer)) > 0) {
                                    jsonContent.append(new String(buffer, 0, length));
                                }
                                String[] lines = jsonContent.toString().split("\n");
                                for (String line : lines) {
                                    if (mcFunctionJsonPattern.matcher(line).matches()) {
                                        String json = line.substring(line.indexOf("{"), line.lastIndexOf("}") + 1);
                                        JsonElement element = gson.fromJson(json, JsonElement.class);
                                        traverseJsonElement(element);
                                    }
                                }
                            } catch (Exception e) {
                                getLogger().warning("Failed to parse mcfunction file " + entry.getName() + " in datapack " + file.getName());
                                getLogger().warning(e.getMessage());
                            }
                        }
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            } else if (file.isDirectory()) {
                // go through all files recursively and find .json files
                // then recursively go through all the json files and find this pattern that can be nested:
                checkFile(file);
            }
        }
        getLogger().info("Found " + translations.size() + " translations");
        if (Boolean.getBoolean("sst.debug")) {
            translations.forEach((key, value) -> getLogger().info(" - " + key + " -> " + LegacyComponentSerializer.legacySection().serialize(value)));
        }
        protocolManager.addPacketListener(new PacketAdapter(
                this,
                ListenerPriority.NORMAL,
                PacketType.Play.Server.SET_SLOT
        ) {
            @Override
            public void onPacketSending(PacketEvent event) {
                PacketContainer packet = event.getPacket();
                ItemStack itemStack = packet.getItemModifier().read(0);
                packet.getItemModifier().write(0, mutateItem(itemStack));
            }
        });
        protocolManager.addPacketListener(new PacketAdapter(
                this,
                ListenerPriority.NORMAL,
                PacketType.Play.Server.WINDOW_ITEMS
        ) {
            @Override
            public void onPacketSending(PacketEvent event) {
                PacketContainer packet = event.getPacket();
                List<ItemStack> itemStacks = packet.getItemListModifier().read(0);
                List<ItemStack> newStacks = new ArrayList<>();
                for (ItemStack itemStack : itemStacks) {
                    newStacks.add(mutateItem(itemStack));
                }
                packet.getItemListModifier().write(0, newStacks);
            }
        });
        protocolManager.addPacketListener(new PacketAdapter(
                this,
                ListenerPriority.NORMAL,
                PacketType.Play.Server.SYSTEM_CHAT,
                PacketType.Play.Server.DISGUISED_CHAT
        ) {
            @Override
            public void onPacketSending(PacketEvent event) {
                PacketContainer packet = event.getPacket();
                WrappedChatComponent component = null;
                try {
                    StructureModifier<Object> adventureModifier = packet.getModifier().withType(AdventureComponentConverter.getComponentClass());
                    if (!adventureModifier.getFields().isEmpty()) { // Only true on Paper
                        net.kyori.adventure.text.Component comp = (net.kyori.adventure.text.Component) adventureModifier.read(0);
                        if (comp != null) {
                            /*
                            if (comp.hoverEvent() != null) {
                                HoverEvent<?> hoverEvent = comp.hoverEvent();
                                if (hoverEvent.action() == HoverEvent.Action.SHOW_TEXT) {
                                    Component hoverComponent = (Component) hoverEvent.value();
                                    System.out.println("CE - " + LegacyComponentSerializer.legacySection().serialize(hoverComponent));
                                }
                            }
                             */
                            comp = translate(comp);
                            component = AdventureComponentConverter.fromComponent(comp);
                            // System.out.println(" - Final translated component: " + comp.getClass().getName() + " | " + component.getJson());
                            adventureModifier.write(0, null); // Reset to null - the JSON message will be set instead
                        }
                    }
                } catch (Throwable ignored) {
                } // Ignore if paper is unavailable

                if (component == null) {
                    component = WrappedChatComponent.fromJson(packet.getStrings().read(0));
                }

                // Modify component here
                packet.getStrings().write(0, component.getJson());
                /*
                System.out.println("Found system chat: " + packet.getChatComponents().size() + " | " + packet.getChatTypes().size() + " | " + packet.getChatComponentArrays().size() + " | " + packet.getChatComponentArrays().getValues().size());
                StructureModifier<WrappedChatComponent> chatComponents = packet.getChatComponents();
                for (WrappedChatComponent value : chatComponents.getValues()) {
                    if (value == null) continue;
                    System.out.println("Found chat component: " + value.getJson());
                    WrappedChatComponent wrappedChatComponent = value;
                    Component component = AdventureComponentConverter.fromWrapper(wrappedChatComponent);
                    component = translate(component);
                    wrappedChatComponent = AdventureComponentConverter.fromComponent(component);
                    packet.getChatComponents().write(0, wrappedChatComponent);
                }
                 */
            }
        });
        /*
        protocolManager.addPacketListener(new PacketAdapter(
                this,
                ListenerPriority.NORMAL,
                PacketType.Play.Server.BUNDLE
        ) {
            @Override
            public void onPacketSending(PacketEvent event) {
                PacketContainer packet = event.getPacket();
                StructureModifier<Iterable<PacketContainer>> packetBundles = packet.getPacketBundles();
                for (Iterable<PacketContainer> value1 : packetBundles.getValues()) {
                    value1.forEach(container -> {
                        if (container.getType() == PacketType.Play.Server.SPAWN_ENTITY || container.getType() == PacketType.Play.Server.ENTITY_METADATA) {
                            int entityId = container.getIntegers().read(0);
                            final Entity entity = event.getPlayer().getWorld().getEntities().stream().filter(e -> e.getEntityId() == entityId).findFirst().orElse(null);
                            if (entity == null || !(entity instanceof LivingEntity)) return;

                            final LivingEntity livingEntity = (LivingEntity) entity;

                            System.out.println("Found entity packet: " + container.getType() + " - " + container);
                            List<WrappedDataValue> read = container.getDataValueCollectionModifier().read(0);
                            WrappedDataValue value = new WrappedDataValue(
                                    2,
                                    WrappedDataWatcher.Registry.getChatComponentSerializer(true),
                                    Optional.of(WrappedChatComponent.fromText("test").getHandle())
                            );
                            read.add(value);
                            container.getDataValueCollectionModifier().write(0, read);

                            event.setPacket(packet);
                        }
                    });
                }
            }
        });
                 */
        protocolManager.addPacketListener(new PacketAdapter(
                this,
                ListenerPriority.NORMAL,
                PacketType.Play.Server.ENTITY_METADATA
        ) {
            @Override
            public void onPacketSending(PacketEvent event) {
                PacketContainer packet = event.getPacket();
                int entityId = packet.getIntegers().read(0);
                final Entity entity = event.getPlayer().getWorld().getEntities().stream().filter(e -> e.getEntityId() == entityId).findFirst().orElse(null);
                if (entity == null || !(entity instanceof LivingEntity)) return;
                final LivingEntity livingEntity = (LivingEntity) entity;
                if (livingEntity.customName() instanceof TranslatableComponent component) {
                    List<WrappedDataValue> read = packet.getDataValueCollectionModifier().read(0);
                    WrappedDataValue value = new WrappedDataValue(
                            2,
                            WrappedDataWatcher.Registry.getChatComponentSerializer(true),
                            Optional.of(AdventureComponentConverter.fromComponent(ServerSideTranslations.getInstance().translate(component)).getHandle())
                    );
                    read.add(value);
                    packet.getDataValueCollectionModifier().write(0, read);
                }
                event.setPacket(packet);
            }
        });

        Metrics metrics = new Metrics(this, 19655);
        metrics.addCustomChart(new Metrics.SimplePie("translation_count", () -> String.valueOf(translations.size())));
    }
    private void checkFile(File file) {
        if (file.isDirectory()) {
            for (File f : file.listFiles()) {
                checkFile(f);
            }
        } else {
            if (file.getName().endsWith(".json")) {
                try {
                    StringBuilder jsonContent = new StringBuilder();
                    byte[] buffer = new byte[1024];
                    int length;
                    while ((length = new FileInputStream(file).read(buffer)) > 0) {
                        jsonContent.append(new String(buffer, 0, length));
                    }
                    JsonElement element = gson.fromJson(jsonContent.toString(), JsonElement.class);
                    traverseJsonElement(element);
                } catch (Exception e) {
                    getLogger().warning("Failed to parse json file " + file.getName() + " in datapack " + file.getName());
                    getLogger().warning(e.getMessage());
                }
            } else if (file.getName().endsWith(".mcfunction")) {
                try {
                    // match the json pattern
                    StringBuilder jsonContent = new StringBuilder();
                    byte[] buffer = new byte[1024];
                    int length;
                    while ((length = new FileInputStream(file).read(buffer)) > 0) {
                        jsonContent.append(new String(buffer, 0, length));
                    }
                    String[] lines = jsonContent.toString().split("\n");
                    for (String line : lines) {
                        if (mcFunctionJsonPattern.matcher(line).matches()) {
                            String json = line.substring(line.indexOf("{"), line.lastIndexOf("}") + 1);
                            JsonElement element = gson.fromJson(json, JsonElement.class);
                            traverseJsonElement(element);
                        }
                    }
                } catch (Exception e) {
                    getLogger().warning("Failed to parse mcfunction file " + file.getName() + " in datapack " + file.getName());
                    getLogger().warning(e.getMessage());
                }
            }
        }
    }

    public Component translate(Component component, boolean... flags) {
        boolean onlyTranslate = flags.length > 0 && flags[0];
        boolean dontTranslateHover = flags.length > 1 && flags[1];
        if (component instanceof TextComponent tc && tc.content().trim().isEmpty()) return component;

        // System.out.println("Type: " + component.getClass().getName() + " | " + LegacyComponentSerializer.legacySection().serialize(component));

        if (component instanceof TranslatableComponent translatableComponent) {
            try {
                String key = translatableComponent.key();
                if (translations.containsKey(key)) {
                    Component c = translations.get(key).style(component.style());
                    for (Component child : translatableComponent.children()) {
                        c = c.append(translate(child).style(component.style()));
                    }
                    if (!onlyTranslate && !dontTranslateHover) {
                        HoverEvent<?> hoverEvent = translatableComponent.hoverEvent();
                        if (hoverEvent != null) {
                            if (hoverEvent.action() == HoverEvent.Action.SHOW_TEXT) {
                                Component hoverComponent = (Component) hoverEvent.value();
                                // getComponentLogger().info("Hover component: (" + hoverComponent.children().size() + ")" + hoverComponent.getClass().getName() + " | " + (hoverComponent instanceof TranslatableComponent ? ((TranslatableComponent) hoverComponent).key() : LegacyComponentSerializer.legacySection().serialize(hoverComponent)));
                                hoverComponent = translate(hoverComponent, false, true);
                                // getComponentLogger().info("  - Translated hover component: (" + hoverComponent.children().size() + ")" + hoverComponent.getClass().getName() + " | " + (hoverComponent instanceof TranslatableComponent ? ((TranslatableComponent) hoverComponent).key() : LegacyComponentSerializer.legacySection().serialize(hoverComponent)));
                                c = c.hoverEvent(HoverEvent.showText(hoverComponent));
                            }
                        }
                        if (translatableComponent.clickEvent() != null) {
                            c = c.clickEvent(translatableComponent.clickEvent());
                        }
                    }
                    return c;
                }
                List<Component> args = new ArrayList<>(translatableComponent.args());
                args.replaceAll(this::translate);
                component = translatableComponent.args(args);
            } catch (Exception ignored) {
            }
        }

        if (component.hoverEvent() != null && !onlyTranslate && !dontTranslateHover) {
            component = applyHoverEventTranslation(component, component.hoverEvent());
        }

        if (!component.children().isEmpty() && !onlyTranslate) {
            List<Component> children = new ArrayList<>(component.children());
            children.replaceAll(this::translate);
            component = component.children(children);
        }

        return component;
    }


    private Component applyHoverEventTranslation(Component component, HoverEvent<?> hoverEvent) {
        if (hoverEvent == null) return component;
        HoverEvent.Action<?> action = hoverEvent.action();
        // System.out.println("  - Hover event: " + action);
        if (action == HoverEvent.Action.SHOW_TEXT) {
            Component hoverComponent = (Component) hoverEvent.value();
            // System.out.println("    - Hover component: " + LegacyComponentSerializer.legacySection().serialize(hoverComponent).replace("\n", "\\n"));
            hoverComponent = translate(hoverComponent);
            return component.hoverEvent(HoverEvent.showText(LegacyComponentSerializer.legacySection().deserialize(
                    LegacyComponentSerializer.legacySection().serialize(hoverComponent).replace("\n", "\\n")
            )));
        } else if (action == HoverEvent.Action.SHOW_ENTITY) {
            HoverEvent.ShowEntity showEntity = (HoverEvent.ShowEntity) hoverEvent.value();
            Component name = showEntity.name();
            name = translate(name);
            return component.hoverEvent(HoverEvent.showEntity(HoverEvent.ShowEntity.showEntity(showEntity.type(), showEntity.id(), name)));
        }
        return component;
    }


    public ItemStack mutateItem(ItemStack itemStack) {
        if (itemStack != null) {
            ItemMeta itemMeta = itemStack.getItemMeta();
            if (itemMeta != null) {
                List<Component> l = itemMeta.lore();
                if (l != null) {
                    List<Component> lore = new ArrayList<>(l);
                    lore.replaceAll(this::translate);
                    itemMeta.lore(lore);
                    itemMeta.displayName(translate(itemMeta.displayName()));
                    itemStack.setItemMeta(itemMeta);
                    /*
                    System.out.println("------------------------");
                    for (Component component : lore) {
                        System.out.println("New lore: " + LegacyComponentSerializer.legacySection().serialize(component));
                    }
                    System.out.println("------------------------");
                     */
                }
            }
        }
        return itemStack;
    }

    private void traverseJsonElement(JsonElement element) {
        if (element.isJsonObject()) {
            JsonObject jsonObject = element.getAsJsonObject();
            traverseJsonObject(jsonObject);
        } else if (element.isJsonArray()) {
            JsonArray jsonArray = element.getAsJsonArray();
            for (JsonElement jsonElement : jsonArray) {
                traverseJsonElement(jsonElement);
            }
        }
    }

    private void traverseJsonObject(JsonObject jsonObject) {
        checkObject(jsonObject);
        for (Map.Entry<String, JsonElement> entry : jsonObject.entrySet()) {
            if (entry.getValue().isJsonObject()) {
                JsonObject nestedObject = entry.getValue().getAsJsonObject();
                if (!checkObject(nestedObject)) {
                    traverseJsonObject(nestedObject);
                }
            } else {
                traverseJsonElement(entry.getValue());
            }
        }
    }

    private boolean checkObject(JsonObject nestedObject) {
        if (nestedObject.has("translate") && nestedObject.has("fallback")) {
            String translateValue = nestedObject.get("translate").getAsString();
            String fallbackValue = nestedObject.get("fallback").getAsString();
            boolean italic = false;
            String color = null;
            if (nestedObject.has("italic")) {
                italic = nestedObject.get("italic").getAsBoolean();
            }
            if (nestedObject.has("color")) {
                color = nestedObject.get("color").getAsString();
            }
            Component component = Component.text("")
                    .decoration(TextDecoration.ITALIC, false)
                    .append(Component.text(fallbackValue)
                            .decoration(TextDecoration.ITALIC, italic));
            if (color != null) {
                component = component.style(Style.style(net.kyori.adventure.text.format.TextColor.fromHexString(color)));
            }
            translations.put(translateValue, component);
            return true;
        } else {
            return false;
        }
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
    }
}
