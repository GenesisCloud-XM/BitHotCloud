package org.bhc.common.overlay.message;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.Arrays;
import org.apache.commons.lang3.ArrayUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.bhc.common.utils.Sha256Hash;
import org.bhc.core.net.message.MessageTypes;

// 收发消息的基类型， 具备数据+类型，
public abstract class Message {

  protected static final Logger logger = LoggerFactory.getLogger("Message");

  protected byte[] data;   //消息数据 
  protected byte type;     //消息类型

  public Message() {
  }

  public Message(byte[] packed) {
    this.data = packed;
  }

  public Message(byte type, byte[] packed) {
    this.type = type;
    this.data = packed;
  }

  //获取发送数据，用于向其他节点发送消息。 这个发送数据是type+data的组合，包装成netty框架格式ByteBuf
  public ByteBuf getSendData() {
	  //add函数把type插入指定位置，生成新的数组。然后把byte数组包装成bytebuf。
    return Unpooled.wrappedBuffer(ArrayUtils.add(this.getData(), 0, type));
  }

  //计算data的SHA256值，保存在Sha256Hash对象中
  public Sha256Hash getMessageId() {
    return Sha256Hash.of(getData());
  }

  //纯粹的数据段
  public byte[] getData() {
    return this.data;
  }

  public MessageTypes getType() {
    return MessageTypes.fromByte(this.type);
  }

  public abstract Class<?> getAnswerMessage();

  @Override
  public String toString() {
    return "type: " + getType() + "\n";
  }

  @Override
  public int hashCode() {
    return Arrays.hashCode(data);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof Message)) {
      return false;
    }
    Message message = (Message) o;
    return Arrays.equals(data, message.data);
  }
}