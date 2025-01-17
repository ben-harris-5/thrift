/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.thrift;

import java.util.Collections;
import java.util.Map;
import org.apache.thrift.async.AsyncMethodCallback;
import org.apache.thrift.protocol.*;
import org.apache.thrift.server.AbstractNonblockingServer.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TBaseAsyncProcessor<I> implements TAsyncProcessor, TProcessor {
  protected final Logger LOGGER = LoggerFactory.getLogger(getClass().getName());

  final I iface;
  final Map<String, AsyncProcessFunction<I, ? extends TBase, ?, ? extends TBase>> processMap;

  public TBaseAsyncProcessor(
      I iface, Map<String, AsyncProcessFunction<I, ? extends TBase, ?, ? extends TBase>> processMap) {
    this.iface = iface;
    this.processMap = processMap;
  }

  public Map<String, AsyncProcessFunction<I, ? extends TBase, ?, ? extends TBase>> getProcessMapView() {
    return Collections.unmodifiableMap(processMap);
  }

  public void process(final AsyncFrameBuffer fb) throws TException {

    final TProtocol in = fb.getInputProtocol();
    final TProtocol out = fb.getOutputProtocol();

    // Find processing function
    final TMessage msg = in.readMessageBegin();
    AsyncProcessFunction fn = processMap.get(msg.name);
    if (fn == null) {
      TProtocolUtil.skip(in, TType.STRUCT);
      in.readMessageEnd();

      TApplicationException x =
          new TApplicationException(
              TApplicationException.UNKNOWN_METHOD, "Invalid method name: '" + msg.name + "'");
      LOGGER.debug("Invalid method name", x);

      // this means it is a two-way request, so we can send a reply
      if (msg.type == TMessageType.CALL) {
        out.writeMessageBegin(new TMessage(msg.name, TMessageType.EXCEPTION, msg.seqid));
        x.write(out);
        out.writeMessageEnd();
        out.getTransport().flush();
      }
      fb.responseReady();
      return;
    }

    // Get Args
    TBase args = fn.getEmptyArgsInstance();

    try {
      args.read(in);
    } catch (TProtocolException e) {
      in.readMessageEnd();

      TApplicationException x =
          new TApplicationException(TApplicationException.PROTOCOL_ERROR, e.getMessage());
      LOGGER.debug("Could not retrieve function arguments", x);

      if (!fn.isOneway()) {
        out.writeMessageBegin(new TMessage(msg.name, TMessageType.EXCEPTION, msg.seqid));
        x.write(out);
        out.writeMessageEnd();
        out.getTransport().flush();
      }
      fb.responseReady();
      return;
    }
    in.readMessageEnd();

    if (fn.isOneway()) {
      fb.responseReady();
    }

    // start off processing function
    AsyncMethodCallback resultHandler = fn.getResultHandler(fb, msg.seqid);
    try {
      fn.start(iface, args, resultHandler);
    } catch (Exception e) {
      LOGGER.debug("Exception handling function", e);
      resultHandler.onError(e);
    }
    return;
  }

  @Override
  public void process(TProtocol in, TProtocol out) throws TException {}
}
