package org.bhc.core.net;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.bhc.common.overlay.message.Message;
import org.bhc.common.overlay.server.ChannelManager;
import org.bhc.core.exception.P2pException;
import org.bhc.core.exception.P2pException.TypeEnum;
import org.bhc.core.net.message.TronMessage;
import org.bhc.core.net.messagehandler.BlockMsgHandler;
import org.bhc.core.net.messagehandler.ChainInventoryMsgHandler;
import org.bhc.core.net.messagehandler.FetchInvDataMsgHandler;
import org.bhc.core.net.messagehandler.InventoryMsgHandler;
import org.bhc.core.net.messagehandler.SyncBlockChainMsgHandler;
import org.bhc.core.net.messagehandler.TransactionsMsgHandler;
import org.bhc.core.net.peer.PeerConnection;
import org.bhc.core.net.peer.PeerStatusCheck;
import org.bhc.core.net.service.AdvService;
import org.bhc.core.net.service.SyncService;
import org.bhc.protos.Protocol.ReasonCode;

@Slf4j
@Component
public class TronNetService {

  @Autowired
  private ChannelManager channelManager;

  @Autowired
  private AdvService advService;

  @Autowired
  private SyncService syncService;

  @Autowired
  private PeerStatusCheck peerStatusCheck;

  @Autowired
  private SyncBlockChainMsgHandler syncBlockChainMsgHandler;   //专门处理SYNC_BLOCK_CHAIN消息，接收区块同步数据

  @Autowired
  private ChainInventoryMsgHandler chainInventoryMsgHandler;  //专门处理BLOCK_CHAIN_INVENTORY消息，接收区块同步数据

  @Autowired
  private InventoryMsgHandler inventoryMsgHandler;


  @Autowired
  private FetchInvDataMsgHandler fetchInvDataMsgHandler;  //专门处理FETCH_INV_DATA消息，接收区块同步数据

  @Autowired
  private BlockMsgHandler blockMsgHandler;

  @Autowired
  private TransactionsMsgHandler transactionsMsgHandler;

  public void start() {
    channelManager.init();
    advService.init();
    syncService.init();
    peerStatusCheck.init();
    transactionsMsgHandler.init();
    logger.info("TronNetService start successfully.");
  }

  public void close() {
    channelManager.close();
    advService.close();
    syncService.close();
    peerStatusCheck.close();
    transactionsMsgHandler.close();
    logger.info("TronNetService closed successfully.");
  }

  public void broadcast(Message msg) {
    advService.broadcast(msg);
  }

  protected void onMessage(PeerConnection peer, TronMessage msg) {
    try {
      switch (msg.getType()) {
        case SYNC_BLOCK_CHAIN:
          syncBlockChainMsgHandler.processMessage(peer, msg);
          break;
        case BLOCK_CHAIN_INVENTORY:
          chainInventoryMsgHandler.processMessage(peer, msg);
          break;
        case INVENTORY:
          inventoryMsgHandler.processMessage(peer, msg);
          break;
        case FETCH_INV_DATA:
          fetchInvDataMsgHandler.processMessage(peer, msg);
          break;
        case BLOCK:
          blockMsgHandler.processMessage(peer, msg);
          break;
        case TRXS:
          transactionsMsgHandler.processMessage(peer, msg);
          break;
        default:
          throw new P2pException(TypeEnum.NO_SUCH_MESSAGE, msg.getType().toString());
      }
    } catch (Exception e) {
      processException(peer, msg, e);
    }
  }

  private void processException(PeerConnection peer, TronMessage msg, Exception ex) {
    ReasonCode code = null;

    if (ex instanceof P2pException) {
      TypeEnum type = ((P2pException) ex).getType();
      switch (type) {
        case BAD_TRX:
          code = ReasonCode.BAD_TX;
          break;
        case BAD_BLOCK:
          code = ReasonCode.BAD_BLOCK;
          break;
        case NO_SUCH_MESSAGE:
        case MESSAGE_WITH_WRONG_LENGTH:
        case BAD_MESSAGE:
          code = ReasonCode.BAD_PROTOCOL;
          break;
        case SYNC_FAILED:
          code = ReasonCode.SYNC_FAIL;
          break;
        case UNLINK_BLOCK:
          code = ReasonCode.UNLINKABLE;
          break;
        case DEFAULT:
          code = ReasonCode.UNKNOWN;
          break;
      }
      logger.error("Message from {} process failed, {} \n type: {}, detail: {}.",
          peer.getInetAddress(), msg, type, ex.getMessage());
    } else {
      code = ReasonCode.UNKNOWN;
      logger.error("Message from {} process failed, {}",
          peer.getInetAddress(), msg, ex);
    }

    peer.disconnect(code);
  }
}
