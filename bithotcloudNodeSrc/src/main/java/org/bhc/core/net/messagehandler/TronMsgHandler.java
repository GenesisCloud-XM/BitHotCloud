package org.bhc.core.net.messagehandler;

import org.bhc.core.exception.P2pException;
import org.bhc.core.net.message.TronMessage;
import org.bhc.core.net.peer.PeerConnection;

public interface TronMsgHandler {

  void processMessage(PeerConnection peer, TronMessage msg) throws P2pException;

}
