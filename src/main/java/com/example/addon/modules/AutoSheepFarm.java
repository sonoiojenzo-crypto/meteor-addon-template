package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.SheepEntity;
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

    private final Setting<Integer> pageClicks = sgGeneral.add(new IntSetting.Builder()
        .name("click-pagina")
        .description("Quante volte cliccare il vetro blu per arrivare alla lana.")
        .defaultValue(5)
        .build()
    );

    private final Setting<Integer> nextPageSlot = sgGeneral.add(new IntSetting.Builder()
        .name("slot-pagina-successiva")
        .description("Slot del vetro blu 'Pagina Successiva' (parte da 0).")
        .defaultValue(5)
        .build()
    );

    private final Setting<Integer> woolSlot = sgGeneral.add(new IntSetting.Builder()
        .name("slot-lana")
        .description("Slot della lana nella pagina finale (parte da 0).")
        .defaultValue(0)
        .build()
    );

    private final Setting<Integer> emeraldSlot = sgGeneral.add(new IntSetting.Builder()
        .name("slot-smeraldo")
        .description("Slot dello smeraldo 'conferma vendita' (parte da 0).")
        .defaultValue(31) // il tuo slot 32 (contando da 1) = slot 31 (contando da 0)
        .build()
    );

    private enum State {
        IDLE, TARGETING, SHEAR, WAIT_AFTER_SHEAR,
        OPEN_SHOP, WAIT_SHOP_OPEN, PAGING,
        OPEN_SELL_MENU, WAIT_SELL_MENU, CONFIRM_SELL,
        CLOSE
    }

    private State state = State.IDLE;
    private Entity target;
    private int delayTicks = 0;
    private int pageClicksDone = 0;

    public AutoSheepFarm() {
        super(AddonTemplate.CATEGORY, "auto-sheep-farm", "Sheara automaticamente pecore a stack pieno e vende la lana.");
    }

    @Override
    public void onActivate() {
        state = State.IDLE;
        target = null;
        delayTicks = 0;
        pageClicksDone = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;

        if (delayTicks > 0) {
            delayTicks--;
            return;
        }

        switch (state) {
            case IDLE -> findTarget();
            case TARGETING -> rotateToTarget();
            case SHEAR -> shear();
            case WAIT_AFTER_SHEAR -> state = State.OPEN_SHOP;
            case OPEN_SHOP -> openShop();
            case WAIT_SHOP_OPEN -> checkShopOpen();
            case PAGING -> clickNextPage();
            case OPEN_SELL_MENU -> openSellMenu();
            case WAIT_SELL_MENU -> checkSellMenuOpen();
            case CONFIRM_SELL -> confirmSell();
            case CLOSE -> closeShop();
        }
    }

    private void findTarget() {
        String needle = stackAmount.get() + "x";
        double best = radius.get() * radius.get();
        Entity found = null;

        for (Entity entity : mc.world.getEntities()) {
            if (!(entity instanceof SheepEntity)) continue;
            if (entity.getCustomName() == null) continue;
            if (!entity.getCustomName().getString().contains(needle)) continue;

            double dist = mc.player.squaredDistanceTo(entity);
            if (dist <= best) {
                best = dist;
                found = entity;
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

    private void openShop() {
        mc.player.networkHandler.sendChatCommand(shopCommand.get());
        delayTicks = actionDelay.get() * 3;
        state = State.WAIT_SHOP_OPEN;
    }

    private void checkShopOpen() {
        if (mc.currentScreen instanceof HandledScreen<?>) {
            pageClicksDone = 0;
            state = State.PAGING;
        } else {
            delayTicks = actionDelay.get();
        }
    }

    private void clickNextPage() {
        if (!(mc.currentScreen instanceof HandledScreen<?> screen)) {
            state = State.IDLE;
            return;
        }

        if (pageClicksDone >= pageClicks.get()) {
            state = State.OPEN_SELL_MENU;
            return;
        }

        ScreenHandler handler = screen.getScreenHandler();
        mc.interactionManager.clickSlot(handler.syncId, nextPageSlot.get(), 0, SlotActionType.PICKUP, mc.player);

        pageClicksDone++;
        delayTicks = actionDelay.get();
    }

    // Click DESTRO sulla lana per aprire il menu "Vendi > White Wool"
    private void openSellMenu() {
        if (!(mc.currentScreen instanceof HandledScreen<?> screen)) {
            state = State.IDLE;
            return;
        }

        ScreenHandler handler = screen.getScreenHandler();
        mc.interactionManager.clickSlot(handler.syncId, woolSlot.get(), 1, SlotActionType.PICKUP, mc.player);

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

    // Click SINISTRO sullo smeraldo per vendere tutto
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
        state = State.IDLE;
    }
}
