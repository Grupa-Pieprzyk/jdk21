/*
 * Copyright (c) 2000, 2022, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2021, Azul Systems, Inc. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 *
 */

package sun.jvm.hotspot;

import java.rmi.RemoteException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

import sun.jvm.hotspot.debugger.Debugger;
import sun.jvm.hotspot.debugger.DebuggerException;
import sun.jvm.hotspot.debugger.JVMDebugger;
import sun.jvm.hotspot.debugger.MachineDescription;
import sun.jvm.hotspot.debugger.MachineDescriptionAMD64;
import sun.jvm.hotspot.debugger.MachineDescriptionPPC64;
import sun.jvm.hotspot.debugger.MachineDescriptionAArch64;
import sun.jvm.hotspot.debugger.MachineDescriptionRISCV64;
import sun.jvm.hotspot.debugger.MachineDescriptionIntelX86;
import sun.jvm.hotspot.debugger.NoSuchSymbolException;
import sun.jvm.hotspot.debugger.bsd.BsdDebuggerLocal;
import sun.jvm.hotspot.debugger.linux.LinuxDebuggerLocal;
import sun.jvm.hotspot.debugger.remote.RemoteDebugger;
import sun.jvm.hotspot.debugger.remote.RemoteDebuggerClient;
import sun.jvm.hotspot.debugger.remote.RemoteDebuggerServer;
import sun.jvm.hotspot.debugger.windbg.WindbgDebuggerLocal;
import sun.jvm.hotspot.runtime.VM;
import sun.jvm.hotspot.types.TypeDataBase;
import sun.jvm.hotspot.utilities.PlatformInfo;
import sun.jvm.hotspot.utilities.UnsupportedPlatformException;

/** <P> This class wraps much of the basic functionality and is the
 * highest-level factory for VM data structures. It makes it simple
 * to start up the debugging system. </P>
 *
 * <P> FIXME: especially with the addition of remote debugging, this
 * has turned into a mess; needs rethinking. </P>
 */

public class HotSpotAgent {
    private JVMDebugger debugger;
    private MachineDescription machDesc;
    private TypeDataBase db;

    private String os;
    private String cpu;

    // The system can work in several ways:
    //  - Attaching to local process
    //  - Attaching to local core file
    //  - Connecting to remote debug server
    //  - Starting debug server for process
    //  - Starting debug server for core file

    // These are options for the "client" side of things
    public static final int PROCESS_MODE   = 0;
    public static final int CORE_FILE_MODE = 1;
    public static final int REMOTE_MODE    = 2;
    private int startupMode;

    // This indicates whether we are really starting a server or not
    private boolean isServer;

    // All possible required information for connecting
    private int pid;
    private String javaExecutableName;
    private String coreFileName;
    private String debugServerID;
    private int rmiPort;

    // All needed information for server side
    private String serverID;
    private String serverName;

    private String[] jvmLibNames;

    static void showUsage() {
    }

    public HotSpotAgent() {
        // for non-server add shutdown hook to clean-up debugger in case
        // of forced exit. For remote server, shutdown hook is added by
        // DebugServer.
        //Runtime.getRuntime().addShutdownHook(new java.lang.Thread(
        //new Runnable() {
        //    public void run() {
        //        synchronized (HotSpotAgent.this) {
        //            if (!isServer) {
        //                detach();
        //            }
        //        }
        //    }
        //}));
    }

    //--------------------------------------------------------------------------------
    // Accessors (once the system is set up)
    //

    public synchronized JVMDebugger getDebugger() {
        return debugger;
    }

    public synchronized TypeDataBase getTypeDataBase() {
        return db;
    }

    //--------------------------------------------------------------------------------
    // Client-side operations
    //

    /** This attaches to a process running on the local machine. */
    public synchronized void attach(int processID)
    throws DebuggerException {
        if (debugger != null) {
            throw new DebuggerException("Already attached");
        }
        pid = processID;
        startupMode = PROCESS_MODE;
        isServer = false;
        go();
    }

