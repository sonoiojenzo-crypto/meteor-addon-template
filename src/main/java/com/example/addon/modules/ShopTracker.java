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

    // ----- Pattern per riconoscere i messaggi dello shop -----
    private final Setting<String> sellRegex = sgGeneral.add(new StringSetting.Builder()
        .name("regex-vendita")
        .description("Regex per riconoscere il messaggio di vendita. Gruppo 1 = quantità, Gruppo 2 = item, Gruppo 3 = importo.")
        .defaultValue("Hai venduto (\\d+)x (.+) per \\$([0-9.,]+) con successo")
        .build()
    );

    private final Setting<String> buyRegex = sgGeneral.add(new StringSetting.Builder()
        .name("regex-acquisto")
        .description("Regex per riconoscere il messaggio di acquisto. Gruppo 1 = quantità, Gruppo 2 = item, Gruppo 3 = importo.")
        .defaultValue("Hai acquistato (\\d+)x (.+) per \\$([0-9.,]+) con successo")
        .build()
    );

    // ----- Impostazioni scontrino -----
    private final Setting<String> receiptFormat = sgGeneral.add(new StringSetting.Builder()
        .name("formato-scontrino")
        .description("Usa {earned} e {spent} come segnaposto.")
        .defaultValue("Nell'ultima ora hai guadagnato ${earned} e hai speso ${spent}!!")
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

    private Pattern compiledSell;
    private Pattern compiledBuy;

    public ShopTracker() {
        super(AddonTemplate.CATEGORY, "shop-tracker", "Traccia guadagni/spese dello shop e stampa uno scontrino periodico.");
    }

    @Override
    public void onActivate() {
        earnedThisPeriod = 0;
        spentThisPeriod = 0;
        lastReportTime = System.currentTimeMillis();
        compilePatterns();
    }

    private void compilePatterns() {
        try {
            compiledSell = Pattern.compile(sellRegex.get());
            compiledBuy = Pattern.compile(buyRegex.get());
        } catch (Exception e) {
            error("Regex non valida: " + e.getMessage());
            toggle(); // disattiva il modulo per sicurezza
        }
    }

    private double parseAmount(String raw) {
        // rimuove separatori delle migliaia (virgola) e simboli, tiene il punto come decimale
        String cleaned = raw.replace(",", "").replace("$", "").trim();
        try {
            return Double.parseDouble(cleaned);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    @EventHandler
    private void onMessage(ReceiveMessageEvent event) {
        String msg = event.getMessage().getString();

        Matcher sellMatch = compiledSell.matcher(msg);
        if (sellMatch.find()) {
            earnedThisPeriod += parseAmount(sellMatch.group(3));
            return;
        }

        Matcher buyMatch = compiledBuy.matcher(msg);
        if (buyMatch.find()) {
            spentThisPeriod += parseAmount(buyMatch.group(3));
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
                    ChatUtils.sendPlayerMsg(out); // manda come messaggio reale in chat
                } else {
                    ChatUtils.info(out); // visibile solo a te, in stile Meteor
                }
            }

            earnedThisPeriod = 0;
            spentThisPeriod = 0;
            lastReportTime = now;
        }
    }
}
