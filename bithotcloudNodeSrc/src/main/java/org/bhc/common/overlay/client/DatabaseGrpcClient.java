package org.bhc.common.overlay.client;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.bhc.api.DatabaseGrpc;
import org.bhc.api.GrpcAPI.EmptyMessage;
import org.bhc.api.GrpcAPI.NumberMessage;
import org.bhc.protos.Protocol.Block;
import org.bhc.protos.Protocol.DynamicProperties;

public class DatabaseGrpcClient {

  private final ManagedChannel channel;
  private final DatabaseGrpc.DatabaseBlockingStub databaseBlockingStub;

  public DatabaseGrpcClient(String host, int port) {
    channel = ManagedChannelBuilder.forAddress(host, port)
        .usePlaintext(true)
        .build();
    databaseBlockingStub = DatabaseGrpc.newBlockingStub(channel);
  }

  public DatabaseGrpcClient(String host) {
    channel = ManagedChannelBuilder.forTarget(host)
        .usePlaintext(true)
        .build();
    databaseBlockingStub = DatabaseGrpc.newBlockingStub(channel);
  }


  public Block getBlock(long blockNum) {
    if (blockNum < 0) {
      return databaseBlockingStub.getNowBlock(EmptyMessage.newBuilder().build());
    }
    NumberMessage.Builder builder = NumberMessage.newBuilder();
    builder.setNum(blockNum);
    return databaseBlockingStub.getBlockByNum(builder.build());
  }

  public void shutdown() {
    channel.shutdown();
  }

  public DynamicProperties getDynamicProperties() {
    return databaseBlockingStub.getDynamicProperties(EmptyMessage.newBuilder().build());
  }
}
