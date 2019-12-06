package org.bhc.core.net.service;

import static org.bhc.core.config.Parameter.NetConstants.MAX_BLOCK_FETCH_PER_PEER;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javafx.util.Pair;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.bhc.common.overlay.server.Channel.TronState;
import org.bhc.core.capsule.BlockCapsule;
import org.bhc.core.capsule.BlockCapsule.BlockId;
import org.bhc.core.config.Parameter.NodeConstant;
import org.bhc.core.exception.P2pException;
import org.bhc.core.exception.P2pException.TypeEnum;
import org.bhc.core.net.TronNetDelegate;
import org.bhc.core.net.message.BlockMessage;
import org.bhc.core.net.message.FetchInvDataMessage;
import org.bhc.core.net.message.SyncBlockChainMessage;
import org.bhc.core.net.peer.PeerConnection;
import org.bhc.protos.Protocol.Inventory.InventoryType;
import org.bhc.protos.Protocol.ReasonCode;

@Slf4j
@Component
public class SyncService {

  @Autowired
  private TronNetDelegate tronNetDelegate;

  //待处理队列, 将用于数据库保存操作。数据来源自 接收的同步区块blockJustReceived
  private Map<BlockMessage, PeerConnection> blockWaitToProcess = new ConcurrentHashMap<>();

  //接收同步区块队列， 每次收到Block就保存到这里面。每次重新开始同步流程时就清空该队列，数据转移到待处理队列。
  private Map<BlockMessage, PeerConnection> blockJustReceived = new ConcurrentHashMap<>();

  //高速缓冲区， 保存待接收的同步区块id
  private Cache<BlockId, Long> requestBlockIds = CacheBuilder.newBuilder().maximumSize(10_000)
      .expireAfterWrite(1, TimeUnit.HOURS).initialCapacity(10_000)
      .recordStats().build();

  private ScheduledExecutorService fetchExecutor = Executors.newSingleThreadScheduledExecutor();

  private ScheduledExecutorService blockHandleExecutor = Executors
      .newSingleThreadScheduledExecutor();

  private volatile boolean handleFlag = false;  //这个标记很重要， 一旦打开就启动BLOCK_CHAIN_INVENTORY命令。

  @Setter
  private volatile boolean fetchFlag = false;   //这个标记很重要， 一旦打开就启动接收区块。

  public void init() {
	  
	  //fetchExecutor是专门发出FETCH_INV_DATA消息的线程，每次fetchFlag打开后就启动一次。
    fetchExecutor.scheduleWithFixedDelay(() -> {
      try {
        if (fetchFlag) {
        	System.out.println("---- fetchExecutor:  startFetchSyncBlock()....");
          fetchFlag = false;   //又立即关闭标记，说明只运行一次。 
          startFetchSyncBlock();
        }
      } catch (Throwable t) {
        logger.error("Fetch sync block error.", t);
      }
    }, 10, 1, TimeUnit.SECONDS);

    //blockHandleExecutor 会发出BLOCK_CHAIN_INVENTORY消息。当handleFlag打开时启动一次。
    blockHandleExecutor.scheduleWithFixedDelay(() -> {
      try {
        if (handleFlag) {
          handleFlag = false;
          handleSyncBlock();
        }
      } catch (Throwable t) {
        logger.error("Handle sync block error.", t);
      }
    }, 10, 1, TimeUnit.SECONDS);
  }

  public void close() {
    fetchExecutor.shutdown();
    blockHandleExecutor.shutdown();
  }

  //启动同步命令, 当发现收到孤立的区块时也要立即进行同步过程。
  public void startSync(PeerConnection peer) {
    peer.setTronState(TronState.SYNCING);
    peer.setNeedSyncFromPeer(true);        //同步标记
    peer.getSyncBlockToFetch().clear();    //清空接收同步区块的队列
    peer.setRemainNum(0); 
    peer.setBlockBothHave(tronNetDelegate.getGenesisBlockId());
    System.out.println("------ syncNext(peer) ......");
    syncNext(peer);
  }

  public void syncNext(PeerConnection peer) {
	  //
	  System.out.println("---  syncNext() ....  ");
    try {
      if (peer.getSyncChainRequested() != null) {
        logger.warn("Peer {} is in sync.", peer.getNode().getHost());
        return;
      }
      LinkedList<BlockId> chainSummary = getBlockChainSummary(peer);
      System.out.println("------ chainSummary="+chainSummary);
      peer.setSyncChainRequested(new Pair<>(chainSummary, System.currentTimeMillis()));
      System.out.println("------ peer.sendMessage ......");
      peer.sendMessage(new SyncBlockChainMessage(chainSummary));
    } catch (Exception e) {
      logger.error("Peer {} sync failed, reason: {}", peer.getInetAddress(), e.getMessage());
      peer.disconnect(ReasonCode.SYNC_FAIL);
    }
  }

