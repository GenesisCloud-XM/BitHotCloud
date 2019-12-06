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

    //找出对方缺少的block，
    LinkedList<BlockId> blockIds = getLostBlockIds(summaryChainIds);

    if (blockIds.size() == 1) {
      peer.setNeedSyncFromUs(false);
    } else {
      peer.setNeedSyncFromUs(true);
      
      //计算双方区块的差距，这就是剩下的区块数量
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

    //最新同步区块id应该一直 <=lastNum, 这才是正常的。  
    BlockId lastSyncBlockId = peer.getLastSyncBlockId();
    long lastNum = blockIds.get(blockIds.size() - 1).getNum();
    if (lastSyncBlockId != null && lastSyncBlockId.getNum() > lastNum) {
      throw new P2pException(TypeEnum.BAD_MESSAGE,
          "lastSyncNum:" + lastSyncBlockId.getNum() + " gt lastNum:" + lastNum);
    }
  }

  //在主链上找出缺少的区块， 最多一次包含2000个。
  private LinkedList<BlockId> getLostBlockIds(List<BlockId> blockIds) throws P2pException {
  
    BlockId unForkId = null;
    //倒序查找id，因为在blockIds中最后一个id就是请求方的最新区块。只要主链上找到这个id就可以从此开始同步。
    //如果主链上找不到最后一个id，就要依次找前一个id。由于请求方的id排列顺序是二分法得来的，猜测这样可以快速缩小查找范围，不用逐一查找。
    //另外，此处可能设计到分叉链，所以只在主链上操作。 一定找出同步流程的起始区块unForkId。
    for (int i = blockIds.size() - 1; i >= 0; i--) {
      if (tronNetDelegate.containBlockInMainChain(blockIds.get(i))) {
        unForkId = blockIds.get(i);
        break;
      }
    }

    //每次最多2000个，就从起始区块unForkId+2000；考虑到本链的头区块高度，取小者作为同步的长度
    long len = Math.min(tronNetDelegate.getHeadBlockId().getNum(),
        unForkId.getNum() + NodeConstant.SYNC_FETCH_BATCH_NUM);

    /*把这些区块编号全部组装成列表，准备发送应答。
                  这里包含起点区块id+2000个区块id，总共可能有2001个。原因是起始区块后面还有2000个新区块。
                  当全部同步完毕了，这时len=unForkId=head，所以ids只添加了head区块id。这个应答发回去请求方就知道同步完毕了。
    */
    LinkedList<BlockId> ids = new LinkedList<>();
    for (long i = unForkId.getNum(); i <= len; i++) {
      BlockId id = tronNetDelegate.getBlockIdByNum(i);
      ids.add(id);
    }
    return ids;
  }

}
