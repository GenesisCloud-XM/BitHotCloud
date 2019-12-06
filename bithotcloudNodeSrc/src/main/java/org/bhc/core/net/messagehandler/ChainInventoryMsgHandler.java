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

    check(peer, chainInventoryMessage);  //对应答的区块列表数据进行安全检查，

    peer.setNeedSyncFromPeer(true);    //同步标记，用于SyncService服务检查是否处于同步状态

    peer.setSyncChainRequested(null); //todo thread sec  //这是在syncService.syncNext(peer)函数中就初始化了列表。

    Deque<BlockId> blockIdWeGet = new LinkedList<>(chainInventoryMessage.getBlockIds());

    //列表只有一个区块id，而且本机已经包含该id，说明双方已经同步完所有区块，这是最后的一条BLOCK_CHAIN_INVENTORY消息，同步流程结束。
    //例子，同步1~19区块完毕后收到的BLOCK_CHAIN_INVENTORY消息：
    //size: 1, first blockId: Num:19,ID:0000000000000013486d5d70ce9f0570c520122da2a7d1569b24c3d5e3ba2053
    if (blockIdWeGet.size() == 1 && tronNetDelegate.containBlock(blockIdWeGet.peek())) {
      peer.setNeedSyncFromPeer(false);  //取消同步标记
      return;
    }

    //在syncService.startSync()已经清空接收同步区块的队列，这是在发送SYNC_BLOCK_CHAIN之前的准备动作。
    //第一次收到BLOCK_CHAIN_INVENTORY应答消息时，应该还是为空，直接跳过。
    //第二次收到BLOCK_CHAIN_INVENTORY消息时就不是空了，进行等待。
    while (!peer.getSyncBlockToFetch().isEmpty()) {
      if (peer.getSyncBlockToFetch().peekLast().equals(blockIdWeGet.peekFirst())) {
        break;
      }
      peer.getSyncBlockToFetch().pollLast();
    }

    /*-------------------------------*/
    //这两段代码：把区块id列表精简后写入 peer.getSyncBlockToFetch()中保存，以后SyncService用来获取区块。
    blockIdWeGet.poll(); //弹出第1个元素，原因是头id本机已经有了
    peer.setRemainNum(chainInventoryMessage.getRemainNum());
    peer.getSyncBlockToFetch().addAll(blockIdWeGet);   //保存列表中剩下的全部id

    //遍历列表，去除本机已经有的区块id。 目的是精简列表，只留下本机没有的id
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

    /* 核心思想： 同步区块超过2000个，例如4405个：
     * 1先收到服务器发来的2000个id，剩余数量2405个。这时会执行 else分支，再次发出请求SYNC_BLOCK_CHAIN
     * 2再次收到服务器发来的2000个id，剩余数量405个，这时已经存储了4000个id了，不再继续请求SYNC_BLOCK_CHAIN，会执行if分支，
     *  启动SyncService定时器用来获取区块，发出FETCH_INV_DATA消息。 以后每次接收到Block消息，就会从getSyncBlockToFetch()弹出一个id，
     *  那么这个getSyncBlockToFetch()就会不断消耗减少。 
     *  当减少到2000个时，再次执行else分支，继续请求请求SYNC_BLOCK_CHAIN。
     * 3接着收到服务器发来的剩下405个id，剩余数量为0，此时区块id接收完毕；这时会执行if分支，启动SyncService定时器用来获取区块，发出FETCH_INV_DATA消息。
     *  之后就是一直接收Block，getSyncBlockToFetch()就会不断消耗减少直到为空。
     * 4 最后的一种情况是：剩余数量为0，getSyncBlockToFetch()为空，这是执行else分支，发出最后一次的请求SYNC_BLOCK_CHAIN，标志着同步流程结束。
     * */
    System.out.println("----processMessage:  chainInventoryMessage.getRemainNum()= "+chainInventoryMessage.getRemainNum());
    System.out.println("----peer.getSyncBlockToFetch().size()="+peer.getSyncBlockToFetch().size());
    if ((chainInventoryMessage.getRemainNum() == 0 && !peer.getSyncBlockToFetch().isEmpty()) ||
        (chainInventoryMessage.getRemainNum() != 0
            && peer.getSyncBlockToFetch().size() > NodeConstant.SYNC_FETCH_BATCH_NUM)) {
    	System.out.println("----processMessage:  0000 ");
        syncService.setFetchFlag(true);  //设置获取区块标记, 打开后就通知了SyncService的定时器开始干活了。
    } else {
    	System.out.println("----processMessage:  1111 ");
        syncService.syncNext(peer);
    }
  }
  
  //对应答的区块列表数据进行安全检查。 个人认为有用“区块号码必须连续”，“区块头是一致”。
  private void check(PeerConnection peer, ChainInventoryMessage msg) throws P2pException {
	//这是在syncService.syncNext(peer)函数中就初始化了列表。  
    if (peer.getSyncChainRequested() == null) {
      throw new P2pException(TypeEnum.BAD_MESSAGE, "not send syncBlockChainMsg");
    }

    //检查列表为空
    List<BlockId> blockIds = msg.getBlockIds();
    if (CollectionUtils.isEmpty(blockIds)) {
      throw new P2pException(TypeEnum.BAD_MESSAGE, "blockIds is empty");
    }

    //检查列表数量不能太大
    if (blockIds.size() > NodeConstant.SYNC_FETCH_BATCH_NUM + 1) {
      throw new P2pException(TypeEnum.BAD_MESSAGE, "big blockIds size: " + blockIds.size());
    }

    //列表数量不足2000，但是服务器方还有剩余区块，这是毛病。
    if (msg.getRemainNum() != 0 && blockIds.size() < NodeConstant.SYNC_FETCH_BATCH_NUM) {
      throw new P2pException(TypeEnum.BAD_MESSAGE,
          "remain: " + msg.getRemainNum() + ", blockIds size: " + blockIds.size());
    }
    //列表中的区块号码必须连续，不能中断跳跃
    long num = blockIds.get(0).getNum();
    for (BlockId id : msg.getBlockIds()) {
      if (id.getNum() != num++) {
        throw new P2pException(TypeEnum.BAD_MESSAGE, "not continuous block");
      }
    }
    //本机中必须包含列表的第0个区块id，保证区块头是一致的。
    //例如第一次请求返回的列表编号，从区块0~19； 0就是双方共同的创世区块。
    if (!peer.getSyncChainRequested().getKey().contains(blockIds.get(0))) {
      throw new P2pException(TypeEnum.BAD_MESSAGE, "unlinked block, my head: "
          + peer.getSyncChainRequested().getKey().getLast().getString()
          + ", peer: " + blockIds.get(0).getString());
    }
    //本机区块头不是创世区块，按照当前时间计算的剩余区块数量maxFutureNum应该是准确的，列表中计算得到的剩余数量不可能超过前者。
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
