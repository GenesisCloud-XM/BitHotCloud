package org.bhc.core.exception;

public class TooBigTransactionException extends TronException {

  public TooBigTransactionException() {
    super();
  }

  public TooBigTransactionException(String message) {
    super(message);
  }
}
