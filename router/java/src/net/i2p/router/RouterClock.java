package net.i2p.router;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

import net.i2p.data.DataHelper;
import net.i2p.router.time.RouterTimestamper;
import net.i2p.time.Timestamper;
import net.i2p.util.Clock;
import net.i2p.util.Log;

/**
 * Alternate location for determining the time which takes into account an offset.
 * This offset will ideally be periodically updated so as to serve as the difference
 * between the local computer's current time and the time as known by some reference
 * (such as an NTP synchronized clock).
 *
 * RouterClock is a subclass of Clock with access to router transports.
 * Configuration permitting, it will block clock offset changes
 * which would increase peer clock skew.
 */
public class RouterClock extends Clock {

    /**
     *  How often we will slew the clock
     *  i.e. ppm = 1000000/MAX_SLEW
     *  We should be able to slew really fast,
     *  this is probably a lot faster than what NTP does
     *  1/50 is 12s in a 10m tunnel lifetime, that should be fine.
     *  All of this is @since 0.7.12
     */
    private static final long MAX_SLEW = 50;
    public static final int DEFAULT_STRATUM = 8;
    private static final int WORST_STRATUM = 16;

    /** the max NTP Timestamper delay is 30m right now, make this longer than that */
    private static final long MIN_DELAY_FOR_WORSE_STRATUM = 45*60*1000;
    private volatile long _desiredOffset;
    private volatile long _lastSlewed;
    /** use system time for this */
    private long _lastChanged;
    private int _lastStratum;
    private final Timestamper _timeStamper;

    /**
     *  If the system clock shifts by this much,
     *  call the callback, we probably need a soft restart.
     *  @since 0.8.8
     */
    private static final long MASSIVE_SHIFT_FORWARD = 150*1000;
    private static final long MASSIVE_SHIFT_BACKWARD = 61*1000;

    private final Set<ClockShiftListener> _shiftListeners;
    private volatile long _lastShiftNanos;

    public RouterClock(RouterContext context) {
        super(context);
        _lastStratum = WORST_STRATUM;
        _lastSlewed = System.currentTimeMillis();
        _shiftListeners = new CopyOnWriteArraySet();
        _lastShiftNanos = System.nanoTime();
        _timeStamper = new RouterTimestamper(context, this);
    }

    /**
     *  The RouterTimestamper
     */
    @Override
    public Timestamper getTimestamper() { return _timeStamper; }

    /**
     * Specify how far away from the "correct" time the computer is - a positive
     * value means that the system time is slow, while a negative value means the system time is fast.
     *
     * @param offsetMs the delta from System.currentTimeMillis() (NOT the delta from now())
     */
    @Override
    public void setOffset(long offsetMs, boolean force) {
         setOffset(offsetMs, force, DEFAULT_STRATUM);
    }

    /**
     * @since 0.7.12
     * @param offsetMs the delta from System.currentTimeMillis() (NOT the delta from now())
     */
    private void setOffset(long offsetMs, int stratum) {
         setOffset(offsetMs, false, stratum);
    }

    /**
     * @since 0.7.12
     * @param offsetMs the delta from System.currentTimeMillis() (NOT the delta from now())
     */
    private synchronized void setOffset(long offsetMs, boolean force, int stratum) {
        long delta = offsetMs - _offset;
        if (!force) {
            if ((offsetMs > MAX_OFFSET) || (offsetMs < 0 - MAX_OFFSET)) {
                getLog().error("Maximum offset shift exceeded [" + offsetMs + "], NOT HONORING IT");
                return;
            }
            
            // only allow substantial modifications before the first 10 minutes
            if (_alreadyChanged && (System.currentTimeMillis() - _startedOn > 10 * 60 * 1000)) {
                if ( (delta > MAX_LIVE_OFFSET) || (delta < 0 - MAX_LIVE_OFFSET) ) {
                    getLog().log(Log.CRIT, "The clock has already been updated, but you want to change it by "
                                           + delta + " to " + offsetMs + "?  Did something break?");
                    return;
                }
            }
            
            // let's be perfect
            if (delta == 0) {
                getLog().debug("Not changing offset, delta=0");
                _alreadyChanged = true;
                return;
            }

            // only listen to a worse stratum if it's been a while
            if (_alreadyChanged && stratum > _lastStratum &&
                System.currentTimeMillis() - _lastChanged < MIN_DELAY_FOR_WORSE_STRATUM) {
                getLog().debug("Ignoring update from a stratum " + stratum +
                              " clock, we recently had an update from a stratum " + _lastStratum + " clock");
                return;
            }
            
            // If so configured, check sanity of proposed clock offset
            if (_context.getBooleanPropertyDefaultTrue("router.clockOffsetSanityCheck") &&
                _alreadyChanged) {

                // Try calculating peer clock skew
                long currentPeerClockSkew = ((RouterContext)_context).commSystem().getFramedAveragePeerClockSkew(50);

                    // Predict the effect of applying the proposed clock offset
                    long predictedPeerClockSkew = currentPeerClockSkew + delta;

                    // Fail sanity check if applying the offset would increase peer clock skew
                    if ((Math.abs(predictedPeerClockSkew) > (Math.abs(currentPeerClockSkew) + 5*1000)) ||
                        (Math.abs(predictedPeerClockSkew) > 20*1000)) {

                        getLog().error("Ignoring clock offset " + offsetMs + "ms (current " + _offset +
                                       "ms) since it would increase peer clock skew from " + currentPeerClockSkew +
                                       "ms to " + predictedPeerClockSkew + "ms. Bad time server?");
                        return;
                    } else {
                        getLog().debug("Approving clock offset " + offsetMs + "ms (current " + _offset +
                                       "ms) since it would decrease peer clock skew from " + currentPeerClockSkew +
                                       "ms to " + predictedPeerClockSkew + "ms.");
                    }
            } // check sanity
        }

        // In first minute, allow a lower (better) stratum to do a step adjustment after
        // a previous step adjustment.
        // This allows NTP to trump a peer offset after a soft restart
        if (_alreadyChanged &&
            (stratum >= _lastStratum || _startedOn - System.currentTimeMillis() > 60*1000)) {
            // Update the target offset, slewing will take care of the rest
            if (delta > 15*1000)
                getLog().logAlways(Log.WARN, "Warning - Updating target clock offset to " + offsetMs + "ms from " + _offset + "ms, Stratum " + stratum);
            else if (getLog().shouldLog(Log.INFO))
                getLog().info("Updating target clock offset to " + offsetMs + "ms from " + _offset + "ms, Stratum " + stratum);
            
            if (!_statCreated) {
                _context.statManager().createRequiredRateStat("clock.skew", "Clock step adjustment (ms)", "Clock", new long[] { 10*60*1000, 3*60*60*1000, 24*60*60*60 });
                _statCreated = true;
            }
            _context.statManager().addRateData("clock.skew", delta, 0);
            _desiredOffset = offsetMs;
        } else {
            getLog().log(Log.INFO, "Initializing clock offset to " + offsetMs + "ms, Stratum " + stratum);
            _alreadyChanged = true;
            _offset = offsetMs;
            _desiredOffset = offsetMs;
            // this is used by the JobQueue
            fireOffsetChanged(delta);
        }
        _lastChanged = System.currentTimeMillis();
        _lastStratum = stratum;

    }

