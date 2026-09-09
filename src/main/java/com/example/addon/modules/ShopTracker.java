package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;
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
        .description("Usa {earned}, {spent}, {sold_items}, {bought_items} come segnaposto.")
        .defaultValue("Nell'ultima ora hai guadagnato €{earned} vendendo: {sold_items} e hai speso €{spent} acquistando: {bought_items}!!")
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

    // item -> quantità totale, mantiene l'ordine di inserimento
    private final Map<String, Integer> soldItemsThisPeriod = new LinkedHashMap<>();
    private final Map<String, Integer> boughtItemsThisPeriod = new LinkedHashMap<>();

    // Rimuove tutto tranne lettere, numeri, spazi, virgole e punti (elimina simboli custom/colori del server)
    private static final Pattern CLEANUP = Pattern.compile("[^\\p{L}\\p{N}\\s.,]");
    // Pattern completo: quantità, item, prezzo (es. "81 x Glowstone per 595.34")
    private static final Pattern FULL_PATTERN = Pattern.compile("(?i)(\\d+)\\s*x\\s*(.+?)\\s*per\\s*([0-9.,]+)");
    // Fallback: solo il prezzo, se il pattern completo non trova match (comportamento di prima, invariato)
    private static final Pattern PRICE_PATTERN = Pattern.compile("(?i)per\\s*([0-9.,]+)");

    public ShopTracker() {
        super(AddonTemplate.CATEGORY, "shop-tracker", "Traccia guadagni/spese dello shop e stampa uno scontrino periodico.");
    }

    @Override
    public void onActivate() {
        earnedThisPeriod = 0;
        spentThisPeriod = 0;
        soldItemsThisPeriod.clear();
        boughtItemsThisPeriod.clear();
        lastReportTime = System.currentTimeMillis();
    }

    private double parseAmount(String raw) {
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

        String cleaned = CLEANUP.matcher(raw).replaceAll("").replaceAll("\\s+", " ").trim();
        String lower = cleaned.toLowerCase();

        boolean isSell = lower.contains(sellKeyword.get().toLowerCase());
        boolean isBuy = lower.contains(buyKeyword.get().toLowerCase());

        if (!isSell && !isBuy) return;

        Matcher fullMatch = FULL_PATTERN.matcher(cleaned);
        if (fullMatch.find()) {
            int qty;
            try {
                qty = Integer.parseInt(fullMatch.group(1));
            } catch (NumberFormatException e) {
                qty = 0;
            }
            String itemName = fullMatch.group(2).trim();
            double amount = parseAmount(fullMatch.group(3));

            if (isSell) {
                earnedThisPeriod += amount;
                soldItemsThisPeriod.merge(itemName, qty, Integer::sum);
            } else {
                spentThisPeriod += amount;
                boughtItemsThisPeriod.merge(itemName, qty, Integer::sum);
            }
            return;
        }

        // Fallback: se il pattern completo non matcha, conta almeno il prezzo (come prima)
        Matcher priceMatch = PRICE_PATTERN.matcher(cleaned);
        if (!priceMatch.find()) return;

        double amount = parseAmount(priceMatch.group(1));
        if (isSell) {
            earnedThisPeriod += amount;
        } else {
            spentThisPeriod += amount;
        }
    }

    private String buildItemList(Map<String, Integer> items) {
        if (items.isEmpty()) return "-";

        StringJoiner joiner = new StringJoiner(", ");
        for (Map.Entry<String, Integer> entry : items.entrySet()) {
            joiner.add(entry.getValue() + "x " + entry.getKey());
        }
        return joiner.toString();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        long now = System.currentTimeMillis();
        long intervalMs = intervalMinutes.get() * 60_000L;

        if (now - lastReportTime >= intervalMs) {
            if (!silentIfNoActivity.get() || earnedThisPeriod > 0 || spentThisPeriod > 0) {
                String out = receiptFormat.get()
                    .replace("{earned}", String.format("%.2f", earnedThisPeriod))
                    .replace("{spent}", String.format("%.2f", spentThisPeriod))
                    .replace("{sold_items}", buildItemList(soldItemsThisPeriod))
                    .replace("{bought_items}", buildItemList(boughtItemsThisPeriod));

                if (sendToServer.get()) {
                    ChatUtils.sendPlayerMsg(out);
                } else {
                    ChatUtils.info(out);
                }
            }

            earnedThisPeriod = 0;
            spentThisPeriod = 0;
            soldItemsThisPeriod.clear();
            boughtItemsThisPeriod.clear();
            lastReportTime = now;
        }
    }
}
