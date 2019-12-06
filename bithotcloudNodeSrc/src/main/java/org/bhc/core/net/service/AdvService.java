package org.bhc.core.net.service;

import static org.bhc.core.config.Parameter.ChainConstant.BLOCK_PRODUCED_INTERVAL;
import static org.bhc.core.config.Parameter.NetConstants.MAX_TRX_FETCH_PER_PEER;
import static org.bhc.core.config.Parameter.NetConstants.MSG_CACHE_DURATION_IN_BLOCKS;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.bhc.common.overlay.discover.node.statistics.MessageCount;
import org.bhc.common.overlay.message.Message;
import org.bhc.common.utils.Sha256Hash;
import org.bhc.common.utils.Time;
import org.bhc.core.capsule.BlockCapsule.BlockId;
import org.bhc.core.config.args.Args;
import org.bhc.core.net.TronNetDelegate;
import org.bhc.core.net.message.BlockMessage;
import org.bhc.core.net.message.FetchInvDataMessage;
import org.bhc.core.net.message.InventoryMessage;
import org.bhc.core.net.message.TransactionMessage;
import org.bhc.core.net.peer.Item;
import org.bhc.core.net.peer.PeerConnection;
import org.bhc.protos.Protocol.Inventory.InventoryType;

@Slf4j
@Component
public class AdvService {

  @Autowired
  private TronNetDelegate tronNetDelegate;

  private ConcurrentHashMap<Item, Long> invToFetch = new ConcurrentHashMap<>();  //���յ���Ϣ

  private ConcurrentHashMap<Item, Long> invToSpread = new ConcurrentHashMap<>();  //�㲥�ĵ���Ϣ

  //������Ϣ���ٻ�����
  private Cache<Item, Long> invToFetchCache = CacheBuilder.newBuilder()
      .maximumSize(100_000).expireAfterWrite(1, TimeUnit.HOURS).recordStats().build();

  //���׸��ٻ������� ֻ�������1Сʱ�Ľ��ף��������5������
  private Cache<Item, Message> trxCache = CacheBuilder.newBuilder()
      .maximumSize(50_000).expireAfterWrite(1, TimeUnit.HOURS).recordStats().build();
  //������ٻ������� �����Լ����ɵĺͽ��յ������飬��Ч��1���ӣ����10����
  private Cache<Item, Message> blockCache = CacheBuilder.newBuilder()
      .maximumSize(10).expireAfterWrite(1, TimeUnit.MINUTES).recordStats().build();

  private ScheduledExecutorService spreadExecutor = Executors.newSingleThreadScheduledExecutor();

  private ScheduledExecutorService fetchExecutor = Executors.newSingleThreadScheduledExecutor();

  @Getter
  private MessageCount trxCount = new MessageCount();

  private boolean fastForward = Args.getInstance().isFastForward();

  public void init() {
    if (!fastForward) {
      spreadExecutor.scheduleWithFixedDelay(() -> {
        try {
          consumerInvToSpread();
        } catch (Throwable t) {
          logger.error("Spread thread error.", t);
        }
      }, 100, 10, TimeUnit.MILLISECONDS);
    }

    fetchExecutor.scheduleWithFixedDelay(() -> {
      try {
        consumerInvToFetch();
      } catch (Throwable t) {
        logger.error("Fetch thread error.", t);
      }
    }, 100, 10, TimeUnit.MILLISECONDS);
  }

  public void close() {
    spreadExecutor.shutdown();
    fetchExecutor.shutdown();
  }

  synchronized public boolean addInv(Item item) {
    if (invToFetchCache.getIfPresent(item) != null) {
      return false;
    }

    if (item.getType().equals(InventoryType.TRX)) {
      if (trxCache.getIfPresent(item) != null) {
        return false;
      }
    } else {
      if (blockCache.getIfPresent(item) != null) {
        return false;
      }
    }

    invToFetchCache.put(item, System.currentTimeMillis());
    invToFetch.put(item, System.currentTimeMillis());
    return true;
  }

