package org.bhc.core.db;

import java.util.List;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ArrayUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.bhc.common.utils.ByteArray;
import org.bhc.common.utils.Sha256Hash;
import org.bhc.core.capsule.BlockCapsule;
import org.bhc.core.capsule.TransactionCapsule;
import org.bhc.core.db.KhaosDatabase.KhaosBlock;
import org.bhc.core.exception.BadItemException;
import org.bhc.core.exception.StoreException;

@Slf4j(topic = "DB")
@Component
public class TransactionStore extends TronStoreWithRevoking<TransactionCapsule> {

  @Autowired
  private BlockStore blockStore;

  @Autowired
  private KhaosDatabase khaosDatabase;

  @Autowired
  private TransactionStore(@Value("trans") String dbName) {
    super(dbName);
  }

  @Override
  public void put(byte[] key, TransactionCapsule item) {
    if (Objects.isNull(item) || item.getBlockNum() == -1) {
      super.put(key, item);
    } else {
      revokingDB.put(key, ByteArray.fromLong(item.getBlockNum()));
    }

    if (Objects.nonNull(indexHelper)) {
      indexHelper.update(item.getInstance());
    }
  }

  //指定区块编号、交易key值，去读对应的交易对象
  private TransactionCapsule getTransactionFromBlockStore(byte[] key, long blockNum) {
    List<BlockCapsule> blocksList = blockStore.getLimitNumber(blockNum, 1);
    if (blocksList.size() != 0) {
      for (TransactionCapsule e : blocksList.get(0).getTransactions()) {
        if (e.getTransactionId().equals(Sha256Hash.wrap(key))) {
          return e;
        }
      }
    }
    return null;
  }

  private TransactionCapsule getTransactionFromKhaosDatabase(byte[] key, long high) {
    List<KhaosBlock> khaosBlocks = khaosDatabase.getMiniStore().getBlockByNum(high);
    for (KhaosBlock bl : khaosBlocks) {
      for (TransactionCapsule e : bl.blk.getTransactions()) {
        if (e.getTransactionId().equals(Sha256Hash.wrap(key))) {
          return e;
        }
      }
    }
    return null;
  }

  @Override
  public TransactionCapsule get(byte[] key) throws BadItemException {
    byte[] value = revokingDB.getUnchecked(key);
    if (ArrayUtils.isEmpty(value)) {
      return null;
    }
    TransactionCapsule transactionCapsule = null;
    if (value.length == 8) {
      long blockHigh = ByteArray.toLong(value);
      transactionCapsule = getTransactionFromBlockStore(key, blockHigh);
      if (transactionCapsule == null) {
        transactionCapsule = getTransactionFromKhaosDatabase(key, blockHigh);
      }
    }

    return transactionCapsule == null ? new TransactionCapsule(value) : transactionCapsule;
  }

  @Override
  public TransactionCapsule getUnchecked(byte[] key) {
    try {
      return get(key);
    } catch (Exception e) {
      return null;
    }
  }

  /**
   * get total transaction.
   */
  @Deprecated
  public long getTotalTransactions() {
    return 0; //Streams.stream(iterator()).count();
  }

  @Override
  public void delete(byte[] key) {
    deleteIndex(key);
    super.delete(key);
  }

  private void deleteIndex(byte[] key) {
    if (Objects.nonNull(indexHelper)) {
      TransactionCapsule item;
      try {
        item = get(key);
        if (Objects.nonNull(item)) {
          indexHelper.remove(item.getInstance());
        }
      } catch (StoreException e) {
        logger.error("deleteIndex: ", e);
      }
    }
  }
}
