package com.example.addon.hud;

import com.example.addon.AddonTemplate;
import com.example.addon.modules.ShopTracker;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.render.color.Color;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class ShopTrackerHud extends HudElement {
    public static final HudElementInfo<ShopTrackerHud> INFO = new HudElementInfo<>(
        AddonTemplate.HUD_GROUP,
        "shop-tracker-hud",
        "Mostra una tabella dei guadagni/spese giornalieri dello ShopTracker.",
        ShopTrackerHud::new
    );

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> daysToShow = sgGeneral.add(new IntSetting.Builder()
        .name("giorni-da-mostrare")
        .description("Quanti giorni recenti mostrare nella tabella.")
        .defaultValue(5)
        .min(1)
        .sliderMax(14)
        .build()
    );

    private final Setting<Boolean> showItems = sgGeneral.add(new BoolSetting.Builder()
        .name("mostra-item")
        .description("Mostra anche gli item venduti/comprati sotto ogni riga.")
        .defaultValue(false)
        .build()
    );

    public ShopTrackerHud() {
        super(INFO);
    }

    @Override
    public void render(HudRenderer renderer) {
        ShopTracker tracker = Modules.get().get(ShopTracker.class);

        if (tracker == null) {
            String msg = "ShopTracker non trovato";
            setSize(renderer.textWidth(msg, true), renderer.textHeight(true));
            if (isInEditor()) renderer.text(msg, x, y, Color.RED, true);
            return;
        }

        List<Map.Entry<String, ShopTracker.DayStats>> entries = new ArrayList<>(tracker.getDailyStats().entrySet());
        Collections.reverse(entries); // le date pi\u00f9 recenti prima (yyyy-MM-dd si ordina alfabeticamente = cronologicamente)

        double lineHeight = renderer.textHeight(true) + 2;
        double width = 220;
        int shown = Math.min(entries.size(), daysToShow.get());
        double height = Math.max(shown, 1) * lineHeight;
        if (showItems.get()) height += shown * lineHeight * 2;

        setSize(width, height);

        double drawY = y;

        if (entries.isEmpty()) {
            renderer.text("Nessun dato ancora", x, drawY, Color.GRAY, true);
            return;
        }

        for (int i = 0; i < shown; i++) {
            Map.Entry<String, ShopTracker.DayStats> entry = entries.get(i);
            ShopTracker.DayStats day = entry.getValue();

            String line = entry.getKey() + "  +€" + String.format("%.2f", day.earned) + "  -€" + String.format("%.2f", day.spent);
            renderer.text(line, x, drawY, Color.WHITE, true);
            drawY += lineHeight;

            if (showItems.get()) {
                String sold = "  Venduto: " + (day.soldItems.isEmpty() ? "-" : String.join(", ", day.soldItems));
                String bought = "  Comprato: " + (day.boughtItems.isEmpty() ? "-" : String.join(", ", day.boughtItems));
                renderer.text(sold, x, drawY, Color.GREEN, true);
                drawY += lineHeight;
                renderer.text(bought, x, drawY, Color.RED, true);
                drawY += lineHeight;
            }
        }
    }
}
