package org.bhc.core.db;

import com.google.common.collect.Streams;
import java.util.List;
import java.util.Map.Entry;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ArrayUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.bhc.core.capsule.WitnessCapsule;


/* WitnessCapsule类是 Witness类的封装，可以方便的读写成员变量
 * 继续多一层封装，WitnessStore是容器，存储多个WitnessCapsule对象。提供了两个方法：
 * getAllWitnesses(): 获取所有的见证人对象
 * get() 获取一个地址对应的见证人对象
 * */
@Slf4j(topic = "DB")
@Component
public class WitnessStore extends TronStoreWithRevoking<WitnessCapsule> {

  @Autowired
  protected WitnessStore(@Value("witness") String dbName) {
    super(dbName);
  }

  /**
   * get all witnesses.
   */
  public List<WitnessCapsule> getAllWitnesses() {
    return Streams.stream(iterator())
        .map(Entry::getValue)
        .collect(Collectors.toList());
  }

  @Override
  public WitnessCapsule get(byte[] key) {
    byte[] value = revokingDB.getUnchecked(key);
    return ArrayUtils.isEmpty(value) ? null : new WitnessCapsule(value);
  }
}
