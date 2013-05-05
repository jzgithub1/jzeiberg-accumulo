package org.apache.accumulo.core.client.lexicoder.util;

import java.util.ArrayList;

public class ByteUtils {

  /**
   * Escapes 0x00 with 0x01 0x01 and 0x01 with 0x01 0x02
   *
   * @param in
   * @return
   */
  public static byte[] escape(byte[] in) {
    int escapeCount = 0;
    for (int i = 0; i < in.length; i++) {
      if (in[i] == 0x00 || in[i] == 0x01) {
        escapeCount++;
      }
    }

    if (escapeCount == 0)
      return in;

    byte ret[] = new byte[escapeCount + in.length];
    int index = 0;

    for (int i = 0; i < in.length; i++) {
      switch (in[i]) {
        case 0x00:
          ret[index++] = 0x01;
          ret[index++] = 0x01;
          break;
        case 0x01:
          ret[index++] = 0x01;
          ret[index++] = 0x02;
          break;
        default:
          ret[index++] = in[i];
      }
    }

    return ret;
  }

  public static byte[] unescape(byte[] in) {
    int escapeCount = 0;
    for (int i = 0; i < in.length; i++) {
      if (in[i] == 0x01) {
        escapeCount++;
        i++;
      }
    }

    if (escapeCount == 0)
      return in;

    byte ret[] = new byte[in.length - escapeCount];

    int index = 0;
    for (int i = 0; i < in.length; i++) {
      if (in[i] == 0x01) {
        i++;
        ret[index++] = (byte) (in[i] - 1);
      } else {
        ret[index++] = in[i];
      }

    }

    return ret;
  }

  public static byte[][] split(byte[] data) {
    ArrayList<Integer> offsets = new ArrayList<Integer>();

    for (int i = 0; i < data.length; i++) {
      if (data[i] == 0x00) {
        offsets.add(i);
      }
    }

    offsets.add(data.length);

    byte[][] ret = new byte[offsets.size()][];

    int index = 0;
    for (int i = 0; i < offsets.size(); i++) {
      ret[i] = new byte[offsets.get(i) - index];
      System.arraycopy(data, index, ret[i], 0, ret[i].length);
      index = offsets.get(i) + 1;
    }

    return ret;
  }

  public static byte[] concat(byte[]... fields) {
    int len = 0;
    for (byte[] field : fields) {
      len += field.length;
    }

    byte ret[] = new byte[len + fields.length - 1];
    int index = 0;

    for (byte[] field : fields) {
      System.arraycopy(field, 0, ret, index, field.length);
      index += field.length;
      if (index < ret.length)
        ret[index++] = 0x00;
    }

    return ret;
  }


}
