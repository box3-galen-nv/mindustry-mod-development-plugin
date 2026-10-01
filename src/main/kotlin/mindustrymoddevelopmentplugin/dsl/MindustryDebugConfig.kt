package mindustrymoddevelopmentplugin.dsl

import org.gradle.api.provider.Property

/**
 * Logging and debugging of the launched game (`mindustryModRoot { debug { ... } }`).
 *
 * Everything here affects `runMindustry` only: the log files it writes and the JDWP socket it may
 * open. The two switches are *defaults* for the build — a single run overrides them with
 * `-PmindustryDebug=...` / `-PmindustryDebugSuspend=...`, and the command line wins, so a CI job can
 * force debugging off for a project that turns it on here.
 */
abstract class MindustryDebugConfig {

    /**
     * Maximum number of `runMindustry` log files to keep in `build/logger/`; older ones are deleted.
     * Default [DEFAULT_MAX_LOG_FILES].
     */
    abstract val maxLogFiles: Property<Int>

    /**
     * Whether `runMindustry` also writes the game output to `build/logger/log_*.log` files. When
     * `false` the output goes to the console only and no log file is written.
     * Default [DEFAULT_ENABLE_RUN_LOGGING].
     */
    abstract val enableRunLogging: Property<Boolean>

    /**
     * Whether a run opens a JDWP debug socket for an IDE. Default [DEFAULT_ENABLE_DEBUG].
     *
     * Debugging is normally decided per run instead of here: the generated IDEA run configuration
     * passes `-PmindustryDebug=true`, and that property wins over this value in both directions, so
     * `-PmindustryDebug=false` turns it off even for a project that enables it here.
     *
     * The socket is bound to `localhost`, never to every interface: an unauthenticated JDWP port is
     * arbitrary code execution for anyone who can reach it. An occupied [debugPort] also makes the
     * game JVM abort before it starts.
     */
    abstract val enableDebug: Property<Boolean>

    /**
     * Port of the JDWP debug socket, and the port the generated attach configuration points at.
     * Default [DEFAULT_DEBUG_PORT], which is what IDEA's Remote JVM Debug template expects.
     */
    abstract val debugPort: Property<Int>

    /**
     * Whether a debugged run waits for the debugger before executing any code, so breakpoints in
     * startup code (content registration) are hit as well. Default [DEFAULT_DEBUG_SUSPEND].
     *
     * `-PmindustryDebugSuspend=true|false` overrides it for one run; without debugging it does
     * nothing, so a run never waits for a debugger that cannot attach.
     */
    abstract val debugSuspend: Property<Boolean>

    init {
        maxLogFiles.convention(DEFAULT_MAX_LOG_FILES)
        enableRunLogging.convention(DEFAULT_ENABLE_RUN_LOGGING)
        enableDebug.convention(DEFAULT_ENABLE_DEBUG)
        debugPort.convention(DEFAULT_DEBUG_PORT)
        debugSuspend.convention(DEFAULT_DEBUG_SUSPEND)
    }

    companion object {
        // Every default is a named constant referenced by init { }, so the block above never
        // mixes inline literals with named defaults.

        /** Default value of [maxLogFiles]. */
        const val DEFAULT_MAX_LOG_FILES = 25

        /** Default value of [enableRunLogging]. */
        const val DEFAULT_ENABLE_RUN_LOGGING = true

        /** Default value of [enableDebug]. */
        const val DEFAULT_ENABLE_DEBUG = false

        /** Default value of [debugPort]. */
        const val DEFAULT_DEBUG_PORT = 5005

        /** Default value of [debugSuspend]. */
        const val DEFAULT_DEBUG_SUSPEND = false
    }
}
