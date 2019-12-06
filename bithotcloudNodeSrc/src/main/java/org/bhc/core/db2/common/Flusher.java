package org.bhc.core.db2.common;

import java.util.Map;
import org.bhc.core.db.common.WrappedByteArray;

public interface Flusher {

  void flush(Map<WrappedByteArray, WrappedByteArray> batch);

  void close();

  void reset();
}