  public Message getMessage(Item item) {
    if (item.getType().equals(InventoryType.TRX)) {
      return trxCache.getIfPresent(item);
    } else {
      return blockCache.getIfPresent(item);
    }
  }

  public void broadcast(Message msg) {
    Item item;
    if (msg instanceof BlockMessage) {
      BlockMessage blockMsg = (BlockMessage) msg;
      item = new Item(blockMsg.getMessageId(), InventoryType.BLOCK);
      logger.info("Ready to broadcast block {}", blockMsg.getBlockId().getString());
      
      blockMsg.getBlockCapsule().getTransactions().forEach(transactionCapsule -> {
        Sha256Hash tid = transactionCapsule.getTransactionId();
        invToSpread.remove(tid);   //���������ʽ�㲥�ˣ���Щ�����ڰ����Ľ��׾Ͳ����ڵ����㲥�ˡ�
        trxCache.put(new Item(tid, InventoryType.TRX),
            new TransactionMessage(transactionCapsule.getInstance()));
      });
      blockCache.put(item, msg);
      
    } else if (msg instanceof TransactionMessage) {
      TransactionMessage trxMsg = (TransactionMessage) msg;
      item = new Item(trxMsg.getMessageId(), InventoryType.TRX);
      trxCount.add();
      trxCache.put(item,
          new TransactionMessage(((TransactionMessage) msg).getTransactionCapsule().getInstance()));
    } else {
      logger.error("Adv item is neither block nor trx, type: {}", msg.getType());
      return;
    }
    synchronized (invToSpread) {
      invToSpread.put(item, System.currentTimeMillis());
    }

    if (fastForward) {
      consumerInvToSpread();
    }
  }

  public void onDisconnect(PeerConnection peer) {
    if (!peer.getAdvInvRequest().isEmpty()) {
      peer.getAdvInvRequest().keySet().forEach(item -> {
        if (tronNetDelegate.getActivePeer().stream()
            .anyMatch(p -> !p.equals(peer) && p.getAdvInvReceive().getIfPresent(item) != null)) {
          invToFetch.put(item, System.currentTimeMillis());
        } else {
          invToFetchCache.invalidate(item);
        }
      });
    }
  }

  private void consumerInvToFetch() {
    Collection<PeerConnection> peers = tronNetDelegate.getActivePeer().stream()
        .filter(peer -> peer.isIdle())
        .collect(Collectors.toList());

    if (invToFetch.isEmpty() || peers.isEmpty()) {
      return;
    }

    InvSender invSender = new InvSender();
    long now = System.currentTimeMillis();
    invToFetch.forEach((item, time) -> {
      if (time < now - MSG_CACHE_DURATION_IN_BLOCKS * BLOCK_PRODUCED_INTERVAL) {
        logger.info("This obj is too late to fetch, type: {} hash: {}.", item.getType(),
            item.getHash());
        invToFetch.remove(item);
        invToFetchCache.invalidate(item);
        return;
      }
      peers.stream()
          .filter(peer -> peer.getAdvInvReceive().getIfPresent(item) != null
              && invSender.getSize(peer) < MAX_TRX_FETCH_PER_PEER)
          .sorted(Comparator.comparingInt(peer -> invSender.getSize(peer)))
          .findFirst().ifPresent(peer -> {
        invSender.add(item, peer);
        peer.getAdvInvRequest().put(item, now);
        invToFetch.remove(item);
      });
    });

    invSender.sendFetch();
  }

