/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.accumulo.server.conf.codec;

import static org.apache.accumulo.server.conf.codec.VersionedProperties.tsFormatter;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Abstract class to provide encoding / decoding of versioned properties. This class handles the
 * serialization of the metadata and subclasses are required to implement
 * {@link #encodePayload(OutputStream, VersionedProperties, EncodingOptions)} and
 * {@link #decodePayload(InputStream, EncodingOptions)} to handle any specific implementation
 * metadata (optional) and the property map according to the encoding scheme of the subclass.
 * <p>
 * The basic encoding format:
 * <ul>
 * <li>encoding metadata - specifies codec to be used</li>
 * <li>version metadata - specifies property versioning information</li>
 * <li>codec specific metadata (optional)</li>
 * <li>the property map</li>
 * </ul>
 *
 */
public abstract class VersionedPropCodec {

  private final EncodingOptions encodingOpts;

  public VersionedPropCodec(final EncodingOptions encodingOpts) {
    this.encodingOpts = encodingOpts;
  }

  /**
   * The general encoding options that apply to all encodings.
   *
   * @return the general options.
   */
  public EncodingOptions getEncodingOpts() {
    return encodingOpts;
  }

  /**
   * Serialize the versioned properties. The version information on the properties is updated if the
   * data is successfully serialized.
   *
   * @param vProps
   *          the versioned properties.
   * @return a byte array with the serialized properties.
   */
  public byte[] toBytes(final VersionedProperties vProps) throws IOException {

    try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(bos)) {

      // write encoding metadata
      encodingOpts.encode(dos);

      // write version metadata
      DataVersionInfo vMetadata =
          new DataVersionInfo(vProps.getNextVersion(), vProps.getTimestamp());
      vMetadata.write(dos);

      // delegate property encoding to sub-class
      encodePayload(bos, vProps, encodingOpts);

      return bos.toByteArray();
    }
  }

  /**
   * Encode the properties and optionally any specific encoding metadata that is necessary to decode
   * the payload with the scheme chosen.
   *
   * @param out
   *          an output stream
   * @param vProps
   *          the versioned properties
   * @param encodingOpts
   *          the general encoding options.
   * @throws IOException
   *           if an error occurs writing to the underlying output stream.
   */
  abstract void encodePayload(final OutputStream out, final VersionedProperties vProps,
      final EncodingOptions encodingOpts) throws IOException;

  public VersionedProperties fromBytes(final byte[] bytes) throws IOException {

    try (ByteArrayInputStream bis = new ByteArrayInputStream(bytes);
        DataInputStream dis = new DataInputStream(bis)) {

      EncodingOptions encodingOpts = EncodingOptions.fromDataStream(dis);

      if (!checkCanDecodeVersion(encodingOpts)) {
        throw new IllegalArgumentException(
            "Invalid data version - cannot process the version read: "
                + encodingOpts.getEncodingVersion());
      }

      DataVersionInfo vMetadata = DataVersionInfo.fromDataStream(dis);

      Map<String,String> props = decodePayload(bis, encodingOpts);

      return new VersionedProperties(vMetadata.getDataVersion(), vMetadata.getTimestamp(), props);
    }
  }

  abstract boolean checkCanDecodeVersion(final EncodingOptions encodingOpts);

  /**
   * Extracts the encoding version from the encoded byte array without fully decoding the payload.
   * This is a convenience method if multiple encodings are present, and should only be required if
   * upgrading / changing encodings, otherwise a single encoding should be in operation for an
   * instance at any given time.
   *
   * @param bytes
   *          serialized encoded versioned property byte array.
   * @return the encoding version used to serialize the properties.
   */
  public static int getEncodingVersion(final byte[] bytes) {
    try (ByteArrayInputStream bis = new ByteArrayInputStream(bytes);
        DataInputStream dis = new DataInputStream(bis)) {
      return EncodingOptions.fromDataStream(dis).getEncodingVersion();
    } catch (NullPointerException | IOException ex) {
      throw new IllegalArgumentException("Failed to read encoding version from byte array provided",
          ex);
    }
  }

  /**
   * Extracts the data version from the encoded byte array without fully decoding the payload.
   * Normally the data version should be obtained from a fully decoded instance of the versioned
   * properties.
   * <p>
   * The cost of reading the byte array from the backing store should be considered verses the
   * additional cost of decoding - with a goal of reducing data reads from the store preferred.
   * Generally reading from the store will be followed by some sort of usage which would require the
   * full decode operation anyway.
   *
   * @param bytes
   *          serialized encoded versioned property byte array.
   * @return the encoding version used to serialize the properties.
   */
  public static int getDataVersion(final byte[] bytes) {
    try (ByteArrayInputStream bis = new ByteArrayInputStream(bytes);
        DataInputStream dis = new DataInputStream(bis)) {
      // skip encoding metadata
      EncodingOptions.fromDataStream(dis);
      return DataVersionInfo.fromDataStream(dis).getDataVersion();
    } catch (NullPointerException | IOException ex) {
      throw new IllegalArgumentException(
          "Failed to read data version version from byte array provided", ex);
    }
  }

  /**
   * Decode the payload and any optional encoding specific metadata and return a map of the property
   * name, value pairs.
   *
   * @param inStream
   *          an input stream
   * @param encodingOpts
   *          the general encoding options.
   * @return a map of properties name, value pairs.
   * @throws IOException
   *           if an exception occurs reading from the input stream.
   */
  abstract Map<String,String> decodePayload(final InputStream inStream,
      final EncodingOptions encodingOpts) throws IOException;

  /**
   * Read the property map from a data input stream as UTF strings. The input stream should be
   * created configured by sub-classes for the output of the sub-class. If the sub-class uses an
   * encoding other that UTF strings, they should override this method. An example would be an
   * encoding that uses JSON to encode the map.
   * <p>
   * The handling the properties as UTF strings is one implementation. Subclasses can implement
   * different mechanism if desired, one example might be using a JSON implementation to encode /
   * decode the properties.
   *
   * @param dis
   *          a data input stream
   * @return the property map
   * @throws IOException
   *           if an exception occurs reading from the stream.
   */
  Map<String,String> readMapAsUTF(DataInputStream dis) throws IOException {

    Map<String,String> aMap = new HashMap<>();
    int items = dis.readInt();

    for (int i = 0; i < items; i++) {
      String k = dis.readUTF();
      String v = dis.readUTF();
      aMap.put(k, v);
    }
    return aMap;
  }

  /**
   * Write the property map to the data output stream. The underlying stream is not closed by this
   * method.
   * <p>
   * The handling the properties as UTF strings is one implementation. Subclasses can implement
   * different mechanism if desired, one example might be using a JSON implementation to encode /
   * decode the properties.
   *
   * @param dos
   *          a data output stream
   * @param aMap
   *          the property map of k, v string pairs.
   * @throws IOException
   *           if an exception occurs.
   */
  void writeMapAsUTF(final DataOutputStream dos, final Map<String,String> aMap) throws IOException {

    dos.writeInt(aMap.size());

    for (Map.Entry<String,String> e : aMap.entrySet()) {
      dos.writeUTF(e.getKey());
      dos.writeUTF(e.getValue());
    }
    dos.flush();
  }

  /**
   * Helper class for reading / writing versioned properties metadata.
   */
  static class DataVersionInfo {
    private final int dataVersion;
    private final Instant timestamp;

    public DataVersionInfo(final int dataVersion, final Instant timestamp) {
      this.dataVersion = dataVersion;
      this.timestamp = timestamp;
    }

    public static DataVersionInfo fromDataStream(final DataInputStream dis) throws IOException {
      try {
        var dataVersion = dis.readInt();
        var timestamp = tsFormatter.parse(dis.readUTF(), Instant::from);
        return new DataVersionInfo(dataVersion, timestamp);
      } catch (Exception ex) {
        throw new IOException("Could not parse data version info", ex);
      }
    }

    public int getDataVersion() {
      return dataVersion;
    }

    public Instant getTimestamp() {
      return timestamp;
    }

    public void write(final DataOutputStream dos) throws IOException {
      dos.writeInt(dataVersion);
      dos.writeUTF(tsFormatter.format(timestamp));
    }
  }
}
