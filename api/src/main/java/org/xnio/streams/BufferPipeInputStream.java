/*
 * JBoss, Home of Professional Open Source
 * Copyright 2010, JBoss Inc., and individual contributors as indicated
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

package org.xnio.streams;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.Semaphore;
import org.xnio.Buffers;
import org.xnio.Pooled;

/**
 * An {@code InputStream} implementation which is populated asynchronously with {@link ByteBuffer} instances.
 */
public class BufferPipeInputStream extends InputStream {
    private final Queue<Pooled<ByteBuffer>> queue;
    private final InputHandler inputHandler;

    // protected by "this"
    private boolean eof;
    private IOException failure;

    /**
     * Construct a new instance.  The given {@code inputHandler} will
     * be invoked after each buffer is fully read and when the stream is closed.
     *
     * @param inputHandler the input events handler
     */
    public BufferPipeInputStream(final InputHandler inputHandler) {
        this.inputHandler = inputHandler;
        queue = new ArrayDeque<Pooled<ByteBuffer>>();
    }

    /**
     * Push a buffer into the queue.  There is no mechanism to limit the number of pushed buffers; if such a mechanism
     * is desired, it must be implemented externally, for example maybe using a {@link Semaphore}.
     *
     * @param buffer the buffer from which more data should be read
     */
    public void push(final ByteBuffer buffer) {
        synchronized (this) {
            if (!eof && failure == null) {
                queue.add(Buffers.pooledWrapper(buffer));
                notifyAll();
            }
        }
    }

    /**
     * Push a buffer into the queue.  There is no mechanism to limit the number of pushed buffers; if such a mechanism
     * is desired, it must be implemented externally, for example maybe using a {@link Semaphore}.
     *
     * @param pooledBuffer the buffer from which more data should be read
     */
    public void push(final Pooled<ByteBuffer> pooledBuffer) {
        synchronized (this) {
            if (!eof && failure == null) {
                queue.add(pooledBuffer);
                notifyAll();
            } else {
                pooledBuffer.free();
            }
        }
    }

    /**
     * Push the EOF condition into the queue.  After this method is called, no further buffers may be pushed into this
     * instance.
     */
    public void pushEof() {
        synchronized (this) {
            eof = true;
            notifyAll();
        }
    }

    /**
     * Push an exception condition into the queue.  After this method is called, no further buffers may be pushed into this
     * instance.
     *
     * @param e the exception to push
     */
    public void pushException(IOException e) {
        synchronized (this) {
            if (! eof) {
                failure = e;
                notifyAll();
            }
        }
    }

    /** {@inheritDoc} */
    public int read() throws IOException {
        final Queue<Pooled<ByteBuffer>> queue = this.queue;
        synchronized (this) {
            while (queue.isEmpty()) {
                if (eof) {
                    return -1;
                }
                checkFailure();
                try {
                    wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException("Interrupted on read()");
                }
            }
            final Pooled<ByteBuffer> entry = queue.peek();
            final ByteBuffer buf = entry.getResource();
            final int v = buf.get() & 0xff;
            if (buf.remaining() == 0) {
                entry.free();
                queue.poll();
                try {
                    inputHandler.acknowledge();
                } catch (IOException e) {
                    // no operation!
                }
            }
            return v;
        }
    }

    private void clearQueue() {
        synchronized (this) {
            Pooled<ByteBuffer> entry;
            while ((entry = queue.poll()) != null) {
                entry.free();
            }
        }
    }

    /** {@inheritDoc} */
    public int read(final byte[] b, int off, int len) throws IOException {
        if (len == 0) {
            return 0;
        }
        final Queue<Pooled<ByteBuffer>> queue = this.queue;
        synchronized (this) {
            while (queue.isEmpty()) {
                if (eof) {
                    return -1;
                }
                checkFailure();
                try {
                    wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException("Interrupted on read()");
                }
            }
            int total = 0;
            while (len > 0) {
                final Pooled<ByteBuffer> entry = queue.peek();
                if (entry == null) {
                    break;
                }
                final ByteBuffer buffer = entry.getResource();
                final int byteCnt = Math.min(buffer.remaining(), len);
                buffer.get(b, off, byteCnt);
                off += byteCnt;
                total += byteCnt;
                len -= byteCnt;
                if (buffer.remaining() == 0) {
                    entry.free();
                    queue.poll();
                    try {
                        inputHandler.acknowledge();
                    } catch (IOException e) {
                        // no operation!
                    }
                }
            }
            return total;
        }
    }

    /** {@inheritDoc} */
    public int available() throws IOException {
        synchronized (this) {
            int total = 0;
            for (Pooled<ByteBuffer> entry : queue) {
                total += entry.getResource().remaining();
                if (total < 0) {
                    return Integer.MAX_VALUE;
                }
            }
            return total;
        }
    }

    public long skip(long qty) throws IOException {
        final Queue<Pooled<ByteBuffer>> queue = this.queue;
        synchronized (this) {
            while (queue.isEmpty()) {
                if (eof) {
                    return 0L;
                }
                checkFailure();
                try {
                    wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException("Interrupted on read()");
                }
            }
            long skipped = 0L;
            while (qty > 0L) {
                final Pooled<ByteBuffer> entry = queue.peek();
                if (entry == null) {
                    break;
                }
                final ByteBuffer buffer = entry.getResource();
                final int byteCnt = Math.min(buffer.remaining(), (int) Math.max((long)Integer.MAX_VALUE, qty));
                buffer.position(buffer.position() + byteCnt);
                skipped += byteCnt;
                qty -= byteCnt;
                if (buffer.remaining() == 0) {
                    queue.poll();
                    entry.free();
                    try {
                        inputHandler.acknowledge();
                    } catch (IOException e) {
                        // no operation!
                    }
                }
            }
            return skipped;
        }
    }

    /** {@inheritDoc} */
    public void close() throws IOException {
        synchronized (this) {
            if (! eof) {
                clearQueue();
                eof = true;
                notifyAll();
                inputHandler.close();
            }
        }
    }

    private void checkFailure() throws IOException {
        final IOException failure = this.failure;
        if (failure != null) {
            failure.fillInStackTrace();
            try {
                throw failure;
            } finally {
                eof = true;
                clearQueue();
                notifyAll();
                this.failure = null;
            }
        }
    }

    /**
     * A handler for events relating to the consumption of data from a {@link BufferPipeInputStream} instance.
     */
    public interface InputHandler extends Closeable {

        /**
         * Acknowledges the successful processing of an input buffer.  Though this method may throw an exception,
         * it is not acted upon.
         *
         * @throws IOException if an I/O error occurs sending the acknowledgement
         */
        void acknowledge() throws IOException;

        /**
         * Signifies that the user of the enclosing {@link BufferPipeInputStream} has called the {@code close()} method
         * explicitly.  Any thrown exception is propagated up to the caller of {@link BufferPipeInputStream#close() NioByteInput.close()}.
         *
         * @throws IOException if an I/O error occurs
         */
        void close() throws IOException;
    }
}
