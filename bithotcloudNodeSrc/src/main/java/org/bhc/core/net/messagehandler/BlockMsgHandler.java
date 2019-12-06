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

//p2p���յ�Block��Ϣ��ר�ŵĴ�����
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
    
    /* �������������� һ����ͬ�����̣�һ�������������С�
     * 1 ͬ�������У� ͬ�����̻�������ϣ�����յ�����id��������getSyncBlockRequested()�����У�
     *   ����յ�������idӦ�����������������оݿɲ顣 ʵ��traceҲ���� ����ͬ��Blockʱִ��if��֧��
     *   
     * 2 �������У� �������յ���һ�������ڵ�㲥��Block��Ϣ��ִ��else��֧��
     * */
    if (peer.getSyncBlockRequested().containsKey(blockId)) {
    	System.out.println("----BlockMsgHandler  000   ");
      peer.getSyncBlockRequested().remove(blockId);  //�յ������飬�����������ɾ����Ӱ����peer��idle״̬
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
	  //Ϊ�˷�ֹ�յ��������Ƕ��ѵı�ţ� �����鸸��id�Ƿ��Ѿ����ڡ�
	  //�������id�����ڣ�˵���м�ȱ����ĳЩ���飬��ʱ�ͱ��뿪ʼͬ�����̣���ȱ�ٵ����鲹�ϡ�
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

    //�������鵽���ݿ�, ���̺ܳ�������
    tronNetDelegate.processBlock(block);
    
    //���ƭ�Ӵ���
    witnessProductBlockService.validWitnessProductTwoBlock(block);
    
    //�������ӣ����¹�ͬBlock����ɶ�������أ� �²�͸��
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