    /** This opens a core file on the local machine */
    public synchronized void attach(String javaExecutableName, String coreFileName)
    throws DebuggerException {
        if (debugger != null) {
            throw new DebuggerException("Already attached");
        }
        if ((javaExecutableName == null) || (coreFileName == null)) {
            throw new DebuggerException("Both the core file name and Java executable name must be specified");
        }
        this.javaExecutableName = javaExecutableName;
        this.coreFileName = coreFileName;
        startupMode = CORE_FILE_MODE;
        isServer = false;
        go();
    }

    /** This uses a JVMDebugger that is already attached to the core or process */
    public synchronized void attach(JVMDebugger d)
    throws DebuggerException {
        debugger = d;
        isServer = false;
        go();
    }

    /** This attaches to a "debug server" on a remote machine; this
      remote server has already attached to a process or opened a
      core file and is waiting for RMI calls on the Debugger object to
      come in. */
    public synchronized void attach(String remoteServerID)
    throws DebuggerException {
        if (debugger != null) {
            throw new DebuggerException("Already attached to a process");
        }
        if (remoteServerID == null) {
            throw new DebuggerException("Debug server id must be specified");
        }

        debugServerID = remoteServerID;
        startupMode = REMOTE_MODE;
        isServer = false;
        go();
    }

    /** This should only be called by the user on the client machine,
      not the server machine */
    public synchronized boolean detach() throws DebuggerException {
        if (isServer) {
            throw new DebuggerException("Should not call detach() for server configuration");
        }
        return detachInternal();
    }

    //--------------------------------------------------------------------------------
    // Server-side operations
    //

    /** This attaches to a process running on the local machine and
      starts a debug server, allowing remote machines to connect and
      examine this process. Uses specified name to uniquely identify a
      specific debuggee on the server. Allows to specify the port number
      to which the RMI connector is bound. If not specified a random
      available port is used. */
    public synchronized void startServer(int processID,
                                         String serverID,
                                         String serverName,
                                         int rmiPort) {
        if (debugger != null) {
            throw new DebuggerException("Already attached");
        }
        pid = processID;
        startupMode = PROCESS_MODE;
        isServer = true;
        this.serverID = serverID;
        this.serverName = serverName;
        this.rmiPort = rmiPort;
        go();
    }

    /** This attaches to a process running on the local machine and
     starts a debug server, allowing remote machines to connect and
     examine this process. Uses specified name to uniquely identify a
     specific debuggee on the server */
    public synchronized void startServer(int processID, String serverID, String serverName) {
        startServer(processID, serverID, serverName, 0);
    }

    /** This attaches to a process running on the local machine and
      starts a debug server, allowing remote machines to connect and
      examine this process. */
    public synchronized void startServer(int processID)
    throws DebuggerException {
        startServer(processID, null, null);
    }

    /** This opens a core file on the local machine and starts a debug
      server, allowing remote machines to connect and examine this
      core file. Uses supplied uniqueID to uniquely identify a specific
      debuggee. Allows to specify the port number to which the RMI connector
      is bound. If not specified a random available port is used.  */
    public synchronized void startServer(String javaExecutableName,
                                         String coreFileName,
                                         String serverID,
                                         String serverName,
                                         int rmiPort) {
        if (debugger != null) {
            throw new DebuggerException("Already attached");
        }
        if ((javaExecutableName == null) || (coreFileName == null)) {
            throw new DebuggerException("Both the core file name and Java executable name must be specified");
        }
        this.javaExecutableName = javaExecutableName;
        this.coreFileName = coreFileName;
        startupMode = CORE_FILE_MODE;
        isServer = true;
        this.serverID = serverID;
        this.serverName = serverName;
        this.rmiPort = rmiPort;
    }

    /** This opens a core file on the local machine and starts a debug
     server, allowing remote machines to connect and examine this
     core file. Uses supplied uniqueID to uniquely identify a specific
     debuggee */
    public synchronized void startServer(String javaExecutableName,
                                         String coreFileName,
                                         String serverID,
                                         String serverName) {
        startServer(javaExecutableName, coreFileName, serverID, serverName, 0);
    }