	// 处理接收到的每一个区块id信息， 保存在blockJustReceived.
	public void processBlock(PeerConnection peer, BlockMessage blockMessage) {
		synchronized (blockJustReceived) {
			blockJustReceived.put(blockMessage, peer);
		}
		
		//处理区块时打开handleFlag， 会启动一次blockHandleExecutor定时器，就可能发出BLOCK_CHAIN_INVENTORY消息
		handleFlag = true;
		
		// 同步过程中希望接收的区块写入syncBlockRequested，以后接收到区块就从中移除。当为空时连接状态才能是idle，刚好运行下面的if语句
		if (peer.isIdle()) {
            System.out.println("---- peer.getRemainNum()="+peer.getRemainNum());
            System.out.println("---- peer.getSyncBlockToFetch().size()="+peer.getSyncBlockToFetch().size());
			// 还有剩余区块
			if (peer.getRemainNum() > 0 && peer.getSyncBlockToFetch().size() <= NodeConstant.SYNC_FETCH_BATCH_NUM) {
				System.out.println("---- 000  syncNext(peer).... ");
				syncNext(peer);     //实际代码会运行到这里， 这句话是个真的复杂流程。
			} else {
				System.out.println("---- 111  fetchFlag = true... ");
				// 接收完了100个Block， 打开接收标记，让SyncService的定时器开始再次干活，这个服务才能发出FETCH_INV_DATA消息
				fetchFlag = true;
			}
		}
	}

  public void onDisconnect(PeerConnection peer) {
    if (!peer.getSyncBlockRequested().isEmpty()) {
      peer.getSyncBlockRequested().keySet().forEach(blockId -> invalid(blockId));
    }
  }

  private void invalid(BlockId blockId) {
    requestBlockIds.invalidate(blockId);
    fetchFlag = true;
  }

  /* 返回本机区块hash值的列表，注意这个列表是按照类似2分法提取的部分区块hash值。以19个区块的同步为例，
   * 第一次的本机区块只有创世区块，列表中只有创世区块hash
   * 第二次本机已经同步到了19个区块，列表中的区块编号是 0/10/15/18/19.
   * */
  private LinkedList<BlockId> getBlockChainSummary(PeerConnection peer) throws Exception {

    BlockId beginBlockId = peer.getBlockBothHave();
    
    //每次执行时blockIds元素为0， 估计是每次的同步命令会清空。
    List<BlockId> blockIds = new ArrayList<>(peer.getSyncBlockToFetch());
    
    LinkedList<BlockId> forkList = new LinkedList<>();
    LinkedList<BlockId> summary = new LinkedList<>();
    long syncBeginNumber = tronNetDelegate.getSyncBeginNumber();
    long low = syncBeginNumber < 0 ? 0 : syncBeginNumber;
    long highNoFork;
    long high;

    if (beginBlockId.getNum() == 0) {
      highNoFork = high = tronNetDelegate.getHeadBlockId().getNum();
    } else {
      if (tronNetDelegate.containBlockInMainChain(beginBlockId)) {
        highNoFork = high = beginBlockId.getNum();
      } else {
        forkList = tronNetDelegate.getBlockChainHashesOnFork(beginBlockId);
        if (forkList.isEmpty()) {
          throw new P2pException(TypeEnum.SYNC_FAILED,
              "can't find blockId: " + beginBlockId.getString());
        }
        highNoFork = forkList.peekLast().getNum();
        forkList.pollLast();
        Collections.reverse(forkList);
        high = highNoFork + forkList.size();
      }
    }

    if (low > highNoFork) {
      throw new P2pException(TypeEnum.SYNC_FAILED, "low: " + low + " gt highNoFork: " + highNoFork);
    }

    System.out.println("----- blockIds.size()="+blockIds.size());
    long realHigh = high + blockIds.size();

    logger.info("Get block chain summary, low: {}, highNoFork: {}, high: {}, realHigh: {}",
        low, highNoFork, high, realHigh);

    while (low <= realHigh) {
      if (low <= highNoFork) {  //一般执行这里，无分叉情况，
        summary.offer(tronNetDelegate.getBlockIdByNum(low));
      } else if (low <= high) {
        summary.offer(forkList.get((int) (low - highNoFork - 1)));
      } else {
        summary.offer(blockIds.get((int) (low - high - 1)));
      }
      low += (realHigh - low + 2) / 2;
    }

    return summary;
  }

