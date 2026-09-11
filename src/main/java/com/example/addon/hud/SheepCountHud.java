package com.example.addon.hud;

import com.example.addon.AddonTemplate;
import com.example.addon.modules.SheepEsp;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.render.color.Color;

public class SheepCountHud extends HudElement {
    public static final HudElementInfo<SheepCountHud> INFO = new HudElementInfo<>(
        AddonTemplate.HUD_GROUP,
        "sheep-count-hud",
        "Mostra quante pecore sono rilevate nel raggio impostato su SheepEsp.",
        SheepCountHud::new
    );

    public SheepCountHud() {
        super(INFO);
    }

    @Override
    public void render(HudRenderer renderer) {
        SheepEsp esp = Modules.get().get(SheepEsp.class);

        String text;
        if (esp == null) {
            text = "SheepEsp non trovato";
        } else {
            text = "Pecore: " + esp.getSheepCount();
        }

        setSize(renderer.textWidth(text, true), renderer.textHeight(true));
        renderer.text(text, x, y, Color.WHITE, true);
    }
}