  //�㲥��Ϣ������Ĵ������
  //�߳�spreadExecutor����ʱ10msִ��consumerInvToSpread
  private void consumerInvToSpread() {
    if (invToSpread.isEmpty()) {
      return;
    }

    /*��invToSpread�洢����Ϣת�浽spread�У��²�������Ĵ���Ứʱ�䣬�˴�ʹ��spread��������ѭ������
     * �Ϳ����ͷų�invToSpread�����ˣ����������ط��Ĳ�����
     * ps: �����ͬһ��invToSpread����ѭ�������޷�������Ϣ��ӵ�invToSpread�С�*/
    InvSender invSender = new InvSender();
    HashMap<Item, Long> spread = new HashMap<>();
    synchronized (invToSpread) {
      spread.putAll(invToSpread);
      invToSpread.clear();    //ע��˴������invToSpread��
    }
    
    /*ɸѡ������ ��spread�е�ÿһ����Ϣ+ÿһ������ڵ�  ˫��ѭ��
     *  û�յ������ڵ㷢���������Ϣ
     *  û�������ڵ㷢�͹������Ϣ
     *ִ�ж����� ����ϢID��ӵ��ýڵ�Ĺ㲥�б��У� ��ӵ� invSender */
    tronNetDelegate.getActivePeer().stream()
        .filter(peer -> !peer.isNeedSyncFromPeer() && !peer.isNeedSyncFromUs())
        .forEach(peer -> spread.entrySet().stream()
            .filter(entry -> peer.getAdvInvReceive().getIfPresent(entry.getKey()) == null
                && peer.getAdvInvSpread().getIfPresent(entry.getKey()) == null)
            .forEach(entry -> {
              peer.getAdvInvSpread().put(entry.getKey(), Time.getCurrentMillis());
              invSender.add(entry.getKey(), peer);
            }));

    invSender.sendInv();
  }

  class InvSender {

    private HashMap<PeerConnection, HashMap<InventoryType, LinkedList<Sha256Hash>>> send = new HashMap<>();

    public void clear() {
      this.send.clear();
    }

    public void add(Entry<Sha256Hash, InventoryType> id, PeerConnection peer) {
      if (send.containsKey(peer) && !send.get(peer).containsKey(id.getValue())) {
        send.get(peer).put(id.getValue(), new LinkedList<>());
      } else if (!send.containsKey(peer)) {
        send.put(peer, new HashMap<>());
        send.get(peer).put(id.getValue(), new LinkedList<>());
      }
      send.get(peer).get(id.getValue()).offer(id.getKey());
    }

    public void add(Item id, PeerConnection peer) {
      if (send.containsKey(peer) && !send.get(peer).containsKey(id.getType())) {
        send.get(peer).put(id.getType(), new LinkedList<>());
      } else if (!send.containsKey(peer)) {
        send.put(peer, new HashMap<>());
        send.get(peer).put(id.getType(), new LinkedList<>());
      }
      send.get(peer).get(id.getType()).offer(id.getHash());
    }

    public int getSize(PeerConnection peer) {
      if (send.containsKey(peer)) {
        return send.get(peer).values().stream().mapToInt(LinkedList::size).sum();
      }
      return 0;
    }

    //����send�����е�������Ϣ
    /*ids���ͣ�  HashMap< InventoryType, LinkedList<Sha256Hash> > 
     *                    key             value
     **/
    public void sendInv() {
      send.forEach((peer, ids) -> ids.forEach((key, value) -> {
        if (key.equals(InventoryType.TRX) && peer.isFastForwardPeer()) {
          return;
        }
        //��������Ϣ���а��ձ������С�ŵ��ȷ�
        if (key.equals(InventoryType.BLOCK)) {
          value.sort(Comparator.comparingLong(value1 -> new BlockId(value1).getNum()));
        }
        peer.sendMessage(new InventoryMessage(value, key));
      }));
    }

    void sendFetch() {
      send.forEach((peer, ids) -> ids.forEach((key, value) -> {
        if (key.equals(InventoryType.BLOCK)) {
          value.sort(Comparator.comparingLong(value1 -> new BlockId(value1).getNum()));
        }
        peer.sendMessage(new FetchInvDataMessage(value, key));
      }));
    }
  }

}
