package org.bhc.common.overlay.message;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.Arrays;
import org.apache.commons.lang3.ArrayUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.bhc.common.utils.Sha256Hash;
import org.bhc.core.net.message.MessageTypes;

// �շ���Ϣ�Ļ����ͣ� �߱�����+���ͣ�
public abstract class Message {

  protected static final Logger logger = LoggerFactory.getLogger("Message");

  protected byte[] data;   //��Ϣ���� 
  protected byte type;     //��Ϣ����

  public Message() {
  }

  public Message(byte[] packed) {
    this.data = packed;
  }

  public Message(byte type, byte[] packed) {
    this.type = type;
    this.data = packed;
  }

  //��ȡ�������ݣ������������ڵ㷢����Ϣ�� �������������type+data����ϣ���װ��netty��ܸ�ʽByteBuf
  public ByteBuf getSendData() {
	  //add������type����ָ��λ�ã������µ����顣Ȼ���byte�����װ��bytebuf��
    return Unpooled.wrappedBuffer(ArrayUtils.add(this.getData(), 0, type));
  }

  //����data��SHA256ֵ��������Sha256Hash������
  public Sha256Hash getMessageId() {
    return Sha256Hash.of(getData());
  }

  //��������ݶ�
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