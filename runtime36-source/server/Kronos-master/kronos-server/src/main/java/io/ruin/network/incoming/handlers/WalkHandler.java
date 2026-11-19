package io.ruin.network.incoming.handlers;

import io.ruin.api.buffer.InBuffer;
import io.ruin.model.World;
import io.ruin.model.activities.legacytournament.TournamentViewingOrb;
import io.ruin.model.entity.npc.NPC;
import io.ruin.model.entity.player.Player;
import io.ruin.model.inter.Interface;
import io.ruin.model.map.route.MapWalkRoute;
import io.ruin.network.incoming.Incoming;
import io.ruin.utility.IdHolder;

@IdHolder(ids = {98, 91, 69})
public class WalkHandler implements Incoming {

    @Override
    public void handle(Player player, InBuffer in, int opcode) {
        if (opcode == 69) {
            // A 4-byte packed client Coord, emitted only by the expanded map widget.
            int coord = in.readInt();
            if (!player.isVisibleInterface(Interface.WORLD_MAP) || (coord >>> 28) != player.getHeight())
                return;
            int x = coord >>> 14 & 0x3fff;
            int y = coord & 0x3fff;
            if (!player.isLocked() && !player.isStunned() && MapWalkRoute.validCoordinates(x, y)) {
                if (player.emoteDelay.isDelayed()) {
                    player.resetAnimation();
                    player.emoteDelay.reset();
                }
                player.resetActions(true, true, true);
                player.getMovement().setCtrlRun(false);
                MapWalkRoute.start(player, x, y);
            }
            return;
        }
        if (player.isLocked() || player.isStunned()) {
            /* is our player new to the game? */
            if(player.inTutorial) {
                return;
            }
            /* is our player currently transformed? */ // TODO  Inspect the backlashes but we shouldnt be unmorphing if locked.
            /*if (player.getAppearance().getNpcId() != -1)
                TransformationRing.unmorph(player);
            else
                return;*/
            /*if they're viewing an orb, reset viewing orb */
            if(player.usingTournamentOrb) {
                TournamentViewingOrb.reset(player);
                return;
            }

            // Return always if locked why let them move?
            return;
        }

        if(player.emoteDelay.isDelayed()) {
            player.resetAnimation();
            player.emoteDelay.reset();
        }

        player.resetActions(true, true, true);
        int x = in.readLEShortA();
        int y = in.readLEShort();
        int type = in.readByteC();
        if (player.isAdmin() || World.isDev()) {
            NPC npc = player.get("CONTROLLING_NPC");
            if (npc != null) {
                if (type == 2) {
                    npc.getMovement().teleport(x,y, player.getHeight());
                } else {
                    npc.getRouteFinder().routeAbsolute(x,y);
                }
                return;
            } else if (type == 2) {
                int z = player.getHeight();
                player.getMovement().teleport(x, y, z);
                player.sendFilteredMessage("<col=cc0000>::tele: " + x + "," + y + "," + z);
                return;
            }
        }
        player.getMovement().setCtrlRun(type == 1);
        if (opcode == 91)
            MapWalkRoute.start(player, x, y);
        else
            player.getRouteFinder().routeAbsolute(x, y, true);
    }

}