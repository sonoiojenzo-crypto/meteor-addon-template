package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.s2c.play.WorldTimeUpdateS2CPacket;
import net.minecraft.text.Text;

import java.util.ArrayDeque;
import java.util.Deque;

public class TpsGuard extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Double> minTps = sgGeneral.add(new DoubleSetting.Builder()
        .name("min-tps")
        .description("Sotto questo TPS stimato, disconnette.")
        .defaultValue(15.0)
        .min(1).max(20)
        .sliderMax(20)
        .build()
    );

    private final Setting<Integer> windowMs = sgGeneral.add(new IntSetting.Builder()
        .name("finestra-ms")
        .description("Finestra temporale su cui calcolare il TPS medio.")
        .defaultValue(3000)
        .min(1000)
        .sliderMax(10000)
        .build()
    );

    private final Setting<Boolean> disableAfter = sgGeneral.add(new BoolSetting.Builder()
        .name("disattiva-dopo-trigger")
        .description("Disattiva il modulo dopo la disconnessione (evita loop se ti riconnetti manualmente).")
        .defaultValue(true)
        .build()
    );

    private final Deque<Long> ticks = new ArrayDeque<>();

    public TpsGuard() {
        super(AddonTemplate.CATEGORY, "tps-guard", "Disconnette se il TPS del server crolla (lag machine).");
    }

    @Override
    public void onActivate() {
        ticks.clear();
    }

    @EventHandler
    private void onReceive(PacketEvent.Receive event) {
        if (!(event.packet instanceof WorldTimeUpdateS2CPacket)) return;

        long now = System.currentTimeMillis();
        ticks.addLast(now);

        while (!ticks.isEmpty() && now - ticks.peekFirst() > windowMs.get()) {
            ticks.pollFirst();
        }

        if (ticks.size() < 5) return; // troppo pochi campioni, aspetta

        double seconds = (now - ticks.peekFirst()) / 1000.0;
        if (seconds <= 0) return;

        double tps = Math.min((ticks.size() - 1) / seconds, 20.0);

        if (tps < minTps.get()) {
            info("TPS stimato: %.1f — disconnessione di sicurezza.", tps);
            if (mc.getNetworkHandler() != null) {
                mc.getNetworkHandler().getConnection().disconnect(
                    Text.literal("Disconnesso da TpsGuard (TPS < " + minTps.get() + ")")
                );
            }
            if (disableAfter.get()) toggle();
        }
    }
}
