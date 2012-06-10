/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.handler.stream;

import static org.junit.Assert.*;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufs;
import io.netty.buffer.MessageBuf;
import io.netty.buffer.MessageBufs;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedMessageChannel;
import io.netty.util.CharsetUtil;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.Channels;
import java.util.concurrent.atomic.AtomicBoolean;

import junit.framework.Assert;

import org.junit.Test;

public class ChunkedWriteHandlerTest {
    private static final byte[] BYTES = new byte[1024 * 64];
    private static final File TMP;

    static {
        for (int i = 0; i < BYTES.length; i++) {
            BYTES[i] = (byte) i;
        }

        FileOutputStream out = null;
        try {
            TMP = File.createTempFile("netty-chunk-", ".tmp");
            TMP.deleteOnExit();
            out = new FileOutputStream(TMP);
            out.write(BYTES);
            out.flush();
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException e) {
                    // ignore
                }
            }
        }
    }

    // See #310
    @Test
    public void testChunkedStream() {
        check(new ChunkedStream(new ByteArrayInputStream(BYTES)));

        check(new ChunkedStream(new ByteArrayInputStream(BYTES)), new ChunkedStream(new ByteArrayInputStream(BYTES)), new ChunkedStream(new ByteArrayInputStream(BYTES)));

    }

    @Test
    public void testChunkedNioStream() {
        check(new ChunkedNioStream(Channels.newChannel(new ByteArrayInputStream(BYTES))));

        check(new ChunkedNioStream(Channels.newChannel(new ByteArrayInputStream(BYTES))), new ChunkedNioStream(Channels.newChannel(new ByteArrayInputStream(BYTES))), new ChunkedNioStream(Channels.newChannel(new ByteArrayInputStream(BYTES))));

    }


    @Test
    public void testChunkedFile() throws IOException {
        check(new ChunkedFile(TMP));

        check(new ChunkedFile(TMP), new ChunkedFile(TMP), new ChunkedFile(TMP));
    }

    @Test
    public void testChunkedNioFile() throws IOException {
        check(new ChunkedNioFile(TMP));

        check(new ChunkedNioFile(TMP), new ChunkedNioFile(TMP), new ChunkedNioFile(TMP));
    }

    // Test case which shows that there is not a bug like stated here:
    // http://stackoverflow.com/questions/10409241/why-is-close-channelfuturelistener-not-notified/10426305#comment14126161_10426305
    @Test
    public void testListenerNotifiedWhenIsEnd() {
        ByteBuf buffer = ByteBufs.copiedBuffer("Test", CharsetUtil.ISO_8859_1);

        ChunkedInput input = new ChunkedInput() {
            private boolean done;
            private final ByteBuf buffer = ByteBufs.copiedBuffer("Test", CharsetUtil.ISO_8859_1);

            @Override
            public Object nextChunk() throws Exception {
                done = true;
                return buffer.duplicate();
            }

            @Override
            public boolean isEndOfInput() throws Exception {
                return done;
            }

            @Override
            public void close() throws Exception {
                // NOOP
            }
        };

        final AtomicBoolean listenerNotified = new AtomicBoolean(false);
        final ChannelFutureListener listener = new ChannelFutureListener() {

            @Override
            public void operationComplete(ChannelFuture future) throws Exception {
                listenerNotified.set(true);
            }
        };

        ChannelOutboundHandlerAdapter testHandler = new ChannelOutboundHandlerAdapter() {

            @Override
            public MessageBuf<ByteBuf> newOutboundBuffer(ChannelHandlerContext ctx) throws Exception {
                return MessageBufs.buffer();
            }

            @Override
            public void flush(ChannelHandlerContext ctx, ChannelFuture future) throws Exception {
                future.setSuccess();
            }
        };

        EmbeddedMessageChannel ch = new EmbeddedMessageChannel(new ChunkedWriteHandler(), testHandler);
        ch.outboundMessageBuffer().add(input);
        ch.flush().addListener(listener).syncUninterruptibly();
        ch.checkException();
        ch.finish();

        // the listener should have been notified
        assertTrue(listenerNotified.get());

        assertEquals(buffer, ch.readOutbound());
        assertNull(ch.readOutbound());

    }

    private static void check(ChunkedInput... inputs) {
        EmbeddedMessageChannel ch = new EmbeddedMessageChannel(new ChunkedWriteHandler());

        for (ChunkedInput input: inputs) {
            ch.writeOutbound(input);
        }

        Assert.assertTrue(ch.finish());

        int i = 0;
        int read = 0;
        for (;;) {
            ByteBuf buffer = (ByteBuf) ch.readOutbound();
            if (buffer == null) {
                break;
            }
            while (buffer.readable()) {
                Assert.assertEquals(BYTES[i++], buffer.readByte());
                read++;
                if (i == BYTES.length) {
                    i = 0;
                }
            }
        }

        Assert.assertEquals(BYTES.length * inputs.length, read);
    }
}
