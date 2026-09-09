package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.passive.SheepEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;

public class AutoSheepFarm extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Double> radius = sgGeneral.add(new DoubleSetting.Builder()
        .name("raggio")
        .description("Raggio di ricerca delle pecore.")
        .defaultValue(6.0)
        .min(1.0).max(20.0)
        .build()
    );

    private final Setting<Integer> stackAmount = sgGeneral.add(new IntSetting.Builder()
        .name("stack-richiesto")
        .description("Numero minimo nello stack per shearare.")
        .defaultValue(500)
        .build()
    );

    private final Setting<Integer> actionDelay = sgGeneral.add(new IntSetting.Builder()
        .name("ritardo-tick")
        .description("Ritardo (in tick) tra un'azione e l'altra. 20 tick = 1 secondo.")
        .defaultValue(6)
        .min(1).max(40)
        .build()
    );

    private final Setting<String> shopCommand = sgGeneral.add(new StringSetting.Builder()
        .name("comando-shop")
        .description("Comando per aprire lo shop, senza la barra iniziale.")
        .defaultValue("shop Blocks")
        .build()
    );

    private final Setting<Integer> containerSize = sgGeneral.add(new IntSetting.Builder()
        .name("slot-totali-pagina")
        .description("Quanti slot ha la parte shop di ogni pagina (senza il tuo inventario).")
        .defaultValue(18)
        .build()
    );

    private final Setting<Integer> nextPageSlot = sgGeneral.add(new IntSetting.Builder()
        .name("slot-pagina-successiva")
        .description("Slot del vetro blu 'Pagina Successiva' (parte da 0).")
        .defaultValue(14)
        .build()
    );

    private final Setting<Integer> maxPageAttempts = sgGeneral.add(new IntSetting.Builder()
        .name("max-pagine")
        .description("Numero massimo di pagine da provare prima di arrendersi.")
        .defaultValue(10)
        .build()
    );

    private final Setting<String> woolName = sgGeneral.add(new StringSetting.Builder()
        .name("nome-lana")
        .description("Testo da cercare nel nome dell'oggetto lana (sia nello shop che a terra/inventario).")
        .defaultValue("wool")
        .build()
    );

    private final Setting<Integer> emeraldSlot = sgGeneral.add(new IntSetting.Builder()
        .name("slot-smeraldo")
        .description("Slot dello smeraldo 'conferma vendita' (parte da 0).")
        .defaultValue(31)
        .build()
    );

    private final Setting<Double> lootSearchRadius = sgGeneral.add(new DoubleSetting.Builder()
        .name("raggio-ricerca-lana")
        .description("Raggio in cui cercare la lana caduta a terra.")
        .defaultValue(8.0)
        .min(1.0).max(20.0)
        .build()
    );

    private final Setting<Double> moveSpeed = sgGeneral.add(new DoubleSetting.Builder()
        .name("velocita-recupero")
        .description("Velocità con cui ti sposti verso la lana caduta.")
        .defaultValue(0.25)
        .min(0.05).max(0.5)
        .build()
    );

    private final Setting<Integer> moveTimeout = sgGeneral.add(new IntSetting.Builder()
        .name("timeout-recupero")
        .description("Tick massimi spesi a inseguire la lana prima di rinunciare (20 = 1 secondo).")
        .defaultValue(60)
        .build()
    );

    private final Setting<Double> arrivalDistance = sgGeneral.add(new DoubleSetting.Builder()
        .name("distanza-arrivo")
        .description("Quando sei abbastanza vicino da fermarti e considerare la lana raccolta.")
        .defaultValue(1.3)
        .min(0.5).max(4.0)
        .build()
    );

    private enum State {
        IDLE, TARGETING, SHEAR, WAIT_AFTER_SHEAR, MOVE_TO_LOOT,
        OPEN_SHOP, WAIT_SHOP_OPEN, SEARCH_WOOL,
        OPEN_SELL_MENU, WAIT_SELL_MENU, CONFIRM_SELL,
        CLOSE
    }

    private State state = State.IDLE;
    private Entity target;
    private int delayTicks = 0;
    private int moveTicks = 0;
    private int pageAttempts = 0;
    private int foundWoolSlot = -1;

    public AutoSheepFarm() {
        super(AddonTemplate.CATEGORY, "auto-sheep-farm", "Sheara automaticamente pecore a stack pieno, recupera la lana e la vende.");
    }

    @Override
    public void onActivate() {
        state = State.IDLE;
        target = null;
        delayTicks = 0;
        moveTicks = 0;
        pageAttempts = 0;
        foundWoolSlot = -1;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;

        if (delayTicks > 0 && state != State.MOVE_TO_LOOT) {
            delayTicks--;
            return;
        }

        switch (state) {
            case IDLE -> findTarget();
            case TARGETING -> rotateToTarget();
            case SHEAR -> shear();
            case WAIT_AFTER_SHEAR -> {
                moveTicks = 0;
                state = State.MOVE_TO_LOOT;
            }
            case MOVE_TO_LOOT -> moveToLoot();
            case OPEN_SHOP -> openShop();
            case WAIT_SHOP_OPEN -> checkShopOpen();
            case SEARCH_WOOL -> searchWool();
            case OPEN_SELL_MENU -> openSellMenu();
            case WAIT_SELL_MENU -> checkSellMenuOpen();
            case CONFIRM_SELL -> confirmSell();
            case CLOSE -> closeShop();
        }
    }

    // MODIFICATA: ora salta le pecore già tosate con sheep.isSheared()
    private void findTarget() {
        String needle = stackAmount.get() + "x";
        double best = radius.get() * radius.get();
        Entity found = null;

        for (Entity entity : mc.world.getEntities()) {
            if (!(entity instanceof SheepEntity sheep)) continue;
            if (sheep.isSheared()) continue;
            if (sheep.getCustomName() == null) continue;
            if (!sheep.getCustomName().getString().contains(needle)) continue;

            double dist = mc.player.squaredDistanceTo(sheep);
            if (dist <= best) {
                best = dist;
                found = sheep;
            }
        }

        if (found != null) {
            target = found;
            state = State.TARGETING;
        }
    }

    private void rotateToTarget() {
        if (target == null || !target.isAlive()) {
            state = State.IDLE;
            return;
        }

        double yaw = Rotations.getYaw(target);
        double pitch = Rotations.getPitch(target);

        Rotations.rotate(yaw, pitch, 100, () -> state = State.SHEAR);
    }

    private void shear() {
        if (target == null || !target.isAlive()) {
            state = State.IDLE;
            return;
        }

        equipShears();

        mc.interactionManager.interactEntity(mc.player, target, Hand.MAIN_HAND);
        mc.player.swingHand(Hand.MAIN_HAND);

        delayTicks = actionDelay.get();
        state = State.WAIT_AFTER_SHEAR;
    }

    private void equipShears() {
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).getItem() == Items.SHEARS) {
                mc.player.getInventory().selectedSlot = i;
                return;
            }
        }
    }

    private Entity findNearestWoolItem() {
        String needle = woolName.get().toLowerCase();
        double best = lootSearchRadius.get() * lootSearchRadius.get();
        Entity found = null;

        for (Entity entity : mc.world.getEntities()) {
            if (!(entity instanceof ItemEntity itemEntity)) continue;

            ItemStack stack = itemEntity.getStack();
            if (stack.isEmpty()) continue;
            if (!stack.getName().getString().toLowerCase().contains(needle)) continue;

            double dist = mc.player.squaredDistanceTo(entity);
            if (dist <= best) {
                best = dist;
                found = entity;
            }
        }

        return found;
    }

    private void moveToLoot() {
        Entity woolItem = findNearestWoolItem();

        if (woolItem == null || moveTicks >= moveTimeout.get()) {
            delayTicks = actionDelay.get();
            proceedAfterLoot();
            return;
        }

        double dx = woolItem.getX() - mc.player.getX();
        double dz = woolItem.getZ() - mc.player.getZ();
        double distSq = dx * dx + dz * dz;

        if (distSq <= arrivalDistance.get() * arrivalDistance.get()) {
            delayTicks = actionDelay.get();
            proceedAfterLoot();
            return;
        }

        double dist = Math.sqrt(distSq);
        double speed = moveSpeed.get();

        mc.player.setVelocity((dx / dist) * speed, mc.player.getVelocity().y, (dz / dist) * speed);
        moveTicks++;
    }

    private void proceedAfterLoot() {
        state = hasWoolInInventory() ? State.OPEN_SHOP : State.IDLE;
    }

    private boolean hasWoolInInventory() {
        String needle = woolName.get().toLowerCase();

        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.isEmpty()) continue;
            if (stack.getName().getString().toLowerCase().contains(needle)) return true;
        }

        return false;
    }

    private void openShop() {
        mc.player.networkHandler.sendChatCommand(shopCommand.get());
        delayTicks = actionDelay.get() * 3;
        pageAttempts = 0;
        state = State.WAIT_SHOP_OPEN;
    }

    private void checkShopOpen() {
        if (mc.currentScreen instanceof HandledScreen<?>) {
            state = State.SEARCH_WOOL;
        } else {
            delayTicks = actionDelay.get();
        }
    }

    private void searchWool() {
        if (!(mc.currentScreen instanceof HandledScreen<?> screen)) {
            state = State.IDLE;
            return;
        }

        ScreenHandler handler = screen.getScreenHandler();
        String needle = woolName.get().toLowerCase();

        for (int i = 0; i < containerSize.get(); i++) {
            ItemStack stack = handler.getSlot(i).getStack();
            if (stack.isEmpty()) continue;

            String name = stack.getName().getString().toLowerCase();
            if (name.contains(needle)) {
                foundWoolSlot = i;
                state = State.OPEN_SELL_MENU;
                return;
            }
        }

        pageAttempts++;
        if (pageAttempts >= maxPageAttempts.get()) {
            mc.player.closeHandledScreen();
            state = State.IDLE;
            return;
        }

        mc.interactionManager.clickSlot(handler.syncId, nextPageSlot.get(), 0, SlotActionType.PICKUP, mc.player);
        delayTicks = actionDelay.get();
    }

    private void openSellMenu() {
        if (!(mc.currentScreen instanceof HandledScreen<?> screen) || foundWoolSlot < 0) {
            state = State.IDLE;
            return;
        }

        ScreenHandler handler = screen.getScreenHandler();
        mc.interactionManager.clickSlot(handler.syncId, foundWoolSlot, 1, SlotActionType.PICKUP, mc.player);

        delayTicks = actionDelay.get() * 2;
        state = State.WAIT_SELL_MENU;
    }

    private void checkSellMenuOpen() {
        if (mc.currentScreen instanceof HandledScreen<?>) {
            state = State.CONFIRM_SELL;
        } else {
            delayTicks = actionDelay.get();
        }
    }

    private void confirmSell() {
        if (!(mc.currentScreen instanceof HandledScreen<?> screen)) {
            state = State.IDLE;
            return;
        }

        ScreenHandler handler = screen.getScreenHandler();
        mc.interactionManager.clickSlot(handler.syncId, emeraldSlot.get(), 0, SlotActionType.PICKUP, mc.player);

        delayTicks = actionDelay.get();
        state = State.CLOSE;
    }

    private void closeShop() {
        mc.player.closeHandledScreen();
        target = null;
        foundWoolSlot = -1;
        state = State.IDLE;
    }
}
