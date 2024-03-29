package org.bhc.core.db.common.iterator;

import org.bhc.core.capsule.WitnessCapsule;

import java.util.Iterator;
import java.util.Map.Entry;

public class WitnessIterator extends AbstractIterator<WitnessCapsule> {

  public WitnessIterator(Iterator<Entry<byte[], byte[]>> iterator) {
    super(iterator);
  }

  @Override
  protected WitnessCapsule of(byte[] value) {
    return new WitnessCapsule(value);
  }
}
