package org.bhc.core.net.peer;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.TimeUnit;
import javafx.util.Pair;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import org.bhc.common.overlay.message.HelloMessage;
import org.bhc.common.overlay.message.Message;
import org.bhc.common.overlay.server.Channel;
import org.bhc.common.utils.Sha256Hash;
import org.bhc.core.capsule.BlockCapsule.BlockId;
import org.bhc.core.config.Parameter.NodeConstant;
import org.bhc.core.net.TronNetDelegate;
import org.bhc.core.net.service.AdvService;
import org.bhc.core.net.service.SyncService;

@Slf4j(topic = "net")
@Component
@Scope("prototype")
public class PeerConnection extends Channel {

  @Autowired
  private TronNetDelegate tronNetDelegate;

  @Autowired
  private SyncService syncService;

  @Autowired
  private AdvService advService;

  private int invCacheSize = 100_000;

  @Setter
  @Getter
  private BlockId signUpErrorBlockId;

  @Setter
  @Getter
  private HelloMessage helloMessage;

  @Setter
  @Getter  //连接上收到的消息（区块、交易）缓冲区， 有效期1小时。
  private Cache<Item, Long> advInvReceive = CacheBuilder.newBuilder().maximumSize(invCacheSize)
      .expireAfterWrite(1, TimeUnit.HOURS).recordStats().build();

  @Setter
  @Getter  //连接上广播出去的消息（区块、交易）缓冲区， 有效期1小时。
  private Cache<Item, Long> advInvSpread = CacheBuilder.newBuilder().maximumSize(invCacheSize)
      .expireAfterWrite(1, TimeUnit.HOURS).recordStats().build();

  /* 等待接收的消息队列, 与广播交易消息有关系consumerInvToFetch(),  
   * 非空表示正在等待着某些消息应答，此时不能进行区块同步工作，状态不是idle*/
  @Setter
  @Getter  
  private Map<Item, Long> advInvRequest = new ConcurrentHashMap<>();  

  @Getter
  private BlockId blockBothHave = new BlockId();  //同步双方共同的区块

  //每次通过p2p收到Block时，就重置对象
  public void setBlockBothHave(BlockId blockId) {
    this.blockBothHave = blockId;
    this.blockBothHaveUpdateTime = System.currentTimeMillis();
  }

  @Getter
  private long blockBothHaveUpdateTime = System.currentTimeMillis();

  @Setter
  @Getter
  private BlockId lastSyncBlockId;   //随时记录下最新的同步区块id

  @Setter   
  @Getter
  private long remainNum;  //剩余的同步区块数量

  //专门保存该连接上已经同步的区块信息，容量4000条
  @Getter
  private Cache<Sha256Hash, Long> syncBlockIdCache = CacheBuilder.newBuilder()
      .maximumSize(2 * NodeConstant.SYNC_FETCH_BATCH_NUM).recordStats().build();

  /*  双向链表结构的无界并发队列, 就是双端都可插入删除的队列.
   *  保存同步过程中接收的 BLOCK_CHAIN_INVENTORY消息携带的服务器区块id，一个消息最多携带了2000个区块id。
   *  以后可以分批多次请求这些区块，每次100条。 
   * */     
  @Setter
  @Getter  
  private Deque<BlockId> syncBlockToFetch = new ConcurrentLinkedDeque<>();

  @Setter
  @Getter  //同步过程中希望接收的区块写入syncBlockRequested，以后接收到区块就从中移除。当为空时连接状态才能是idle
  private Map<BlockId, Long> syncBlockRequested = new ConcurrentHashMap<>();

  /* 专门表示SYNC_BLOCK_CHAIN消息收到应答, 保存的是SYNC_BLOCK_CHAIN消息中的chainSummary列表
   * 调用者有两处：
        SyncService.syncNext()   每次请求下一批2000个时设置，
              peer.setSyncChainRequested(new Pair<>(chainSummary, System.currentTimeMillis()))
        ChainInventoryMsgHandler.processMessage()
              peer.setSyncChainRequested(null)  每次收到应答时清空
  */
  @Setter
  @Getter
  private Pair<Deque<BlockId>, Long> syncChainRequested = null;  //有用到  setSyncChainRequested

  /* 依据处理流程，接收到的区块数据会发生转移：  
   * syncBlockToFetch --> blockWaitToProcess --> syncBlockInProcess
   * */
  @Setter
  @Getter
  private Set<BlockId> syncBlockInProcess = new HashSet<>();