    /** This opens a core file on the local machine and starts a debug
      server, allowing remote machines to connect and examine this
      core file. */
    public synchronized void startServer(String javaExecutableName, String coreFileName)
    throws DebuggerException {
        startServer(javaExecutableName, coreFileName, null, null);
    }

    /** This may only be called on the server side after startServer()
      has been called */
    public synchronized boolean shutdownServer() throws DebuggerException {
        if (!isServer) {
            throw new DebuggerException("Should not call shutdownServer() for client configuration");
        }
        return detachInternal();
    }


    //--------------------------------------------------------------------------------
    // Internals only below this point
    //

    private boolean detachInternal() {
        if (debugger == null) {
            return false;
        }
        boolean retval = true;
        if (!isServer) {
            VM.shutdown();
        }
        // We must not call detach() if we are a client and are connected
        // to a remote debugger
        Debugger dbg = null;
        DebuggerException ex = null;
        if (isServer) {
            try {
                RMIHelper.unbind(serverID, serverName);
            }
            catch (DebuggerException de) {
                ex = de;
            }
            dbg = debugger;
        } else {
            if (startupMode != REMOTE_MODE) {
                dbg = debugger;
            }
        }
        if (dbg != null) {
            retval = dbg.detach();
        }

        debugger = null;
        machDesc = null;
        db = null;
        if (ex != null) {
            throw(ex);
        }
        return retval;
    }

    private void go() {

    }

    private void setupDebugger() {

    }

    private void setupVM() {
        
    }

    //--------------------------------------------------------------------------------
    // OS-specific debugger setup/connect routines
    //

    // Use the existing JVMDebugger, as passed to our constructor.
    // Retrieve os and cpu from that debugger, not the current platform.
    private void setupDebuggerExisting() {

        os = debugger.getOS();
        cpu = debugger.getCPU();
        setupJVMLibNames(os);
        machDesc = debugger.getMachineDescription();
    }

    // Given a classname, load an alternate implementation of JVMDebugger.
    private void setupDebuggerAlternate(String alternateName) {

    }

    private void connectRemoteDebugger() throws DebuggerException {
        RemoteDebugger remote =
        (RemoteDebugger) RMIHelper.lookup(debugServerID);
        debugger = new RemoteDebuggerClient(remote);
        machDesc = debugger.getMachineDescription();
        os = debugger.getOS();
        setupJVMLibNames(os);
        cpu = debugger.getCPU();
    }

    private void setupJVMLibNames(String os) {
        if (os.equals("win32")) {
            setupJVMLibNamesWin32();
        } else if (os.equals("linux")) {
            setupJVMLibNamesLinux();
        } else if (os.equals("bsd")) {
            setupJVMLibNamesBsd();
        } else if (os.equals("darwin")) {
            setupJVMLibNamesDarwin();
        } else {
            throw new RuntimeException("Unknown OS type");
        }
    }

    //
    // Win32
    //

    private void setupDebuggerWin32() {
       
    }

    private void setupJVMLibNamesWin32() {
        jvmLibNames = new String[] { "jvm.dll" };
    }

    //
    // Linux
    //

    private void setupDebuggerLinux() {
       
    }

    private void setupJVMLibNamesLinux() {
        jvmLibNames = new String[] { "libjvm.so" };
    }

    //
    // BSD
    //

    private void setupDebuggerBsd() {
        
    }

    private void setupJVMLibNamesBsd() {
        jvmLibNames = new String[] { "libjvm.so" };
    }

    //
    // Darwin
    //

    private void setupDebuggerDarwin() {
       
    }

    private void setupJVMLibNamesDarwin() {
        jvmLibNames = new String[] { "libjvm.dylib" };
    }

    /** Convenience routine which should be called by per-platform
      debugger setup. Should not be called when startupMode is
      REMOTE_MODE. */
    private void attachDebugger() {
       
    }

    public int getStartupMode() {
        return startupMode;
    }
}
