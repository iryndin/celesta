package ru.curs.celesta.dbutils;

import ru.curs.celesta.*;
import ru.curs.celesta.dbutils.adaptors.DBAdaptor;
import ru.curs.celesta.syscursors.CalllogCursor;

/**
 * Call profiling manager.
 */
public final class ProfilingManager implements IProfiler {

    private final Celesta celesta;
    private final DBAdaptor dbAdaptor;
    private boolean profilemode = false;


    public ProfilingManager(Celesta celesta, DBAdaptor dbAdaptor) {
        this.celesta = celesta;
        this.dbAdaptor = dbAdaptor;
    }

    /**
     * Logs information on the call to the profiler.
     *
     * @param context  call context
     */
    public void logCall(CallContext context) {
        if (this.profilemode && !NO_LOG.equals(context.getProcName())) {
            try (CallContext sysContext = new SystemCallContext(celesta)) {
                CalllogCursor clc = new CalllogCursor(sysContext);
                clc.setProcname(context.getProcName());
                clc.setUserid(context.getUserId());

                clc.setStarttime(context.getStartTime());
                clc.setDuration((int) (context.getDurationNs() / 1000));
                clc.insert();
            }
        }
    }

    /**
     * Whether the profiling mode is on (is the procedures call time logged to
     * <em>calllog</em> table.
     *
     * @return
     */
    public boolean isProfilemode() {
        return this.profilemode;
    }

    /**
     * Sets the profiling mode.
     *
     * @param profilemode  profiling mode flag ({@code true} - on, {@code false} - off)
     */
    public void setProfilemode(boolean profilemode) {
        this.profilemode = profilemode;
    }

}
