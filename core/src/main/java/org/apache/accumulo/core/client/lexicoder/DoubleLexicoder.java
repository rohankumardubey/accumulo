/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.accumulo.core.client.lexicoder;

import org.apache.accumulo.core.client.lexicoder.impl.AbstractLexicoder;

/**
 * A lexicoder for preserving the native Java sort order of Double values.
 *
 * @since 1.6.0
 */
public class DoubleLexicoder extends AbstractLexicoder<Double> implements Lexicoder<Double> {

  private ULongLexicoder longEncoder = new ULongLexicoder();

  @Override
  public byte[] encode(Double d) {
    long l = Double.doubleToRawLongBits(d);
    if (l < 0)
      l = ~l;
    else
      l = l ^ 0x8000000000000000l;

    return longEncoder.encode(l);
  }

  /**
   * @deprecated Since 1.7.0
   */
  @Override
  @Deprecated
  public Double decode(byte[] b) {
    // This concrete implementation is provided for backwards compatibility with 1.6; it can be removed in 2.0. See ACCUMULO-3789.
    return super.decode(b);
  }

  @Override
  protected Double decodeUnchecked(byte[] data, int offset, int len) {
    long l = longEncoder.decodeUnchecked(data, offset, len);
    if (l < 0)
      l = l ^ 0x8000000000000000l;
    else
      l = ~l;
    return Double.longBitsToDouble(l);
  }

}
