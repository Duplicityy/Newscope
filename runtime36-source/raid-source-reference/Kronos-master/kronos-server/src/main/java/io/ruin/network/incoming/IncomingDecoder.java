package io.ruin.network.incoming;

import io.ruin.Server;
import io.ruin.api.buffer.InBuffer;
import io.ruin.api.netty.Message;
import io.ruin.api.netty.MessageDecoder;
import io.ruin.api.utils.ISAACCipher;
import io.ruin.model.entity.player.Player;

public class IncomingDecoder extends MessageDecoder<Player> {

    private long timeoutAt = Long.MAX_VALUE;

    public IncomingDecoder(ISAACCipher cipher) {
        super(cipher, true);
    }

    @Override
    protected void add(Message message) {
        if(timeoutAt != -1)
            timeoutAt = Server.getEnd(50);
        if(Incoming.IGNORED[message.opcode]) {
            message.buffer = null;
            return;
        }
        super.add(message);
    }

    @Override
    protected void handle(Player player, InBuffer in, int opcode) {
        Incoming handler = Incoming.HANDLERS[opcode];
        if(handler == null) {
            if(player.debug)
                player.sendFilteredMessage("Unhandled incoming opcode: " + opcode);
            return;
        }
        io.ruin.model.activities.raids.auto.RaidAuto.manualInput(player, handler, opcode);
        io.ruin.model.activities.dungeoneering.IDungeon.manualInput(player, handler, opcode);
        handler.handle(player, in, opcode);
        if (io.ruin.content.sixsystems.SixSystems.enabled("thanks")) {
            String action = handler.getClass().getSimpleName();
            if (action.equals("WalkHandler") || action.equals("ChatHandler") || action.equals("CommandHandler")
                    || action.equals("ActionButtonHandler") || action.equals("ObjectActionHandler")
                    || action.equals("NPCActionHandler") || action.equals("InterfaceOnInterfaceHandler")
                    || action.equals("GroundItemActionHandler"))
                io.ruin.content.sixsystems.CommunityThanks.activity(player);
        }
    }

    @Override
    protected int getSize(int opcode) {
        if(opcode < 0 || opcode >= Incoming.SIZES.length)
            return UNHANDLED;
        return Incoming.SIZES[opcode];
    }

    public boolean timeout() {
        if(timeoutAt == -1)
            return true;
        if(!Server.isPast(timeoutAt))
            return false;
        timeoutAt = -1;
        return true;
    }

}
