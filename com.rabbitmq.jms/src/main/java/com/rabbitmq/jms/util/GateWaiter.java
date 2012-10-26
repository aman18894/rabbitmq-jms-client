package com.rabbitmq.jms.util;

import java.util.concurrent.TimeUnit;

/**
 * Hand-crafted gate for pausing multiple threads on entry to a region. Allows waiting threads to be aborted (return
 * with {@link AbortedException}) without using interrupts.
 * <dl>
 * <dt>Design</dt>
 * <dd>The <code>GateWaiter</code> is either <code>OPENED</code>, <code>CLOSED</code> or <code>ABORTED</code>, and the
 * main operations are <code>open()</code>, <code>close()</code>, <code>waitForOpen(<i>timeout</i>)</code> and
 * <code>abort()</code>. The abstract methods <code>onEntry()</code>, <code>onWait()</code> and <code>onAbort()</code>
 * must be defined by an implementing class and are called at appropriate points (described below).</dd>
 * <dd>If the gate is CLOSED and waitForOpen(long timeout) is called, the caller blocks until the gate is OPENED, the
 * timeout expires, or the gate is ABORTED. If the gate is OPENED, either already or at a later time, the call returns
 * after the onEntry() method is called.</dd>
 * </dl>
 */
abstract class GateWaiter {

    /** possible states of the gate */
    private enum GateState { OPENED, CLOSED, ABORTED };

    private Object lock = new Object();
    /*@GuardedBy("lock")*/ private volatile GateState state;

    /**
     * Create a gate, either OPENED or CLOSED. It cannot be created ABORTED.
     * @param opened - <code>true</code> if gate is initially <code>OPENED</code>; <code>false</code> if initially
     *            <code>CLOSED</code>
     */
    public GateWaiter(boolean opened) {
        this.state = (opened ? GateState.OPENED : GateState.CLOSED);
    }

    /**
     * @return <code>true</code> if gate is open, <code>false</code> otherwise
     */
    public final boolean isOpen() {
        synchronized(this.lock){
            return this.state == GateState.OPENED;
        }
    }

    /**
     * Close the gate, provided it is <code>OPENED</code>.
     * @return <code>true</code> if gate <code>CLOSED</code> by this call, <code>false</code> otherwise
     */
    public final boolean close() {
        synchronized(this.lock) {
            if (this.state == GateState.OPENED) {
                this.state = GateState.CLOSED;
                return true; // no need to notify queued threads.
            }
        }
        return false;
    }

    /**
     * Open the gate. Can be done only if gate is <code>CLOSED</code>.
     * @return <code>true</code> if gate <code>OPENED</code> by this call, <code>false</code> otherwise
     */
    public final boolean open() {
        synchronized(this.lock) {
            if (this.state == GateState.CLOSED) {
                this.state = GateState.OPENED;
                this.lock.notifyAll(); // allow current queued threads to pass.
                return true;
            }
        }
        return false;
    }

    /**
     * Called when thread actually waits for the gate to open.
     */
    public abstract void onWait();

    /**
     * Called when thread passes through open gate.
     */
    public abstract void onEntry();

    /**
     * Called when thread is aborted while waiting for open.
     */
    public abstract void onAbort();

    /**
     * Wait (and queue) if gate is <code>CLOSED</code>; or register and return <code>true</code> if gate is
     * <code>OPENED</code> soon enough.
     * <p>Calls <code>onEntry()</code> when gate is <code>OPENED</code> now or later,
     * <code>onAbort()</code> when the gate is <code>ABORTED</code> now or later, and <code>onWait()</code> (once only)
     * if the gate is <code>CLOSED</code> now and we may wait (even if <code>timeoutNanos==0</code>)
     * </p>
     * @param timeoutNanos - time to wait if gate is <code>CLOSED</code>, must be ≥0.
     * @return <code>true</code> if gate is <code>OPENED</code> now or within time limit, <code>false</code> if time
     *         limit expires while waiting
     * @throws InterruptedException if waiting thread is interrupted.
     * @throws AbortedException if gate is <code>ABORTED</code> now or within time limit.
     */
    public final boolean waitForOpen(long timeoutNanos) throws InterruptedException, AbortedException {
        switch (state) {
        case ABORTED:
            this.onAbort();
            throw new AbortedException();
        case OPENED:
            this.onEntry();
            return true;
        case CLOSED:
            this.onWait();
            boolean aborted = false;
            synchronized(this.lock) {
                if (this.state == GateState.CLOSED) { // gate closed -- we are waiting
                    long rem = timeoutNanos;
                    long startTime = System.nanoTime();
                    while ((this.state == GateState.CLOSED) && (rem > 0)) {
                        TimeUnit.NANOSECONDS.timedWait(this.lock, rem);
                        rem = timeoutNanos - (System.nanoTime() - startTime);
                    }
                    // this.state == GateState.OPENED | GateState.ABORTED OR else rem <= 0
                    if (this.state == GateState.OPENED) break;
                    if (this.state == GateState.ABORTED) {
                        aborted = true;
                        break;
                    }
                    return false;  // we timed out
                    // fall through when open or aborted
                }
            }
            if (aborted) { // execute onAbort() out of the synchronised block.
                this.onAbort();
                throw new AbortedException();
            }
        }
        this.onEntry();
        return true;
    }

    /**
     * Abort (cause to abandon wait and throw {@link AbortedException}) all threads waiting on <code>CLOSED</code> gate.
     * @return <code>true</code> if gate is <code>ABORTED</code> by this call; <code>false</code> otherwise
     */
    public final boolean abort() {
        synchronized(this.lock){
            if (this.state != GateState.CLOSED) return false;
            this.state = GateState.ABORTED;
        }
        return true;
    }
}