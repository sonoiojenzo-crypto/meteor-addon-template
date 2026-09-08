
package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.passive.SheepEntity;
import net.minecraft.util.math.Box;

public class SheepEsp extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<ShapeMode> shapeMode = sgGeneral.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("Come disegnare l'ESP.")
        .defaultValue(ShapeMode.Both)
        .build()
    );

    private final Setting<SettingColor> sideColor = sgGeneral.add(new ColorSetting.Builder()
        .name("colore-riempimento")
        .description("Colore del riempimento del box.")
        .defaultValue(new SettingColor(255, 255, 255, 50))
        .build()
    );

    private final Setting<SettingColor> lineColor = sgGeneral.add(new ColorSetting.Builder()
        .name("colore-bordo")
        .description("Colore del bordo del box.")
        .defaultValue(new SettingColor(255, 255, 255, 255))
        .build()
    );

    private final Setting<Boolean> onlyColoredSheep = sgGeneral.add(new BoolSetting.Builder()
        .name("solo-pecore-colorate")
        .description("Evidenzia solo pecore con lana colorata (non bianche).")
        .defaultValue(false)
        .build()
    );

    public SheepEsp() {
        super(AddonTemplate.CATEGORY, "sheep-esp", "Evidenzia le pecore attraverso i muri.");
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        for (var entity : mc.world.getEntities()) {
            if (!(entity instanceof SheepEntity sheep)) continue;
            if (onlyColoredSheep.get() && sheep.getColor().getId() == 15) continue; // 15 = bianco/default

            Box box = sheep.getBoundingBox();

            // Interpola la posizione per un rendering fluido tra i tick
            double x = sheep.prevX + (sheep.getX() - sheep.prevX) * event.tickDelta;
            double y = sheep.prevY + (sheep.getY() - sheep.prevY) * event.tickDelta;
            double z = sheep.prevZ + (sheep.getZ() - sheep.prevZ) * event.tickDelta;

            Box renderBox = box.offset(x - sheep.getX(), y - sheep.getY(), z - sheep.getZ());

            event.renderer.box(
                renderBox,
                sideColor.get(), lineColor.get(),
                shapeMode.get(), 0
            );
        }
    }
}
