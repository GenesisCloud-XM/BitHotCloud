package org.bhc.core.capsule;

public interface ProtoCapsule<T> {

  byte[] getData();

  T getInstance();
}
