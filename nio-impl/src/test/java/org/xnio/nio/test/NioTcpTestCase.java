/*
 * JBoss, Home of Professional Open Source
 * Copyright 2008, JBoss Inc., and individual contributors as indicated
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.xnio.nio.test;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import junit.framework.TestCase;
import org.jboss.logging.Logger;
import org.xnio.ChannelListeners;
import org.xnio.ConnectionChannelThread;
import org.xnio.IoFuture;
import org.xnio.IoUtils;
import org.xnio.ReadChannelThread;
import org.xnio.WriteChannelThread;
import org.xnio.Xnio;
import org.xnio.OptionMap;
import org.xnio.ChannelListener;
import org.xnio.Options;
import org.xnio.FutureResult;
import org.xnio.channels.AcceptingChannel;
import org.xnio.channels.BoundChannel;
import org.xnio.channels.Channels;
import org.xnio.channels.ConnectedStreamChannel;

@SuppressWarnings( { "JavaDoc" })
public final class NioTcpTestCase extends TestCase {

    private static final Logger log = Logger.getLogger("TEST");

    private final TestThreadFactory threadFactory = new TestThreadFactory();

    private static final int SERVER_PORT = 12345;

    private void doConnectionTest(final Runnable body, final ChannelListener<? super ConnectedStreamChannel> clientHandler, final ChannelListener<? super ConnectedStreamChannel> serverHandler) throws Exception {
        Xnio xnio = Xnio.getInstance("nio", NioTcpTestCase.class.getClassLoader());
        final ConnectionChannelThread connectionChannelThread = xnio.createConnectionChannelThread(threadFactory);
        final ConnectionChannelThread serverChannelThread = xnio.createConnectionChannelThread(threadFactory);
        final ReadChannelThread readChannelThread = xnio.createReadChannelThread(threadFactory);
        final ReadChannelThread clientReadChannelThread = xnio.createReadChannelThread(threadFactory);
        final WriteChannelThread writeChannelThread = xnio.createWriteChannelThread(threadFactory);
        final WriteChannelThread clientWriteChannelThread = xnio.createWriteChannelThread(threadFactory);
        try {
            final AcceptingChannel<? extends ConnectedStreamChannel> server = xnio.createStreamServer(
                    new InetSocketAddress(Inet4Address.getByAddress(new byte[] { 127, 0, 0, 1 }), SERVER_PORT),
                    serverChannelThread,
                    ChannelListeners.<ConnectedStreamChannel>openListenerAdapter(readChannelThread, writeChannelThread, new CatchingChannelListener<ConnectedStreamChannel>(
                            serverHandler,
                            threadFactory
                    )), OptionMap.create(Options.REUSE_ADDRESSES, Boolean.TRUE));
            server.resumeAccepts();
            try {
                final IoFuture<? extends ConnectedStreamChannel> ioFuture = xnio.connectStream(new InetSocketAddress(Inet4Address.getByAddress(new byte[] { 127, 0, 0, 1 }), SERVER_PORT), connectionChannelThread, clientReadChannelThread, clientWriteChannelThread, new CatchingChannelListener<ConnectedStreamChannel>(clientHandler, threadFactory), null, OptionMap.EMPTY);
                final ConnectedStreamChannel channel = ioFuture.get();
                try {
                    body.run();
                    channel.close();
                    server.close();
                } catch (Exception e) {
                    log.errorf(e, "Error running body");
                    throw e;
                } catch (Error e) {
                    log.errorf(e, "Error running body");
                    throw e;
                } finally {
                    IoUtils.safeClose(channel);
                }
            } finally {
                IoUtils.safeClose(server);
            }
        } finally {
            connectionChannelThread.shutdown();
            serverChannelThread.shutdown();
            clientReadChannelThread.shutdown();
            clientWriteChannelThread.shutdown();
            readChannelThread.shutdown();
            writeChannelThread.shutdown();
        }
        connectionChannelThread.awaitTermination();
        serverChannelThread.awaitTermination();
        readChannelThread.awaitTermination();
        writeChannelThread.awaitTermination();
        clientReadChannelThread.awaitTermination();
        clientWriteChannelThread.awaitTermination();
    }

    public void testTcpConnect() throws Exception {
        threadFactory.clear();
        log.info("Test: testTcpConnect");
        doConnectionTest(new Runnable() {
            public void run() {
            }
        }, null, ChannelListeners.closingChannelListener());
        threadFactory.await();
    }

    public void testClientTcpClose() throws Exception {
        threadFactory.clear();
        log.info("Test: testClientTcpClose");
        final CountDownLatch latch = new CountDownLatch(2);
        final AtomicBoolean clientOK = new AtomicBoolean(false);
        final AtomicBoolean serverOK = new AtomicBoolean(false);
        doConnectionTest(new Runnable() {
            public void run() {
                try {
                    assertTrue(latch.await(500L, TimeUnit.MILLISECONDS));
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        }, new ChannelListener<ConnectedStreamChannel>() {
            public void handleEvent(final ConnectedStreamChannel channel) {
                log.info("In client open");
                try {
                    channel.getCloseSetter().set(new ChannelListener<ConnectedStreamChannel>() {
                        public void handleEvent(final ConnectedStreamChannel channel) {
                            log.info("In client close");
                            latch.countDown();
                        }
                    });
                    channel.close();
                    clientOK.set(true);
                } catch (Throwable t) {
                    log.error("In client", t);
                    latch.countDown();
                    throw new RuntimeException(t);
                }
            }
        }, new ChannelListener<ConnectedStreamChannel>() {
            public void handleEvent(final ConnectedStreamChannel channel) {
                log.info("In server opened");
                channel.getCloseSetter().set(new ChannelListener<ConnectedStreamChannel>() {
                    public void handleEvent(final ConnectedStreamChannel channel) {
                        log.info("In server close");
                        latch.countDown();
                    }
                });
                channel.getReadSetter().set(new ChannelListener<ConnectedStreamChannel>() {
                    public void handleEvent(final ConnectedStreamChannel channel) {
                        log.info("In server readable");
                        try {
                            final int c = channel.read(ByteBuffer.allocate(100));
                            if (c == -1) {
                                serverOK.set(true);
                            }
                            channel.close();
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                        latch.countDown();
                    }
                });
                channel.resumeReads();
            }
        });
        assertTrue(serverOK.get());
        assertTrue(clientOK.get());
        threadFactory.await();
    }

    public void testServerTcpClose() throws Exception {
        threadFactory.clear();
        log.info("Test: testServerTcpClose");
        final CountDownLatch latch = new CountDownLatch(2);
        final AtomicBoolean clientOK = new AtomicBoolean(false);
        final AtomicBoolean serverOK = new AtomicBoolean(false);
        doConnectionTest(new Runnable() {
            public void run() {
                try {
                    assertTrue(latch.await(500L, TimeUnit.MILLISECONDS));
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        }, new ChannelListener<ConnectedStreamChannel>() {
            public void handleEvent(final ConnectedStreamChannel channel) {
                try {
                    channel.getCloseSetter().set(new ChannelListener<ConnectedStreamChannel>() {
                        public void handleEvent(final ConnectedStreamChannel channel) {
                            latch.countDown();
                        }
                    });
                    channel.getReadSetter().set(new ChannelListener<ConnectedStreamChannel>() {
                        public void handleEvent(final ConnectedStreamChannel channel) {
                            try {
                                final int c = channel.read(ByteBuffer.allocate(100));
                                if (c == -1) {
                                    clientOK.set(true);
                                }
                                channel.close();
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        }
                    });
                    channel.resumeReads();
                } catch (Throwable t) {
                    try {
                        channel.close();
                    } catch (Throwable t2) {
                        t.printStackTrace();
                        latch.countDown();
                        throw new RuntimeException(t);
                    }
                    throw new RuntimeException(t);
                }
            }
        }, new ChannelListener<ConnectedStreamChannel>() {
            public void handleEvent(final ConnectedStreamChannel channel) {
                try {
                    channel.getCloseSetter().set(new ChannelListener<ConnectedStreamChannel>() {
                        public void handleEvent(final ConnectedStreamChannel channel) {
                            serverOK.set(true);
                            latch.countDown();
                        }
                    });
                    channel.close();
                } catch (Throwable t) {
                    t.printStackTrace();
                    latch.countDown();
                    throw new RuntimeException(t);
                }
            }
        });
        assertTrue(serverOK.get());
        assertTrue(clientOK.get());
        threadFactory.await();
    }

    public void testTwoWayTransfer() throws Exception {
        threadFactory.clear();
        log.info("Test: testTwoWayTransfer");
        final CountDownLatch latch = new CountDownLatch(2);
        final AtomicInteger clientSent = new AtomicInteger(0);
        final AtomicInteger clientReceived = new AtomicInteger(0);
        final AtomicInteger serverSent = new AtomicInteger(0);
        final AtomicInteger serverReceived = new AtomicInteger(0);
        final AtomicBoolean delayClientStop = new AtomicBoolean();
        final AtomicBoolean delayServerStop = new AtomicBoolean();
        doConnectionTest(new Runnable() {
            public void run() {
                try {
                    assertTrue(latch.await(500L, TimeUnit.MILLISECONDS));
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        }, new ChannelListener<ConnectedStreamChannel>() {
            public void handleEvent(final ConnectedStreamChannel channel) {
                channel.getCloseSetter().set(new ChannelListener<ConnectedStreamChannel>() {
                    public void handleEvent(final ConnectedStreamChannel channel) {
                        latch.countDown();
                    }
                });
                channel.getReadSetter().set(new ChannelListener<ConnectedStreamChannel>() {
                    public void handleEvent(final ConnectedStreamChannel channel) {
                        try {
                            int c;
                            while ((c = channel.read(ByteBuffer.allocate(100))) > 0) {
                                clientReceived.addAndGet(c);
                            }
                            if (c == -1) {
                                if (delayClientStop.getAndSet(true)) {
                                    channel.close();
                                }
                            } else {
                                channel.resumeReads();
                            }
                        } catch (Throwable t) {
                            t.printStackTrace();
                            throw new RuntimeException(t);
                        }
                    }
                });
                channel.getWriteSetter().set(new ChannelListener<ConnectedStreamChannel>() {
                    public void handleEvent(final ConnectedStreamChannel channel) {
                        try {
                            final ByteBuffer buffer = ByteBuffer.allocate(100);
                            buffer.put("This Is A Test\r\n".getBytes("UTF-8")).flip();
                            int c;
                            while ((c = channel.write(buffer)) > 0) {
                                if (clientSent.addAndGet(c) > 1000) {
                                    channel.shutdownWrites();
                                    if (delayClientStop.getAndSet(true)) {
                                        channel.close();
                                    }
                                    return;
                                }
                                buffer.rewind();
                            }
                            channel.resumeWrites();
                        } catch (Throwable t) {
                            t.printStackTrace();
                            throw new RuntimeException(t);
                        }
                    }
                });

                channel.resumeReads();
                channel.resumeWrites();
            }
        }, new ChannelListener<ConnectedStreamChannel>() {
            public void handleEvent(final ConnectedStreamChannel channel) {
                channel.getCloseSetter().set(new ChannelListener<ConnectedStreamChannel>() {
                    public void handleEvent(final ConnectedStreamChannel channel) {
                        latch.countDown();
                    }
                });
                channel.getReadSetter().set(new ChannelListener<ConnectedStreamChannel>() {
                    public void handleEvent(final ConnectedStreamChannel channel) {
                        try {
                            int c;
                            while ((c = channel.read(ByteBuffer.allocate(100))) > 0) {
                                serverReceived.addAndGet(c);
                            }
                            if (c == -1) {
                                if (delayServerStop.getAndSet(true)) {
                                    channel.close();
                                }
                            } else {
                                channel.resumeReads();
                            }
                        } catch (Throwable t) {
                            t.printStackTrace();
                            throw new RuntimeException(t);
                        }
                    }
                });
                channel.getWriteSetter().set(new ChannelListener<ConnectedStreamChannel>() {
                    public void handleEvent(final ConnectedStreamChannel channel) {
                        try {
                            final ByteBuffer buffer = ByteBuffer.allocate(100);
                            buffer.put("This Is A Test Gumma\r\n".getBytes("UTF-8")).flip();
                            int c;
                            while ((c = channel.write(buffer)) > 0) {
                                if (serverSent.addAndGet(c) > 1000) {
                                    channel.shutdownWrites();
                                    if (delayServerStop.getAndSet(true)) {
                                        channel.close();
                                    }
                                    return;
                                }
                                buffer.rewind();
                            }
                            channel.resumeWrites();
                        } catch (Throwable t) {
                            t.printStackTrace();
                            throw new RuntimeException(t);
                        }
                    }
                });
                channel.resumeReads();
                channel.resumeWrites();
            }
        });
        assertEquals(serverSent.get(), clientReceived.get());
        assertEquals(clientSent.get(), serverReceived.get());
        threadFactory.await();
    }

    public void testClientTcpNastyClose() throws Exception {
        threadFactory.clear();
        log.info("Test: testClientTcpNastyClose");
        final CountDownLatch latch = new CountDownLatch(2);
        final AtomicBoolean clientOK = new AtomicBoolean(false);
        final AtomicBoolean serverOK = new AtomicBoolean(false);
        doConnectionTest(new Runnable() {
            public void run() {
                try {
                    assertTrue(latch.await(500L, TimeUnit.MILLISECONDS));
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        }, new ChannelListener<ConnectedStreamChannel>() {
            public void handleEvent(final ConnectedStreamChannel channel) {
                try {
                    channel.getCloseSetter().set(new ChannelListener<ConnectedStreamChannel>() {
                        public void handleEvent(final ConnectedStreamChannel channel) {
                            latch.countDown();
                        }
                    });
                    channel.setOption(Options.CLOSE_ABORT, Boolean.TRUE);
                    channel.close();
                    clientOK.set(true);
                } catch (Throwable t) {
                    t.printStackTrace();
                    latch.countDown();
                    throw new RuntimeException(t);
                }
            }
        }, new ChannelListener<ConnectedStreamChannel>() {
            public void handleEvent(final ConnectedStreamChannel channel) {
                try {
                    channel.getCloseSetter().set(new ChannelListener<ConnectedStreamChannel>() {
                        public void handleEvent(final ConnectedStreamChannel channel) {
                            latch.countDown();
                        }
                    });
                    channel.getReadSetter().set(new ChannelListener<ConnectedStreamChannel>() {
                        public void handleEvent(final ConnectedStreamChannel channel) {
                            try {
                                channel.read(ByteBuffer.allocate(100));
                            } catch (IOException e) {
                                if (e.getMessage().toLowerCase().contains("reset")) {
                                    serverOK.set(true);
                                } else {
                                    throw new RuntimeException(e);
                                }
                            } finally {
                                IoUtils.safeClose(channel);
                            }
                        }
                    });
                    channel.resumeReads();
                } catch (Throwable t) {
                    t.printStackTrace();
                    latch.countDown();
                    throw new RuntimeException(t);
                }
            }
        });
        assertTrue(serverOK.get());
        assertTrue(clientOK.get());
        threadFactory.await();
    }

    public void testServerTcpNastyClose() throws Exception {
        threadFactory.clear();
        log.info("Test: testServerTcpNastyClose");
        final CountDownLatch latch = new CountDownLatch(2);
        final AtomicBoolean clientOK = new AtomicBoolean(false);
        final AtomicBoolean serverOK = new AtomicBoolean(false);
        final CountDownLatch serverLatch = new CountDownLatch(1);
        doConnectionTest(new Runnable() {
            public void run() {
                try {
                    assertTrue(latch.await(500L, TimeUnit.MILLISECONDS));
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        }, new ChannelListener<ConnectedStreamChannel>() {
            public void handleEvent(final ConnectedStreamChannel channel) {
                try {
                    log.info("Client opened");
                    channel.getCloseSetter().set(new ChannelListener<ConnectedStreamChannel>() {
                        public void handleEvent(final ConnectedStreamChannel channel) {
                            latch.countDown();
                        }
                    });
                    channel.getReadSetter().set(new ChannelListener<ConnectedStreamChannel>() {
                        public void handleEvent(final ConnectedStreamChannel channel) {
                            try {
                                channel.read(ByteBuffer.allocate(100));
                                channel.close();
                            } catch (IOException e) {
                                if (e.getMessage().toLowerCase().contains("reset")) {
                                    clientOK.set(true);
                                } else {
                                    throw new RuntimeException(e);
                                }
                            } finally {
                                IoUtils.safeClose(channel);
                            }
                        }
                    });
                    serverLatch.countDown();
                    channel.resumeReads();
                } catch (Throwable t) {
                    log.error("Error occurred on client", t);
                    try {
                        channel.close();
                    } catch (Throwable t2) {
                        log.error("Error occurred on client (close)", t2);
                        latch.countDown();
                        throw new RuntimeException(t);
                    }
                    throw new RuntimeException(t);
                }
            }
        }, new ChannelListener<ConnectedStreamChannel>() {
            public void handleEvent(final ConnectedStreamChannel channel) {
                try {
                    log.info("Server opened");
                    channel.getCloseSetter().set(new ChannelListener<ConnectedStreamChannel>() {
                        public void handleEvent(final ConnectedStreamChannel channel) {
                            latch.countDown();
                        }
                    });
                    serverLatch.await(500L, TimeUnit.MILLISECONDS);
                    log.info("Closing connection...");
                    channel.setOption(Options.CLOSE_ABORT, Boolean.TRUE);
                    channel.close();
                    serverOK.set(true);
                } catch (Throwable t) {
                    log.error("Error occurred on server", t);
                    latch.countDown();
                    throw new RuntimeException(t);
                }
            }
        });
        assertTrue(serverOK.get());
        assertTrue(clientOK.get());
        threadFactory.await();
    }

    public void testAcceptor() throws Exception {
        threadFactory.clear();
        log.info("Test: testAcceptor");
        final CountDownLatch ioLatch = new CountDownLatch(4);
        final CountDownLatch closeLatch = new CountDownLatch(2);
        final AtomicBoolean clientOpened = new AtomicBoolean();
        final AtomicBoolean clientReadOnceOK = new AtomicBoolean();
        final AtomicBoolean clientReadDoneOK = new AtomicBoolean();
        final AtomicBoolean clientReadTooMuch = new AtomicBoolean();
        final AtomicBoolean clientWriteOK = new AtomicBoolean();
        final AtomicBoolean serverOpened = new AtomicBoolean();
        final AtomicBoolean serverReadOnceOK = new AtomicBoolean();
        final AtomicBoolean serverReadDoneOK = new AtomicBoolean();
        final AtomicBoolean serverReadTooMuch = new AtomicBoolean();
        final AtomicBoolean serverWriteOK = new AtomicBoolean();
        final byte[] bytes = "Ummagumma!".getBytes("UTF-8");
        final Xnio xnio = Xnio.getInstance("nio");
        final ReadChannelThread readChannelThread = xnio.createReadChannelThread(threadFactory);
        final ReadChannelThread clientReadChannelThread = xnio.createReadChannelThread(threadFactory);
        final WriteChannelThread writeChannelThread = xnio.createWriteChannelThread(threadFactory);
        final WriteChannelThread clientWriteChannelThread = xnio.createWriteChannelThread(threadFactory);
        final ConnectionChannelThread connectionChannelThread = xnio.createConnectionChannelThread(threadFactory);
        try {
            final FutureResult<InetSocketAddress> futureAddressResult = new FutureResult<InetSocketAddress>();
            final IoFuture<InetSocketAddress> futureAddress = futureAddressResult.getIoFuture();
            final IoFuture<? extends ConnectedStreamChannel> futureConnection = xnio.acceptStream(new InetSocketAddress(Inet4Address.getByAddress(new byte[] { 127, 0, 0, 1 }), 0), connectionChannelThread, clientReadChannelThread, clientWriteChannelThread, new ChannelListener<ConnectedStreamChannel>() {
                private final ByteBuffer inboundBuf = ByteBuffer.allocate(512);
                private int readCnt = 0;
                private final ByteBuffer outboundBuf = ByteBuffer.wrap(bytes);

                public void handleEvent(final ConnectedStreamChannel channel) {
                    channel.getCloseSetter().set(new ChannelListener<ConnectedStreamChannel>() {
                        public void handleEvent(final ConnectedStreamChannel channel) {
                            closeLatch.countDown();
                        }
                    });
                    channel.getReadSetter().set(new ChannelListener<ConnectedStreamChannel>() {
                        public void handleEvent(final ConnectedStreamChannel channel) {
                            try {
                                final int res = channel.read(inboundBuf);
                                if (res == 0) {
                                    channel.resumeReads();
                                } else if (res == -1) {
                                    serverReadDoneOK.set(true);
                                    ioLatch.countDown();
                                    channel.shutdownReads();
                                } else {
                                    final int ttl = readCnt += res;
                                    if (ttl == bytes.length) {
                                        serverReadOnceOK.set(true);
                                    } else if (ttl > bytes.length) {
                                        serverReadTooMuch.set(true);
                                        IoUtils.safeClose(channel);
                                        return;
                                    }
                                    channel.resumeReads();
                                }
                            } catch (IOException e) {
                                log.errorf(e, "Server read failed");
                                IoUtils.safeClose(channel);
                            }
                        }
                    });
                    channel.getWriteSetter().set(new ChannelListener<ConnectedStreamChannel>() {
                        public void handleEvent(final ConnectedStreamChannel channel) {
                            try {
                                channel.write(outboundBuf);
                                if (!outboundBuf.hasRemaining()) {
                                    serverWriteOK.set(true);
                                    Channels.shutdownWritesBlocking(channel);
                                    ioLatch.countDown();
                                }
                            } catch (IOException e) {
                                log.errorf(e, "Server write failed");
                                IoUtils.safeClose(channel);
                            }
                        }
                    });
                    channel.resumeReads();
                    channel.resumeWrites();
                    serverOpened.set(true);
                }
            }, new ChannelListener<BoundChannel>() {
                public void handleEvent(final BoundChannel channel) {
                    futureAddressResult.setResult(channel.getLocalAddress(InetSocketAddress.class));
                }
            }, OptionMap.create(Options.REUSE_ADDRESSES, Boolean.TRUE));
            final InetSocketAddress localAddress = futureAddress.get();
            final IoFuture<? extends ConnectedStreamChannel> ioFuture = xnio.connectStream(localAddress, connectionChannelThread, readChannelThread, writeChannelThread, new ChannelListener<ConnectedStreamChannel>() {
                private final ByteBuffer inboundBuf = ByteBuffer.allocate(512);
                private int readCnt = 0;
                private final ByteBuffer outboundBuf = ByteBuffer.wrap(bytes);

                public void handleEvent(final ConnectedStreamChannel channel) {
                    channel.getCloseSetter().set(new ChannelListener<ConnectedStreamChannel>() {
                        public void handleEvent(final ConnectedStreamChannel channel) {
                            closeLatch.countDown();
                        }
                    });
                    channel.getReadSetter().set(new ChannelListener<ConnectedStreamChannel>() {
                        public void handleEvent(final ConnectedStreamChannel channel) {
                            try {
                                final int res = channel.read(inboundBuf);
                                if (res == 0) {
                                    channel.resumeReads();
                                } else if (res == -1) {
                                    channel.shutdownReads();
                                    clientReadDoneOK.set(true);
                                    ioLatch.countDown();
                                } else {
                                    final int ttl = readCnt += res;
                                    if (ttl == bytes.length) {
                                        clientReadOnceOK.set(true);
                                    } else if (ttl > bytes.length) {
                                        clientReadTooMuch.set(true);
                                        IoUtils.safeClose(channel);
                                        return;
                                    }
                                    channel.resumeReads();
                                }
                            } catch (IOException e) {
                                log.errorf(e, "Client read failed");
                                IoUtils.safeClose(channel);
                            }
                        }
                    });
                    channel.getWriteSetter().set(new ChannelListener<ConnectedStreamChannel>() {
                        public void handleEvent(final ConnectedStreamChannel channel) {
                            try {
                                channel.write(outboundBuf);
                                if (!outboundBuf.hasRemaining()) {
                                    clientWriteOK.set(true);
                                    Channels.shutdownWritesBlocking(channel);
                                    ioLatch.countDown();
                                }
                            } catch (IOException e) {
                                log.errorf(e, "Client write failed");
                                IoUtils.safeClose(channel);
                            }
                        }
                    });
                    channel.resumeReads();
                    channel.resumeWrites();
                    clientOpened.set(true);
                }
            }, null, OptionMap.EMPTY);
            assertTrue("Read timed out", ioLatch.await(500L, TimeUnit.MILLISECONDS));
            assertTrue("Close timed out", closeLatch.await(500L, TimeUnit.MILLISECONDS));
            assertFalse("Client read too much", clientReadTooMuch.get());
            assertTrue("Client read OK", clientReadOnceOK.get());
            assertTrue("Client read done", clientReadDoneOK.get());
            assertTrue("Client write OK", clientWriteOK.get());
            assertFalse("Server read too much", serverReadTooMuch.get());
            assertTrue("Server read OK", serverReadOnceOK.get());
            assertTrue("Server read done", serverReadDoneOK.get());
            assertTrue("Server write OK", serverWriteOK.get());
        } finally {
            readChannelThread.shutdown();
            writeChannelThread.shutdown();
            clientReadChannelThread.shutdown();
            clientWriteChannelThread.shutdown();
            connectionChannelThread.shutdown();
        }
        threadFactory.await();
    }
}
