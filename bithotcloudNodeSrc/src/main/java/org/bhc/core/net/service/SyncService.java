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

  //���������, ���������ݿⱣ�������������Դ�� ���յ�ͬ������blockJustReceived
  private Map<BlockMessage, PeerConnection> blockWaitToProcess = new ConcurrentHashMap<>();

  //����ͬ��������У� ÿ���յ�Block�ͱ��浽�����档ÿ�����¿�ʼͬ������ʱ����ոö��У�����ת�Ƶ���������С�
  private Map<BlockMessage, PeerConnection> blockJustReceived = new ConcurrentHashMap<>();

  //���ٻ������� ��������յ�ͬ������id
  private Cache<BlockId, Long> requestBlockIds = CacheBuilder.newBuilder().maximumSize(10_000)
      .expireAfterWrite(1, TimeUnit.HOURS).initialCapacity(10_000)
      .recordStats().build();

  private ScheduledExecutorService fetchExecutor = Executors.newSingleThreadScheduledExecutor();

  private ScheduledExecutorService blockHandleExecutor = Executors
      .newSingleThreadScheduledExecutor();

  private volatile boolean handleFlag = false;  //�����Ǻ���Ҫ�� һ���򿪾�����BLOCK_CHAIN_INVENTORY���

  @Setter
  private volatile boolean fetchFlag = false;   //�����Ǻ���Ҫ�� һ���򿪾������������顣

  public void init() {
	  
	  //fetchExecutor��ר�ŷ���FETCH_INV_DATA��Ϣ���̣߳�ÿ��fetchFlag�򿪺������һ�Ρ�
    fetchExecutor.scheduleWithFixedDelay(() -> {
      try {
        if (fetchFlag) {
        	System.out.println("---- fetchExecutor:  startFetchSyncBlock()....");
          fetchFlag = false;   //�������رձ�ǣ�˵��ֻ����һ�Ρ� 
          startFetchSyncBlock();
        }
      } catch (Throwable t) {
        logger.error("Fetch sync block error.", t);
      }
    }, 10, 1, TimeUnit.SECONDS);

    //blockHandleExecutor �ᷢ��BLOCK_CHAIN_INVENTORY��Ϣ����handleFlag��ʱ����һ�Ρ�
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

  //����ͬ������, �������յ�����������ʱҲҪ��������ͬ�����̡�
  public void startSync(PeerConnection peer) {
    peer.setTronState(TronState.SYNCING);
    peer.setNeedSyncFromPeer(true);        //ͬ�����
    peer.getSyncBlockToFetch().clear();    //��ս���ͬ������Ķ���
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

	// ������յ���ÿһ������id��Ϣ�� ������blockJustReceived.
	public void processBlock(PeerConnection peer, BlockMessage blockMessage) {
		synchronized (blockJustReceived) {
			blockJustReceived.put(blockMessage, peer);
		}
		
		//��������ʱ��handleFlag�� ������һ��blockHandleExecutor��ʱ�����Ϳ��ܷ���BLOCK_CHAIN_INVENTORY��Ϣ
		handleFlag = true;
		
		// ͬ��������ϣ�����յ�����д��syncBlockRequested���Ժ���յ�����ʹ����Ƴ�����Ϊ��ʱ����״̬������idle���պ����������if���
		if (peer.isIdle()) {
            System.out.println("---- peer.getRemainNum()="+peer.getRemainNum());
            System.out.println("---- peer.getSyncBlockToFetch().size()="+peer.getSyncBlockToFetch().size());
			// ����ʣ������
			if (peer.getRemainNum() > 0 && peer.getSyncBlockToFetch().size() <= NodeConstant.SYNC_FETCH_BATCH_NUM) {
				System.out.println("---- 000  syncNext(peer).... ");
				syncNext(peer);     //ʵ�ʴ�������е���� ��仰�Ǹ���ĸ������̡�
			} else {
				System.out.println("---- 111  fetchFlag = true... ");
				// ��������100��Block�� �򿪽��ձ�ǣ���SyncService�Ķ�ʱ����ʼ�ٴθɻ���������ܷ���FETCH_INV_DATA��Ϣ
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

  /* ���ر�������hashֵ���б�ע������б��ǰ�������2�ַ���ȡ�Ĳ�������hashֵ����19�������ͬ��Ϊ����
   * ��һ�εı�������ֻ�д������飬�б���ֻ�д�������hash
   * �ڶ��α����Ѿ�ͬ������19�����飬�б��е��������� 0/10/15/18/19.
   * */
  private LinkedList<BlockId> getBlockChainSummary(PeerConnection peer) throws Exception {

    BlockId beginBlockId = peer.getBlockBothHave();
    
    //ÿ��ִ��ʱblockIdsԪ��Ϊ0�� ������ÿ�ε�ͬ���������ա�
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
      if (low <= highNoFork) {  //һ��ִ������޷ֲ������
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

    //�����еļ���ڵ㷢��FETCH_INV_DATA�� ��һ�бȽϺݡ�
    tronNetDelegate.getActivePeer().stream()
        .filter(peer -> peer.isNeedSyncFromPeer() && peer.isIdle())  //������ͬ��״̬+����״̬
        .forEach(peer -> {
          if (!send.containsKey(peer)) {
            send.put(peer, new LinkedList<>());
          }
          
          //��ʱ�Ѿ�������id��д����getSyncBlockToFetch()�У� ����д�뵽send�У��˴�ֻд��100����
          //ͬʱ�ȴ��յ�100�����飬�����getSyncBlockRequested()д����Щ����id���Ժ���յ�����ʹ����Ƴ���
          for (BlockId blockId : peer.getSyncBlockToFetch()) {
        	  //���requestBlockIds�Ǹ����ٻ������� ���������е�����id��ʾ��������������ٴ���ӵ�send������
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
    //���ڷ�����FETCH_INV_DATA��Ϣ���������ϣ����ȡ��100������id
    send.forEach((peer, blockIds) -> {
      if (!blockIds.isEmpty()) {
        peer.sendMessage(new FetchInvDataMessage(new LinkedList<>(blockIds), InventoryType.BLOCK));
      }
    });
  }

  //�ٴο�ʼͬ���� �����֮ǰ���������blockJustReceived��
  //���ݴ��������������ݻᷢ��ת�ƣ� syncBlockToFetch --> blockWaitToProcess --> syncBlockInProcess
  private synchronized void handleSyncBlock() {

    synchronized (blockJustReceived) {
      blockWaitToProcess.putAll(blockJustReceived);  //���鱣�浽���������blockWaitToProcess
      blockJustReceived.clear();
    }

    final boolean[] isProcessed = {true};

    while (isProcessed[0]) {

      isProcessed[0] = false;

      blockWaitToProcess.forEach((msg, peerConnection) -> {
    	
    	//��������жϾ�ɾ����Ϣ,
        if (peerConnection.isDisconnect()) {
          blockWaitToProcess.remove(msg);
          invalid(msg.getBlockId());   //�Ӹ��ٻ�������ɾ����������
          return;
        }
        final boolean[] isFound = {false};
        tronNetDelegate.getActivePeer().stream()
            .filter(peer -> msg.getBlockId().equals(peer.getSyncBlockToFetch().peek()))
            .forEach(peer -> {
              peer.getSyncBlockToFetch().pop();  //����ת�Ƶ�SyncBlockInProcess�ˣ����SyncBlockToFetch��
              peer.getSyncBlockInProcess().add(msg.getBlockId());
              isFound[0] = true;
            });
        if (isFound[0]) {
          blockWaitToProcess.remove(msg);  //����ת�Ƶ�SyncBlockInProcess�ˣ����blockWaitToProcess�� 
          isProcessed[0] = true;
          processSyncBlock(msg.getBlockCapsule()); //ִ�������Ĵ���
        }
      });
    }
  }

//���浽���ݿ���. ͬʱ���SyncBlockToFetch�Ƿ�Ϊ�գ� Ϊ�ձ�ʾ��һ���ε�2000������ȫ�����ղ����洦����ϣ�����������һ���Ρ�
  private void processSyncBlock(BlockCapsule block) {
    boolean flag = true;
    BlockId blockId = block.getBlockId();
    try {
      tronNetDelegate.processBlock(block);  //���浽���ݿ���
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
