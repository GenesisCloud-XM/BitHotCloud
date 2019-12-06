package org.bhc.core.net.messagehandler;

import java.util.LinkedList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.bhc.core.capsule.BlockCapsule.BlockId;
import org.bhc.core.config.Parameter.NodeConstant;
import org.bhc.core.exception.P2pException;
import org.bhc.core.exception.P2pException.TypeEnum;
import org.bhc.core.net.TronNetDelegate;
import org.bhc.core.net.message.ChainInventoryMessage;
import org.bhc.core.net.message.SyncBlockChainMessage;
import org.bhc.core.net.message.TronMessage;
import org.bhc.core.net.peer.PeerConnection;

@Slf4j
@Component
public class SyncBlockChainMsgHandler implements TronMsgHandler {

  @Autowired
  private TronNetDelegate tronNetDelegate;

  @Override
  public void processMessage(PeerConnection peer, TronMessage msg) throws P2pException {

    SyncBlockChainMessage syncBlockChainMessage = (SyncBlockChainMessage) msg;

    check(peer, syncBlockChainMessage);

    long remainNum = 0;

    List<BlockId> summaryChainIds = syncBlockChainMessage.getBlockIds();

    //�ҳ��Է�ȱ�ٵ�block��
    LinkedList<BlockId> blockIds = getLostBlockIds(summaryChainIds);

    if (blockIds.size() == 1) {
      peer.setNeedSyncFromUs(false);
    } else {
      peer.setNeedSyncFromUs(true);
      
      //����˫������Ĳ�࣬�����ʣ�µ���������
      remainNum = tronNetDelegate.getHeadBlockId().getNum() - blockIds.peekLast().getNum();
    }
//
//    if (!peer.isNeedSyncFromPeer()
//        && !tronNetDelegate.contain(Iterables.getLast(summaryChainIds), MessageTypes.BLOCK)
//        && tronNetDelegate.canChainRevoke(summaryChainIds.get(0).getNum())) {
//      //startSyncWithPeer(peer);
//    }

    peer.setLastSyncBlockId(blockIds.peekLast());
    peer.setRemainNum(remainNum);
    peer.sendMessage(new ChainInventoryMessage(blockIds, remainNum));
  }

  private void check(PeerConnection peer, SyncBlockChainMessage msg) throws P2pException {
    List<BlockId> blockIds = msg.getBlockIds();
    if (CollectionUtils.isEmpty(blockIds)) {
      throw new P2pException(TypeEnum.BAD_MESSAGE, "SyncBlockChain blockIds is empty");
    }

    BlockId firstId = blockIds.get(0);
    if (!tronNetDelegate.containBlockInMainChain(firstId)) {
      throw new P2pException(TypeEnum.BAD_MESSAGE, "No first block:" + firstId.getString());
    }

    long headNum = tronNetDelegate.getHeadBlockId().getNum();
    if (firstId.getNum() > headNum) {
      throw new P2pException(TypeEnum.BAD_MESSAGE,
          "First blockNum:" + firstId.getNum() + " gt my head BlockNum:" + headNum);
    }

    //����ͬ������idӦ��һֱ <=lastNum, ����������ġ�  
    BlockId lastSyncBlockId = peer.getLastSyncBlockId();
    long lastNum = blockIds.get(blockIds.size() - 1).getNum();
    if (lastSyncBlockId != null && lastSyncBlockId.getNum() > lastNum) {
      throw new P2pException(TypeEnum.BAD_MESSAGE,
          "lastSyncNum:" + lastSyncBlockId.getNum() + " gt lastNum:" + lastNum);
    }
  }

  //���������ҳ�ȱ�ٵ����飬 ���һ�ΰ���2000����
  private LinkedList<BlockId> getLostBlockIds(List<BlockId> blockIds) throws P2pException {
  
    BlockId unForkId = null;
    //�������id����Ϊ��blockIds�����һ��id�������󷽵��������顣ֻҪ�������ҵ����id�Ϳ��ԴӴ˿�ʼͬ����
    //����������Ҳ������һ��id����Ҫ������ǰһ��id���������󷽵�id����˳���Ƕ��ַ������ģ��²��������Կ�����С���ҷ�Χ��������һ���ҡ�
    //���⣬�˴�������Ƶ��ֲ���������ֻ�������ϲ����� һ���ҳ�ͬ�����̵���ʼ����unForkId��
    for (int i = blockIds.size() - 1; i >= 0; i--) {
      if (tronNetDelegate.containBlockInMainChain(blockIds.get(i))) {
        unForkId = blockIds.get(i);
        break;
      }
    }

    //ÿ�����2000�����ʹ���ʼ����unForkId+2000�����ǵ�������ͷ����߶ȣ�ȡС����Ϊͬ���ĳ���
    long len = Math.min(tronNetDelegate.getHeadBlockId().getNum(),
        unForkId.getNum() + NodeConstant.SYNC_FETCH_BATCH_NUM);

    /*����Щ������ȫ����װ���б�׼������Ӧ��
                  ��������������id+2000������id���ܹ�������2001����ԭ������ʼ������滹��2000�������顣
                  ��ȫ��ͬ������ˣ���ʱlen=unForkId=head������idsֻ�����head����id�����Ӧ�𷢻�ȥ���󷽾�֪��ͬ������ˡ�
    */
    LinkedList<BlockId> ids = new LinkedList<>();
    for (long i = unForkId.getNum(); i <= len; i++) {
      BlockId id = tronNetDelegate.getBlockIdByNum(i);
      ids.add(id);
    }
    return ids;
  }

}
