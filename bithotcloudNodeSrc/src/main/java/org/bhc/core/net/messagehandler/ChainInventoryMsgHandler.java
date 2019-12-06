package org.bhc.core.net.messagehandler;

import static org.bhc.core.config.Parameter.ChainConstant.BLOCK_PRODUCED_INTERVAL;

import java.util.Deque;
import java.util.LinkedList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.bhc.core.capsule.BlockCapsule.BlockId;
import org.bhc.core.config.Parameter.ChainConstant;
import org.bhc.core.config.Parameter.NodeConstant;
import org.bhc.core.exception.P2pException;
import org.bhc.core.exception.P2pException.TypeEnum;
import org.bhc.core.net.TronNetDelegate;
import org.bhc.core.net.message.ChainInventoryMessage;
import org.bhc.core.net.message.TronMessage;
import org.bhc.core.net.peer.PeerConnection;
import org.bhc.core.net.service.SyncService;

@Slf4j
@Component
public class ChainInventoryMsgHandler implements TronMsgHandler {

  @Autowired
  private TronNetDelegate tronNetDelegate;

  @Autowired
  private SyncService syncService;

  @Override
  public void processMessage(PeerConnection peer, TronMessage msg) throws P2pException {

    ChainInventoryMessage chainInventoryMessage = (ChainInventoryMessage) msg;

    check(peer, chainInventoryMessage);  //��Ӧ��������б����ݽ��а�ȫ��飬

    peer.setNeedSyncFromPeer(true);    //ͬ����ǣ�����SyncService�������Ƿ���ͬ��״̬

    peer.setSyncChainRequested(null); //todo thread sec  //������syncService.syncNext(peer)�����оͳ�ʼ�����б�

    Deque<BlockId> blockIdWeGet = new LinkedList<>(chainInventoryMessage.getBlockIds());

    //�б�ֻ��һ������id�����ұ����Ѿ�������id��˵��˫���Ѿ�ͬ�����������飬��������һ��BLOCK_CHAIN_INVENTORY��Ϣ��ͬ�����̽�����
    //���ӣ�ͬ��1~19������Ϻ��յ���BLOCK_CHAIN_INVENTORY��Ϣ��
    //size: 1, first blockId: Num:19,ID:0000000000000013486d5d70ce9f0570c520122da2a7d1569b24c3d5e3ba2053
    if (blockIdWeGet.size() == 1 && tronNetDelegate.containBlock(blockIdWeGet.peek())) {
      peer.setNeedSyncFromPeer(false);  //ȡ��ͬ�����
      return;
    }

    //��syncService.startSync()�Ѿ���ս���ͬ������Ķ��У������ڷ���SYNC_BLOCK_CHAIN֮ǰ��׼��������
    //��һ���յ�BLOCK_CHAIN_INVENTORYӦ����Ϣʱ��Ӧ�û���Ϊ�գ�ֱ��������
    //�ڶ����յ�BLOCK_CHAIN_INVENTORY��Ϣʱ�Ͳ��ǿ��ˣ����еȴ���
    while (!peer.getSyncBlockToFetch().isEmpty()) {
      if (peer.getSyncBlockToFetch().peekLast().equals(blockIdWeGet.peekFirst())) {
        break;
      }
      peer.getSyncBlockToFetch().pollLast();
    }

    /*-------------------------------*/
    //�����δ��룺������id�б����д�� peer.getSyncBlockToFetch()�б��棬�Ժ�SyncService������ȡ���顣
    blockIdWeGet.poll(); //������1��Ԫ�أ�ԭ����ͷid�����Ѿ�����
    peer.setRemainNum(chainInventoryMessage.getRemainNum());
    peer.getSyncBlockToFetch().addAll(blockIdWeGet);   //�����б���ʣ�µ�ȫ��id

    //�����б�ȥ�������Ѿ��е�����id�� Ŀ���Ǿ����б�ֻ���±���û�е�id
    synchronized (tronNetDelegate.getBlockLock()) {
      while (!peer.getSyncBlockToFetch().isEmpty() && tronNetDelegate
               .containBlock(peer.getSyncBlockToFetch().peek())) {
        BlockId blockId = peer.getSyncBlockToFetch().pop();
        logger.info("Block {} from {} is processed", blockId.getString(), peer.getNode().getHost());
      }
    }
    /*-------------------------------*/
//
//    if (chainInventoryMessage.getRemainNum() == 0 && peer.getSyncBlockToFetch().isEmpty()) {
//      peer.setNeedSyncFromPeer(false);
//    }

    /* ����˼�룺 ͬ�����鳬��2000��������4405����
     * 1���յ�������������2000��id��ʣ������2405������ʱ��ִ�� else��֧���ٴη�������SYNC_BLOCK_CHAIN
     * 2�ٴ��յ�������������2000��id��ʣ������405������ʱ�Ѿ��洢��4000��id�ˣ����ټ�������SYNC_BLOCK_CHAIN����ִ��if��֧��
     *  ����SyncService��ʱ��������ȡ���飬����FETCH_INV_DATA��Ϣ�� �Ժ�ÿ�ν��յ�Block��Ϣ���ͻ��getSyncBlockToFetch()����һ��id��
     *  ��ô���getSyncBlockToFetch()�ͻ᲻�����ļ��١� 
     *  �����ٵ�2000��ʱ���ٴ�ִ��else��֧��������������SYNC_BLOCK_CHAIN��
     * 3�����յ�������������ʣ��405��id��ʣ������Ϊ0����ʱ����id������ϣ���ʱ��ִ��if��֧������SyncService��ʱ��������ȡ���飬����FETCH_INV_DATA��Ϣ��
     *  ֮�����һֱ����Block��getSyncBlockToFetch()�ͻ᲻�����ļ���ֱ��Ϊ�ա�
     * 4 ����һ������ǣ�ʣ������Ϊ0��getSyncBlockToFetch()Ϊ�գ�����ִ��else��֧���������һ�ε�����SYNC_BLOCK_CHAIN����־��ͬ�����̽�����
     * */
    System.out.println("----processMessage:  chainInventoryMessage.getRemainNum()= "+chainInventoryMessage.getRemainNum());
    System.out.println("----peer.getSyncBlockToFetch().size()="+peer.getSyncBlockToFetch().size());
    if ((chainInventoryMessage.getRemainNum() == 0 && !peer.getSyncBlockToFetch().isEmpty()) ||
        (chainInventoryMessage.getRemainNum() != 0
            && peer.getSyncBlockToFetch().size() > NodeConstant.SYNC_FETCH_BATCH_NUM)) {
    	System.out.println("----processMessage:  0000 ");
        syncService.setFetchFlag(true);  //���û�ȡ������, �򿪺��֪ͨ��SyncService�Ķ�ʱ����ʼ�ɻ��ˡ�
    } else {
    	System.out.println("----processMessage:  1111 ");
        syncService.syncNext(peer);
    }
  }
  