    /**
     *  @param stratum used to determine whether we should ignore
     *  @since 0.7.12
     */
    @Override
    public void setNow(long realTime, int stratum) {
        long diff = realTime - System.currentTimeMillis();
        setOffset(diff, stratum);
    }

    /**
     * Retrieve the current time synchronized with whatever reference clock is in use.
     * Do really simple clock slewing, like NTP but without jitter prevention.
     * Slew the clock toward the desired offset, but only up to a maximum slew rate,
     * and never let the clock go backwards because of slewing.
     * 
     * Take care to only access the volatile variables once for speed and to
     * avoid having another thread change them
     *
     * This is called about a zillion times a second, so we can do the slewing right
     * here rather than in some separate thread to keep it simple.
     * Avoiding backwards clocks when updating in a thread would be hard too.
     */
    @Override
    public long now() {
        long systemNow = System.currentTimeMillis();
        // copy the global, so two threads don't both increment or decrement _offset
        long offset = _offset;
        long sinceLastSlewed = systemNow - _lastSlewed;
        if (sinceLastSlewed >= MASSIVE_SHIFT_FORWARD ||
            sinceLastSlewed <= 0 - MASSIVE_SHIFT_BACKWARD) {
            _lastSlewed = systemNow;
            notifyMassive(sinceLastSlewed);
        } else if (sinceLastSlewed >= MAX_SLEW) {
            // copy the global
            long desiredOffset = _desiredOffset;
            if (desiredOffset > offset) {
                // slew forward
                _offset = ++offset;
            } else if (desiredOffset < offset) {
                // slew backward, but don't let the clock go backward
                // this should be the first call since systemNow
                // was greater than lastSled + MAX_SLEW, i.e. different
                // from the last systemNow, thus we won't let the clock go backward,
                // no need to track when we were last called.
                _offset = --offset;
            }
            _lastSlewed = systemNow;
        }
        return offset + systemNow;
    }

    /*
     *  A large system clock shift happened. Tell people about it.
     *
     *  @since 0.8.8
     */
    private synchronized void notifyMassive(long shift) {
        long nowNanos = System.nanoTime();
        // try to prevent dups, not guaranteed
        // nanoTime() isn't guaranteed to be monotonic either :(
        if (nowNanos < _lastShiftNanos + MASSIVE_SHIFT_FORWARD)
            return;
        _lastShiftNanos = nowNanos;

        // reset these so the offset can be reset by the timestamper again
        _startedOn = System.currentTimeMillis();
        _alreadyChanged = false;
        getTimestamper().timestampNow();

        if (shift > 0)
            getLog().log(Log.CRIT, "Large clock shift forward by " + DataHelper.formatDuration(shift));
        else
            getLog().log(Log.CRIT, "Large clock shift backward by " + DataHelper.formatDuration(0 - shift));

        for (ClockShiftListener lsnr : _shiftListeners) {
            lsnr.clockShift(shift);
        }
    }

    /*
     *  Get notified of massive System clock shifts, positive or negative -
     *  generally a minute or more.
     *  The adjusted (offset) clock changes by the same amount.
     *  The offset itself did not change.
     *  Warning - duplicate notifications may occur.
     *
     *  @since 0.8.8
     */
    public void addShiftListener(ClockShiftListener lsnr) {
            _shiftListeners.add(lsnr);
    }

    /*
     *  @since 0.8.8
     */
    public void removeShiftListener(ClockShiftListener lsnr) {
            _shiftListeners.remove(lsnr);
    }

    /*
     *  @since 0.8.8
     */
    public interface ClockShiftListener {

        /**
         *  @param delta The system clock and adjusted clock just changed by this much,
         *               in milliseconds (approximately)
         */
        public void clockShift(long delta);
    }

    /*
     *  How far we still have to slew, for diagnostics
     *  @since 0.7.12
     *  @deprecated for debugging only
     */
    public long getDeltaOffset() {
        return _desiredOffset - _offset;
    }
}
