package org.bhc.core.net.messagehandler;

import static org.bhc.core.config.Parameter.ChainConstant.BLOCK_PRODUCED_INTERVAL;
import static org.bhc.core.config.Parameter.ChainConstant.BLOCK_SIZE;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.bhc.core.capsule.BlockCapsule;
import org.bhc.core.capsule.BlockCapsule.BlockId;
import org.bhc.core.config.args.Args;
import org.bhc.core.exception.P2pException;
import org.bhc.core.exception.P2pException.TypeEnum;
import org.bhc.core.net.TronNetDelegate;
import org.bhc.core.net.message.BlockMessage;
import org.bhc.core.net.message.TronMessage;
import org.bhc.core.net.peer.Item;
import org.bhc.core.net.peer.PeerConnection;
import org.bhc.core.net.service.AdvService;
import org.bhc.core.net.service.SyncService;
import org.bhc.core.services.WitnessProductBlockService;
import org.bhc.protos.Protocol.Inventory.InventoryType;

//p2p接收到Block消息，专门的处理类
@Slf4j
@Component
public class BlockMsgHandler implements TronMsgHandler {

  @Autowired
  private TronNetDelegate tronNetDelegate;

  @Autowired
  private AdvService advService;

  @Autowired
  private SyncService syncService;

  @Autowired
  private WitnessProductBlockService witnessProductBlockService;

  private int maxBlockSize = BLOCK_SIZE + 1000;

  private boolean fastForward = Args.getInstance().isFastForward();

  @Override
  public void processMessage(PeerConnection peer, TronMessage msg) throws P2pException {

    BlockMessage blockMessage = (BlockMessage) msg;

    check(peer, blockMessage);

    BlockId blockId = blockMessage.getBlockId();
    Item item = new Item(blockId, InventoryType.BLOCK);
    
    /* 这里分两种情况： 一种是同步过程，一种是正常运行中。
     * 1 同步过程中： 同步过程会先请求希望接收的区块id，保存在getSyncBlockRequested()队列中；
     *   因此收到的区块id应该在这个请求队列中有据可查。 实际trace也表明 接收同步Block时执行if分支。
     *   
     * 2 正常运行： 运行中收到了一个其他节点广播的Block消息，执行else分支。
     * */
    if (peer.getSyncBlockRequested().containsKey(blockId)) {
    	System.out.println("----BlockMsgHandler  000   ");
      peer.getSyncBlockRequested().remove(blockId);  //收到了区块，从请求队列中删除。影响着peer的idle状态
      syncService.processBlock(peer, blockMessage);
    } else {
    	System.out.println("----BlockMsgHandler  111   ");
      peer.getAdvInvRequest().remove(item);
      processBlock(peer, blockMessage.getBlockCapsule());
    }
  }

  private void check(PeerConnection peer, BlockMessage msg) throws P2pException {
    Item item = new Item(msg.getBlockId(), InventoryType.BLOCK);
    if (!peer.getSyncBlockRequested().containsKey(msg.getBlockId()) && !peer.getAdvInvRequest()
        .containsKey(item)) {
      throw new P2pException(TypeEnum.BAD_MESSAGE, "no request");
    }
    BlockCapsule blockCapsule = msg.getBlockCapsule();
    if (blockCapsule.getInstance().getSerializedSize() > maxBlockSize) {
      throw new P2pException(TypeEnum.BAD_MESSAGE, "block size over limit");
    }
    long gap = blockCapsule.getTimeStamp() - System.currentTimeMillis();
    if (gap >= BLOCK_PRODUCED_INTERVAL) {
      throw new P2pException(TypeEnum.BAD_MESSAGE, "block time error");
    }
  }

  private void processBlock(PeerConnection peer, BlockCapsule block) throws P2pException {
	  System.out.println("----BlockMsgHandler  processBlock...   ");
	  //为了防止收到的区块是断裂的编号， 必须检查父块id是否已经存在。
	  //如果父块id不存在，说明中间缺少了某些区块，此时就必须开始同步过程，把缺少的区块补上。
    BlockId blockId = block.getBlockId();
    if (!tronNetDelegate.containBlock(block.getParentBlockId())) {
      logger.warn("Get unlink block {} from {}, head is {}.", blockId.getString(),
          peer.getInetAddress(), tronNetDelegate.getHeadBlockId().getString());
      syncService.startSync(peer);
      return;
    }

    if (fastForward && tronNetDelegate.validBlock(block)) {
      advService.broadcast(new BlockMessage(block));
    }

    //保存区块到数据库, 流程很长长长长
    tronNetDelegate.processBlock(block);
    
    //检查骗子代表
    witnessProductBlockService.validWitnessProductTwoBlock(block);
    
    //遍历连接，更新共同Block。有啥子意义呢？ 猜不透啊
    tronNetDelegate.getActivePeer().forEach(p -> {
      if (p.getAdvInvReceive().getIfPresent(blockId) != null) {
        p.setBlockBothHave(blockId);
      }
    });

    if (!fastForward) {
      advService.broadcast(new BlockMessage(block));
    }
  }

}
