package org.bhc.core.db;

import org.bhc.core.db2.common.IRevokingDB;
import org.bhc.core.db2.core.ISession;
import org.bhc.core.exception.RevokingStoreIllegalStateException;

public interface RevokingDatabase {

  ISession buildSession();

  ISession buildSession(boolean forceEnable);

  void setMode(boolean mode);

  void add(IRevokingDB revokingDB);

  void merge() throws RevokingStoreIllegalStateException;

  void revoke() throws RevokingStoreIllegalStateException;

  void commit() throws RevokingStoreIllegalStateException;

  void pop() throws RevokingStoreIllegalStateException;

  void fastPop() throws RevokingStoreIllegalStateException;

  void enable();

  int size();

  void check();

  void setMaxSize(int maxSize);

  void disable();

  void setMaxFlushCount(int maxFlushCount);

  void shutdown();

}