  //��Ӧ��������б����ݽ��а�ȫ��顣 ������Ϊ���á�������������������������ͷ��һ�¡���
  private void check(PeerConnection peer, ChainInventoryMessage msg) throws P2pException {
	//������syncService.syncNext(peer)�����оͳ�ʼ�����б�  
    if (peer.getSyncChainRequested() == null) {
      throw new P2pException(TypeEnum.BAD_MESSAGE, "not send syncBlockChainMsg");
    }

    //����б�Ϊ��
    List<BlockId> blockIds = msg.getBlockIds();
    if (CollectionUtils.isEmpty(blockIds)) {
      throw new P2pException(TypeEnum.BAD_MESSAGE, "blockIds is empty");
    }

    //����б���������̫��
    if (blockIds.size() > NodeConstant.SYNC_FETCH_BATCH_NUM + 1) {
      throw new P2pException(TypeEnum.BAD_MESSAGE, "big blockIds size: " + blockIds.size());
    }

    //�б���������2000�����Ƿ�����������ʣ�����飬����ë����
    if (msg.getRemainNum() != 0 && blockIds.size() < NodeConstant.SYNC_FETCH_BATCH_NUM) {
      throw new P2pException(TypeEnum.BAD_MESSAGE,
          "remain: " + msg.getRemainNum() + ", blockIds size: " + blockIds.size());
    }
    //�б��е����������������������ж���Ծ
    long num = blockIds.get(0).getNum();
    for (BlockId id : msg.getBlockIds()) {
      if (id.getNum() != num++) {
        throw new P2pException(TypeEnum.BAD_MESSAGE, "not continuous block");
      }
    }
    //�����б�������б�ĵ�0������id����֤����ͷ��һ�µġ�
    //�����һ�����󷵻ص��б��ţ�������0~19�� 0����˫����ͬ�Ĵ������顣
    if (!peer.getSyncChainRequested().getKey().contains(blockIds.get(0))) {
      throw new P2pException(TypeEnum.BAD_MESSAGE, "unlinked block, my head: "
          + peer.getSyncChainRequested().getKey().getLast().getString()
          + ", peer: " + blockIds.get(0).getString());
    }
    //��������ͷ���Ǵ������飬���յ�ǰʱ������ʣ����������maxFutureNumӦ����׼ȷ�ģ��б��м���õ���ʣ�����������ܳ���ǰ�ߡ�
    if (tronNetDelegate.getHeadBlockId().getNum() > 0) {
      long maxRemainTime =
          ChainConstant.CLOCK_MAX_DELAY + System.currentTimeMillis() - tronNetDelegate
              .getBlockTime(tronNetDelegate.getSolidBlockId());
      long maxFutureNum =
          maxRemainTime / BLOCK_PRODUCED_INTERVAL + tronNetDelegate.getSolidBlockId().getNum();
      long lastNum = blockIds.get(blockIds.size() - 1).getNum();
      if (lastNum + msg.getRemainNum() > maxFutureNum) {
        throw new P2pException(TypeEnum.BAD_MESSAGE, "lastNum: " + lastNum + " + remainNum: "
            + msg.getRemainNum() + " > futureMaxNum: " + maxFutureNum);
      }
    }
  }

}
