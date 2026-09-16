package com.hortinis.sync.protocol;

public final class InvalidRequestException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public InvalidRequestException() {
    super("The request is invalid.");
  }
}
