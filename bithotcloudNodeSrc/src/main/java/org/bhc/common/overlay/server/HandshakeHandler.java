/*
 * Copyright (c) [2016] [ <ether.camp> ]
 * This file is part of the ethereumJ library.
 *
 * The ethereumJ library is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * The ethereumJ library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with the ethereumJ library. If not, see <http://www.gnu.org/licenses/>.
 */
package org.bhc.common.overlay.server;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.spongycastle.util.encoders.Hex;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import org.bhc.common.overlay.discover.node.NodeManager;
import org.bhc.common.overlay.message.DisconnectMessage;
import org.bhc.common.overlay.message.HelloMessage;
import org.bhc.common.overlay.message.P2pMessage;
import org.bhc.common.overlay.message.P2pMessageFactory;
import org.bhc.core.config.args.Args;
import org.bhc.core.db.Manager;
import org.bhc.core.net.peer.PeerConnection;
import org.bhc.protos.Protocol.ReasonCode;

@Slf4j(topic = "net")
@Component
@Scope("prototype")
public class HandshakeHandler extends ByteToMessageDecoder {

  private byte[] remoteId;

  protected Channel channel;

  @Autowired
  protected NodeManager nodeManager;

  @Autowired
  protected ChannelManager channelManager;

  @Autowired
  protected Manager manager;

  private P2pMessageFactory messageFactory = new P2pMessageFactory();

  @Autowired
  private SyncPool syncPool;

  @Override
  public void channelActive(ChannelHandlerContext ctx) throws Exception {
    logger.info("channel active, {}", ctx.channel().remoteAddress());
    channel.setChannelHandlerContext(ctx);
    if (remoteId.length == 64) {
      channel.initNode(remoteId, ((InetSocketAddress) ctx.channel().remoteAddress()).getPort());
      sendHelloMsg(ctx, System.currentTimeMillis());
    }
  }

  @Override
  protected void decode(ChannelHandlerContext ctx, ByteBuf buffer, List<Object> out)
      throws Exception {
    byte[] encoded = new byte[buffer.readableBytes()];
    buffer.readBytes(encoded);
    P2pMessage msg = messageFactory.create(encoded);

    logger.info("Handshake Receive from {}, {}", ctx.channel().remoteAddress(), msg);

    switch (msg.getType()) {
      case P2P_HELLO:
        handleHelloMsg(ctx, (HelloMessage) msg);
        break;
      case P2P_DISCONNECT:
        if (channel.getNodeStatistics() != null) {
          channel.getNodeStatistics()
              .nodeDisconnectedRemote(((DisconnectMessage) msg).getReasonCode());
        }
        channel.close();
        break;
      default:
        channel.close();
        break;
    }
  }

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
    channel.processException(cause);
  }

  public void setChannel(Channel channel, String remoteId) {
    this.channel = channel;
    this.remoteId = Hex.decode(remoteId);
  }

  protected void sendHelloMsg(ChannelHandlerContext ctx, long time) {

    HelloMessage message = new HelloMessage(
    		nodeManager.getPublicHomeNode(),
    		time,
            manager.getGenesisBlockId(), 
            manager.getSolidBlockId(), 
            manager.getHeadBlockId()       );
    ctx.writeAndFlush(message.getSendData());
    channel.getNodeStatistics().messageStatistics.addTcpOutMessage(message);
    logger.info("Handshake Send to {}, {} ", ctx.channel().remoteAddress(), message);
  }

  private void handleHelloMsg(ChannelHandlerContext ctx, HelloMessage msg) {

    channel.initNode(msg.getFrom().getId(), msg.getFrom().getPort());
    //服务端的代码执行此段， 接收方已经连接太多节点了，中断连接.
    if (remoteId.length != 64) {
      InetAddress address = ((InetSocketAddress) ctx.channel().remoteAddress()).getAddress();
      if (!channelManager.getTrustNodes().keySet().contains(address) && !syncPool.isCanConnect()) {
        channel.disconnect(ReasonCode.TOO_MANY_PEERS);
        return;
      }
    }
    //检查版本号要一致
    if (msg.getVersion() != Args.getInstance().getNodeP2pVersion()) {
      logger.info("Peer {} different p2p version, peer->{}, me->{}",
          ctx.channel().remoteAddress(), msg.getVersion(), Args.getInstance().getNodeP2pVersion());
      channel.disconnect(ReasonCode.INCOMPATIBLE_VERSION);
      return;
    }

    //检查创世区块也要相同
    if (!Arrays
        .equals(manager.getGenesisBlockId().getBytes(), msg.getGenesisBlockId().getBytes())) {
      logger
          .info("Peer {} different genesis block, peer->{}, me->{}", ctx.channel().remoteAddress(),
              msg.getGenesisBlockId().getString(), manager.getGenesisBlockId().getString());
      channel.disconnect(ReasonCode.INCOMPATIBLE_CHAIN);
      return;
    }
    //检查SolidBlock也要相同， 啥含义不明白。 
    //如果编号>,必然包含小号的SolidBlock；如果不包含就说明不是同一个区块链上的。
    if (manager.getSolidBlockId().getNum() >= msg.getSolidBlockId().getNum() && !manager
        .containBlockInMainChain(msg.getSolidBlockId())) {
      logger.info("Peer {} different solid block, peer->{}, me->{}", ctx.channel().remoteAddress(),
          msg.getSolidBlockId().getString(), manager.getSolidBlockId().getString());
      channel.disconnect(ReasonCode.FORKED);
      return;
    }
    //保存消息
    ((PeerConnection) channel).setHelloMessage(msg);

    channel.getNodeStatistics().messageStatistics.addTcpInMessage(msg);
    //握手结束，状态切换
    channel.publicHandshakeFinished(ctx, msg);
    if (!channelManager.processPeer(channel)) {
      return;
    }

//    System.out.println("==== remoteId="+remoteId);
//    System.out.println("==== remoteId.length="+remoteId.length);
    //服务端的remoteId初始为空，因此会发送Hello消息。 客户端？？？？？
    if (remoteId.length != 64) {      	
      sendHelloMsg(ctx, msg.getTimestamp());
    }

    System.out.println("------ syncPool.onConnect(channel) ......");
    syncPool.onConnect(channel);   //开始同步流程
  }
}
