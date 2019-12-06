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
import org.bhc.core.capsule.BytesCapsule;
import org.bhc.core.capsule.TransactionCapsule;
import org.bhc.core.db.KhaosDatabase.KhaosBlock;
import org.bhc.core.db2.common.TxCacheDB;
import org.bhc.core.exception.BadItemException;
import org.bhc.core.exception.StoreException;

@Slf4j
public class TransactionCache extends TronStoreWithRevoking<BytesCapsule> {

  @Autowired
  public TransactionCache(@Value("trans-cache") String dbName) {
    super(dbName, TxCacheDB.class);
  }
}