  private void startFetchSyncBlock() {
    HashMap<PeerConnection, List<BlockId>> send = new HashMap<>();

    //向所有的激活节点发送FETCH_INV_DATA， 这一招比较狠。
    tronNetDelegate.getActivePeer().stream()
        .filter(peer -> peer.isNeedSyncFromPeer() && peer.isIdle())  //连接是同步状态+空闲状态
        .forEach(peer -> {
          if (!send.containsKey(peer)) {
            send.put(peer, new LinkedList<>());
          }
          
          //这时已经把区块id填写到了getSyncBlockToFetch()中， 继续写入到send中，此处只写入100个。
          //同时等待收到100个区块，因此在getSyncBlockRequested()写入这些区块id，以后接收到区块就从中移除。
          for (BlockId blockId : peer.getSyncBlockToFetch()) {
        	  //这个requestBlockIds是个高速缓冲区， 凡是在其中的区块id表示曾经请求过，不再次添加到send请求中
            if (requestBlockIds.getIfPresent(blockId) == null) {
              requestBlockIds.put(blockId, System.currentTimeMillis());
              
              peer.getSyncBlockRequested().put(blockId, System.currentTimeMillis());
              
              send.get(peer).add(blockId);
              if (send.get(peer).size() >= MAX_BLOCK_FETCH_PER_PEER) {
                break;
              }
            }
          }
        });
     System.out.println("---- send.size="+send.size());
    //终于发出了FETCH_INV_DATA消息，里面包含希望获取的100个区块id
    send.forEach((peer, blockIds) -> {
      if (!blockIds.isEmpty()) {
        peer.sendMessage(new FetchInvDataMessage(new LinkedList<>(blockIds), InventoryType.BLOCK));
      }
    });
  }

  //再次开始同步， 清空了之前接收区块的blockJustReceived。
  //依据处理流程区块数据会发生转移： syncBlockToFetch --> blockWaitToProcess --> syncBlockInProcess
  private synchronized void handleSyncBlock() {

    synchronized (blockJustReceived) {
      blockWaitToProcess.putAll(blockJustReceived);  //区块保存到待处理队列blockWaitToProcess
      blockJustReceived.clear();
    }

    final boolean[] isProcessed = {true};

    while (isProcessed[0]) {

      isProcessed[0] = false;

      blockWaitToProcess.forEach((msg, peerConnection) -> {
    	
    	//如果连接中断就删除消息,
        if (peerConnection.isDisconnect()) {
          blockWaitToProcess.remove(msg);
          invalid(msg.getBlockId());   //从高速缓冲区中删除区块请求
          return;
        }
        final boolean[] isFound = {false};
        tronNetDelegate.getActivePeer().stream()
            .filter(peer -> msg.getBlockId().equals(peer.getSyncBlockToFetch().peek()))
            .forEach(peer -> {
              peer.getSyncBlockToFetch().pop();  //数据转移到SyncBlockInProcess了，清除SyncBlockToFetch，
              peer.getSyncBlockInProcess().add(msg.getBlockId());
              isFound[0] = true;
            });
        if (isFound[0]) {
          blockWaitToProcess.remove(msg);  //数据转移到SyncBlockInProcess了，清除blockWaitToProcess， 
          isProcessed[0] = true;
          processSyncBlock(msg.getBlockCapsule()); //执行真正的处理
        }
      });
    }
  }

//保存到数据库中. 同时检查SyncBlockToFetch是否为空？ 为空表示这一批次的2000个区块全部接收并保存处理完毕，可以请求下一批次。
  private void processSyncBlock(BlockCapsule block) {
    boolean flag = true;
    BlockId blockId = block.getBlockId();
    try {
      tronNetDelegate.processBlock(block);  //保存到数据库中
    } catch (Exception e) {
      logger.error("Process sync block {} failed.", blockId.getString(), e);
      flag = false;
    }
    for (PeerConnection peer : tronNetDelegate.getActivePeer()) {
      if (peer.getSyncBlockInProcess().remove(blockId)) {
        if (flag) {
          peer.setBlockBothHave(blockId);
          if (peer.getSyncBlockToFetch().isEmpty()) {
        	  System.out.println("----- SyncService.processSyncBlock: 0000    ");
              syncNext(peer);
          }
        } else {
          peer.disconnect(ReasonCode.BAD_BLOCK);
        }
      }
    }
  }

}
