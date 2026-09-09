package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ShopTracker extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    // ----- Parole chiave da cercare nel messaggio (ripulito da simboli/colori) -----
    private final Setting<String> sellKeyword = sgGeneral.add(new StringSetting.Builder()
        .name("parola-vendita")
        .description("Parola chiave che identifica un messaggio di vendita.")
        .defaultValue("venduto")
        .build()
    );

    private final Setting<String> buyKeyword = sgGeneral.add(new StringSetting.Builder()
        .name("parola-acquisto")
        .description("Parola chiave che identifica un messaggio di acquisto.")
        .defaultValue("acquistato")
        .build()
    );

    // ----- Impostazioni scontrino -----
    private final Setting<String> receiptFormat = sgGeneral.add(new StringSetting.Builder()
        .name("formato-scontrino")
        .description("Usa {earned} e {spent} come segnaposto.")
        .defaultValue("Nell'ultima ora hai guadagnato €{earned} e hai speso €{spent}!!")
        .build()
    );

    private final Setting<Integer> intervalMinutes = sgGeneral.add(new IntSetting.Builder()
        .name("intervallo-minuti")
        .description("Ogni quanti minuti stampare lo scontrino.")
        .defaultValue(60)
        .min(1)
        .sliderMax(180)
        .build()
    );

    private final Setting<Boolean> sendToServer = sgGeneral.add(new BoolSetting.Builder()
        .name("invia-in-chat-pubblica")
        .description("Se attivo manda lo scontrino come messaggio reale al server (visibile agli altri). Se disattivo, lo mostra solo a te.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> silentIfNoActivity = sgGeneral.add(new BoolSetting.Builder()
        .name("silenzioso-se-nessuna-attivita")
        .description("Non stampa lo scontrino se non hai comprato/venduto nulla nell'ultima ora.")
        .defaultValue(true)
        .build()
    );

    // ----- Stato interno -----
    private double earnedThisPeriod = 0;
    private double spentThisPeriod = 0;
    private long lastReportTime = 0;

    // Rimuove tutto tranne lettere, numeri, spazi, virgole e punti (elimina simboli custom/colori del server)
    private static final Pattern CLEANUP = Pattern.compile("[^\\p{L}\\p{N}\\s.,]");
    // Cerca il numero subito dopo la parola "per"
    private static final Pattern PRICE_PATTERN = Pattern.compile("(?i)per\\s*([0-9.,]+)");

    public ShopTracker() {
        super(AddonTemplate.CATEGORY, "shop-tracker", "Traccia guadagni/spese dello shop e stampa uno scontrino periodico.");
    }

    @Override
    public void onActivate() {
        earnedThisPeriod = 0;
        spentThisPeriod = 0;
        lastReportTime = System.currentTimeMillis();
    }

    private double parseAmount(String raw) {
        // rimuove i separatori delle migliaia (virgola), tiene il punto come decimale
        String cleaned = raw.replace(",", "").trim();
        try {
            return Double.parseDouble(cleaned);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    @EventHandler
    private void onMessage(ReceiveMessageEvent event) {
        String raw = event.getMessage().getString();

        // Pulisce il messaggio da simboli/colori custom del server
        String cleaned = CLEANUP.matcher(raw).replaceAll("").replaceAll("\\s+", " ").trim();
        String lower = cleaned.toLowerCase();

        boolean isSell = lower.contains(sellKeyword.get().toLowerCase());
        boolean isBuy = lower.contains(buyKeyword.get().toLowerCase());

        if (!isSell && !isBuy) return;

        Matcher priceMatch = PRICE_PATTERN.matcher(cleaned);
        if (!priceMatch.find()) return;

        double amount = parseAmount(priceMatch.group(1));

        if (isSell) {
            earnedThisPeriod += amount;
        } else {
            spentThisPeriod += amount;
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        long now = System.currentTimeMillis();
        long intervalMs = intervalMinutes.get() * 60_000L;

        if (now - lastReportTime >= intervalMs) {
            if (!silentIfNoActivity.get() || earnedThisPeriod > 0 || spentThisPeriod > 0) {
                String out = receiptFormat.get()
                    .replace("{earned}", String.format("%.2f", earnedThisPeriod))
                    .replace("{spent}", String.format("%.2f", spentThisPeriod));

                if (sendToServer.get()) {
                    ChatUtils.sendPlayerMsg(out);
                } else {
                    ChatUtils.info(out);
                }
            }

            earnedThisPeriod = 0;
            spentThisPeriod = 0;
            lastReportTime = now;
        }
    }
}
