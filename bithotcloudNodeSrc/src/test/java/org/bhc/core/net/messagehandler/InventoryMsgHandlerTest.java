package org.bhc.core.net.messagehandler;

import java.util.ArrayList;
import org.junit.Test;
import org.bhc.core.net.message.InventoryMessage;
import org.bhc.core.net.peer.PeerConnection;
import org.bhc.protos.Protocol.Inventory.InventoryType;

public class InventoryMsgHandlerTest {

  private InventoryMsgHandler handler = new InventoryMsgHandler();
  private PeerConnection peer = new PeerConnection();

  @Test
  public void testProcessMessage() {
    InventoryMessage msg = new InventoryMessage(new ArrayList<>(), InventoryType.TRX);

    peer.setNeedSyncFromPeer(true);
    peer.setNeedSyncFromUs(true);
    handler.processMessage(peer, msg);

    peer.setNeedSyncFromPeer(true);
    peer.setNeedSyncFromUs(false);
    handler.processMessage(peer, msg);

    peer.setNeedSyncFromPeer(false);
    peer.setNeedSyncFromUs(true);
    handler.processMessage(peer, msg);

  }
}