  @Setter
  @Getter
  private boolean needSyncFromPeer;  
  //同步标记，用于SyncService服务检查是否处于同步状态.
  //在 chainInventoryMsgHandler.processMessage函数中设置为true。

  @Setter
  @Getter
  private boolean needSyncFromUs;

  //连接状态：空闲，  只有空闲状态才能接收区块同步。
  public boolean isIdle() {
	/* advInvRequest、syncChainRequested一直没有被使用，都是空。有效的条件只是syncBlockRequested
	 * syncBlockRequested里面保存的是请求的100个区块id，如果接收到了并且保存下来了，就会从中删除，那么就变为空。
	 * 这时状态就是空闲了。 从逻辑上讲， 1秒钟间隔，每次请求并接收100个区块，保存100个区块  */  
    return advInvRequest.isEmpty() && syncBlockRequested.isEmpty() && syncChainRequested == null;
  }

  public void sendMessage(Message message) {
    msgQueue.sendMessage(message);
  }

  //握手成功后，启动同步流程的入口
  public void onConnect() {
	  System.out.println("------ getHelloMessage().getHeadBlockId().getNum()="+getHelloMessage().getHeadBlockId().getNum());
	  System.out.println("------ tronNetDelegate.getHeadBlockId().getNum()="  +tronNetDelegate.getHeadBlockId().getNum());
    if (getHelloMessage().getHeadBlockId().getNum() > tronNetDelegate.getHeadBlockId().getNum()) {
      setTronState(TronState.SYNCING);
      System.out.println("------ syncService.startSync(this) ......");
      syncService.startSync(this);
    } else {
      setTronState(TronState.SYNC_COMPLETED);
    }
  }

  public void onDisconnect() {
    syncService.onDisconnect(this);
    advService.onDisconnect(this);
    advInvReceive.cleanUp();
    advInvSpread.cleanUp();
    advInvRequest.clear();
    syncBlockIdCache.cleanUp();
    syncBlockToFetch.clear();
    syncBlockRequested.clear();
    syncBlockInProcess.clear();
    syncBlockInProcess.clear();
  }

  public String log() {
    long now = System.currentTimeMillis();
//    logger.info("Peer {}:{} [ {}, ping {} ms]-----------\n"
//            + "connect time: {}\n"
//            + "last know block num: {}\n"
//            + "needSyncFromPeer:{}\n"
//            + "needSyncFromUs:{}\n"
//            + "syncToFetchSize:{}\n"
//            + "syncToFetchSizePeekNum:{}\n"
//            + "syncBlockRequestedSize:{}\n"
//            + "remainNum:{}\n"
//            + "syncChainRequested:{}\n"
//            + "blockInProcess:{}\n"
//            + "{}",
//        this.getNode().getHost(), this.getNode().getPort(), this.getNode().getHexIdShort(),
//        (int) this.getPeerStats().getAvgLatency(),
//        (now - super.getStartTime()) / 1000,
//        blockBothHave.getNum(),
//        isNeedSyncFromPeer(),
//        isNeedSyncFromUs(),
//        syncBlockToFetch.size(),
//        syncBlockToFetch.size() > 0 ? syncBlockToFetch.peek().getNum() : -1,
//        syncBlockRequested.size(),
//        remainNum,
//        syncChainRequested == null ? 0 : (now - syncChainRequested.getValue()) / 1000,
//        syncBlockInProcess.size(),
//        nodeStatistics.toString());
////
    return String.format(
        "Peer %s: [ %18s, ping %6s ms]-----------\n"
            + "connect time: %ds\n"
            + "last know block num: %s\n"
            + "needSyncFromPeer:%b\n"
            + "needSyncFromUs:%b\n"
            + "syncToFetchSize:%d\n"
            + "syncToFetchSizePeekNum:%d\n"
            + "syncBlockRequestedSize:%d\n"
            + "remainNum:%d\n"
            + "syncChainRequested:%d\n"
            + "blockInProcess:%d\n",
        this.getNode().getHost() + ":" + this.getNode().getPort(),
        this.getNode().getHexIdShort(),
        (int) this.getPeerStats().getAvgLatency(),
        (now - super.getStartTime()) / 1000,
        blockBothHave.getNum(),
        isNeedSyncFromPeer(),
        isNeedSyncFromUs(),
        syncBlockToFetch.size(),
        syncBlockToFetch.size() > 0 ? syncBlockToFetch.peek().getNum() : -1,
        syncBlockRequested.size(),
        remainNum,
        syncChainRequested == null ? 0 : (now - syncChainRequested.getValue()) / 1000,
        syncBlockInProcess.size())
        + nodeStatistics.toString() + "\n";
  }

}
