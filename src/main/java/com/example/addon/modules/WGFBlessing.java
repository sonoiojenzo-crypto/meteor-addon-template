package com.den.wgfaddon.modules;

import com.den.wgfaddon.WGFAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action;

public class WGFBlessing extends Module {
   boolean shouldAcceptPacket;
   int sequence = 0;
   int packetSent = 0;
   private final SettingGroup sgGeneral;
   private final Setting<Integer> packetAmount;
   private final Setting<Integer> maxPackets;

   public WGFBlessing() {
      super(WGFAddon.CATEGORY, "WGF-Blessing", "Grants the blessing of the gods.");
      this.sgGeneral = this.settings.getDefaultGroup();
      this.packetAmount = this.sgGeneral.add(((IntSetting.Builder)((IntSetting.Builder)(new IntSetting.Builder()).name("packets-per-tick")).min(2).max(200).sliderMin(2).sliderMax(200).defaultValue(140)).build());
      this.maxPackets = this.sgGeneral.add(((IntSetting.Builder)((IntSetting.Builder)(new IntSetting.Builder()).name("max-packets")).sliderMin(1000).sliderMax(100000).defaultValue(40000)).build());
   }

   public void onActivate() {
      this.packetSent = 0;
      this.info("Contacting the oracle, asking for flight permission :3", new Object[0]);
   }

   @EventHandler(
      priority = 200
   )
   public void onTickPre(TickEvent.Pre event) {
      if (this.mc.player != null) {
         BlockPos pos = new BlockPos((int)this.mc.player.getX(), (int)this.mc.player.getY() + 2, (int)this.mc.player.getZ());

         for(int i = 0; i < (Integer)this.packetAmount.get(); ++i) {
            ServerboundPlayerActionPacket packet = new ServerboundPlayerActionPacket(Action.START_DESTROY_BLOCK, pos, Direction.DOWN, ++this.sequence);
            ServerboundPlayerActionPacket otherPacket = new ServerboundPlayerActionPacket(Action.STOP_DESTROY_BLOCK, pos, Direction.DOWN, ++this.sequence);
            this.mc.player.connection.send(packet);
            this.mc.player.connection.send(otherPacket);
            this.info("Sending packets to the oracle of the supreme powers (" + this.packetSent + "/" + String.valueOf(this.maxPackets.get()) + ")", new Object[0]);
         }

         this.packetSent += (Integer)this.packetAmount.get();
         if (this.packetSent >= (Integer)this.maxPackets.get()) {
            this.info("The oracle has received enough data, waiting for approval owo", new Object[0]);
            this.toggle();
         }

      }
   }
}
