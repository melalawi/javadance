/*
 * Copyright (C) 2006-2010 Alfresco Software Limited.
 * Copyright (C) 2018 GK Spencer
 *
 * This file is part of Alfresco
 *
 * Alfresco is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Alfresco is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Alfresco. If not, see <http://www.gnu.org/licenses/>.
 */

package org.filesys.app;

import com.sun.jna.Platform;
import org.filesys.debug.Debug;
import org.filesys.debug.DebugConfigSection;
import org.filesys.ftp.FTPConfigSection;
import org.filesys.netbios.server.NetBIOSNameServer;
import org.filesys.oncrpc.nfs.NFSConfigSection;
import org.filesys.server.NetworkServer;
import org.filesys.server.ServerListener;
import org.filesys.server.config.ServerConfiguration;
import org.filesys.smb.SMBErrorText;
import org.filesys.smb.SMBStatus;
import org.filesys.smb.server.SMBConfigSection;
import org.filesys.smb.server.SMBServer;
import org.filesys.smb.util.DriveMapping;
import org.filesys.smb.util.DriveMappingList;
import org.filesys.util.ConsoleIO;
import org.filesys.util.win32.Win32Utils;
import org.ps2.ui.ClientLogger;

import java.io.File;
import java.io.PrintStream;
import java.lang.reflect.Constructor;
import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * File Server Application
 * 
 * @author gkspencer
 */
public class FileServer implements ServerListener {

	// Constants
	//
	// Checkpoints
	public enum CheckPoint {
		Starting,
		ConfigLoading,
		ConfigLoaded,
		CheckIPAddress,
		CreateSMBServer,
		CreateFTPServer,
		CreateNFSServer,
		ServerStart,
		ServerStarted,
		Running,
		ServerStop,
		ServerStopped,
		Finished
	}

	// Default configuration file name
	protected static final String DEFAULT_CONFIGFILENAME = "fileserver.xml";

	// Flag to enable/disable local IP address checking
	private static final boolean CheckLocalIPAddress = false;

	// Server shutdown flag
	protected static boolean m_shutdown = false;

	// Server restart flag
	protected static boolean m_restart = false;

	// Flag to enable user to shutdown the server via the console
	protected static boolean m_allowShutViaConsole = true;

	// Flag to control output of a stacktrace if an error occurs
	protected static boolean m_dumpStackOnError = true;

	// Server configuration
	private ServerConfiguration m_srvConfig;

	// Main file server thread
	private Thread m_mainThread;

	private static ClientLogger clientLogger;

	/**
	 * Shutdown hook thread
	 */
	protected class FileServerShutdownHook extends Thread {

	    // The file server
        private FileServer m_fileServer;

        /**
         * Constructor
         *
         * @param fileServer FileServer
         */
        public FileServerShutdownHook( FileServer fileServer) {
            m_fileServer = fileServer;
        }

		/**
		 * Run the shutdown hook
		 */
		public void run() {

		    // Set the file server to shut down
            if ( m_fileServer != null && m_fileServer.isRunning()) {
                m_fileServer.requestShutdown();;
            }
		}
	};

	/**
	 * Start the file server
	 *
	 */
	public static void main(final ClientLogger logger) {
		clientLogger = logger;
		// Create the main file server object
		FileServer fileServer = new FileServer();

		// Loop until shutdown
		while (m_shutdown == false) {

			// Start the server
			fileServer.start(new String[0]);

			// DEBUG
			if ( Debug.EnableInfo && m_restart == true) {
				Debug.println("Restarting server ...");
				Debug.println("--------------------------------------------------");
			}
		}
	}

	/**
	 * Class constructor
	 */
	protected FileServer() {
	}

	/**
	 * Set/clear the allow shutdown via console flag
	 * 
	 * @param consoleShut boolean
	 */
	public static final void setAllowConsoleShutdown(boolean consoleShut) {
		m_allowShutViaConsole = consoleShut;
	}

	/**
	 * Enable/disable exception stack dumps
	 * 
	 * @param ena boolean
	 */
	protected final void enableExceptionStackDump(boolean ena) {
		m_dumpStackOnError = ena;
	}

	/**
	 * Start the file server
	 * 
	 * @param args String[]
	 */
	protected void start(String[] args) {

		// Command line parameter should specify the configuration file
		PrintStream out = createOutputStream();

		// Clear the shutdown/restart flags
		m_shutdown = true;
		m_restart = false;

		// Checkpoint - server starting
		checkPoint(out, CheckPoint.Starting);

		// Load the configuration
		m_srvConfig = null;

		try {

			// Checkpoint - configuration loading
			checkPoint(out, CheckPoint.ConfigLoading);

			// Load the configuration
			m_srvConfig = loadConfiguration(out, args);

			// Checkpoint - configuration loaded
			checkPoint(out, CheckPoint.ConfigLoaded);
		}
		catch (Exception ex) {

			// Failed to load server configuration
			checkPointError(out, CheckPoint.ConfigLoading, ex);
			return;
		}

		// Check if the local IP address returns a valid value, '127.0.0.1' indicates a mis-configuration in the hosts
		// file
		if ( CheckLocalIPAddress) {

			try {

				// Checkpoint - check IP address
				checkPoint(out, CheckPoint.CheckIPAddress);

				// Get the local address
				String localAddr = InetAddress.getLocalHost().getHostAddress();
				if ( localAddr.equals("127.0.0.1")) {
					out.println("%% Local IP address resolves to 127.0.0.1, this may be caused by a mis-configured hosts file");
					return;
				}
			}
			catch (UnknownHostException ex) {

				// Failed to get local host IP address details
				checkPointError(out, CheckPoint.CheckIPAddress, ex);
				return;
			}
		}

		// NetBIOS name server, SMB, FTP and NFS servers
		try {

			// Create the SMB server and NetBIOS name server, if enabled
			if ( m_srvConfig.hasConfigSection(SMBConfigSection.SectionName)) {

				// Checkpoint - create SMB server
				checkPoint(out, CheckPoint.CreateSMBServer);

				// Get the SMB server configuration
				SMBConfigSection smbConfig = (SMBConfigSection) m_srvConfig.getConfigSection(SMBConfigSection.SectionName);

				// Create the NetBIOS name server if NetBIOS SMB is enabled
				if ( smbConfig.hasNetBIOSSMB())
					m_srvConfig.addServer(createNetBIOSServer(m_srvConfig));

				// Create the SMB server
				m_srvConfig.addServer(createSMBServer(m_srvConfig));
			}

			// Create the FTP server, if enabled
			if ( m_srvConfig.hasConfigSection(FTPConfigSection.SectionName)) {

				// Checkpoint - create FTP server
				checkPoint(out, CheckPoint.CreateFTPServer);

				// Create the FTP server
				m_srvConfig.addServer(createFTPServer(m_srvConfig));
			}

			// Create the NFS server and mount server, if enabled
			if ( m_srvConfig.hasConfigSection(NFSConfigSection.SectionName)) {

				// Checkpoint - create NFS server
				checkPoint(out, CheckPoint.CreateNFSServer);

				// Get the NFS server configuration
				NFSConfigSection nfsConfig = (NFSConfigSection) m_srvConfig.getConfigSection(NFSConfigSection.SectionName);

				// Check if the port mapper is enabled
				if ( nfsConfig.hasNFSPortMapper())
					m_srvConfig.addServer(createNFSPortMapper(m_srvConfig));

				// Create the mount server
				m_srvConfig.addServer(createNFSMountServer(m_srvConfig));

				// Create the NFS server
				m_srvConfig.addServer(createNFSServer(m_srvConfig));
			}

			// Checkpoint - starting servers
			checkPoint(out, CheckPoint.ServerStart);

			// Get the debug configuration
			DebugConfigSection dbgConfig = (DebugConfigSection) m_srvConfig.getConfigSection(DebugConfigSection.SectionName);

			// Start the configured servers
			for (int i = 0; i < m_srvConfig.numberOfServers(); i++) {

				// Get the current server
				NetworkServer server = m_srvConfig.getServer(i);

				// DEBUG
				if ( Debug.EnableInfo && dbgConfig != null && dbgConfig.hasDebug())
					Debug.println("Starting server " + server.getProtocolName() + " ...");

				// Start the server
				m_srvConfig.getServer(i).startServer();
			}

			// Checkpoint - servers started
			checkPoint(out, CheckPoint.ServerStarted);

			// Check if the server is running as a service
			boolean service = false;

			if ( ConsoleIO.isValid() == false)
				service = true;

			// Checkpoint - servers running
			checkPoint(out, CheckPoint.Running);

			// Install the shutdown hook
			m_mainThread= Thread.currentThread();
            Runtime.getRuntime().addShutdownHook( new FileServerShutdownHook( this));

			// Wait while the server runs, user may stop or restart the server by typing a key
			m_shutdown = false;

			while (m_shutdown == false && m_restart == false) {

				// Check if the user has requested a shutdown, if running interactively
				if ( service == false && m_allowShutViaConsole) {

					// Wait for the user to enter the shutdown key
					int inChar = ConsoleIO.readCharacter();

					if ( inChar == 'x' || inChar == 'X')
						m_shutdown = true;
					else if ( inChar == 'r' || inChar == 'R')
						m_restart = true;
					else if ( inChar == 'g' || inChar == 'G') {
						Debug.println( "Running garbage collection ...");
						System.gc();
					}
					else if ( inChar == -1) {
						
						// Sleep for a short while
						try {
							Thread.sleep(500);
						}
						catch (InterruptedException ex) {
						}
					}
				}
				else {

					// Sleep for a short while
					try {
						Thread.sleep(500);
					}
					catch (InterruptedException ex) {
					}
				}
			}

			// Checkpoint - servers stopping
			checkPoint(out, CheckPoint.ServerStop);

			// Shutdown the servers
			int idx = m_srvConfig.numberOfServers() - 1;

			while (idx >= 0) {

				// Get the current server
				NetworkServer server = m_srvConfig.getServer(idx--);

				// DEBUG
				if ( Debug.EnableInfo && dbgConfig != null && dbgConfig.hasDebug())
					Debug.println("Shutting server " + server.getProtocolName() + " ...");

				// Stop the server
				server.shutdownServer(false);
			}

			// Close the configuration
			m_srvConfig.closeConfiguration();
			
			// Checkpoint - servers stopped
			checkPoint(out, CheckPoint.ServerStopped);
		}
		catch (Exception ex) {

			// Server error
			checkPointError(out, CheckPoint.ServerStarted, ex);
		}
		finally {

			// Close all active servers
			int idx = m_srvConfig.numberOfServers() - 1;

			while (idx >= 0) {
				NetworkServer srv = m_srvConfig.getServer(idx--);
				if ( srv.isActive())
					srv.shutdownServer(true);
			}
		}

		// Checkpoint - finished
		checkPoint(out, CheckPoint.Finished);

		// Indicate file server is no longer running
        m_mainThread = null;
	}

    /**
     * Check if the file server is running
     *
     * @return boolean
     */
    public final boolean isRunning() {
        return m_shutdown == false && m_mainThread != null;
    }

    /**
     * Request a server shutdown
     */
    public final void requestShutdown() {

        // Indicate server shutdown
        m_shutdown = true;

        // Get the debug configuration
        DebugConfigSection dbgConfig = (DebugConfigSection) m_srvConfig.getConfigSection(DebugConfigSection.SectionName);

        // Shutdown the servers
        int idx = m_srvConfig.numberOfServers() - 1;

        while (idx >= 0) {

            // Get the current server
            NetworkServer server = m_srvConfig.getServer(idx--);

            // DEBUG
            if ( Debug.EnableInfo && dbgConfig != null && dbgConfig.hasDebug())
                Debug.println("Shutting server " + server.getProtocolName() + " ...");

            // Stop the server
            server.shutdownServer(false);
        }

        // Close the configuration
        m_srvConfig.closeConfiguration();
    }

	/**
	 * Shutdown the server when running as an NT service
	 * 
	 * @param args String[]
	 */
	public final static void shutdownServer(String[] args) {
		m_shutdown = true;
	}

	/**
	 * Create the SMB server
	 * 
	 * @param config ServerConfiguration
	 * @return NetworkServer
	 * @exception Exception Error creating the SMB server
	 */
	protected NetworkServer createSMBServer(ServerConfiguration config)
		throws Exception {

		// Create an SMB server
		NetworkServer smbServer = new SMBServer(config);

		// Check if there are any drive mappings configured
		if ( Platform.isWindows() && config.hasConfigSection(DriveMappingsConfigSection.SectionName))
			smbServer.addServerListener(this);

		// Return the SMB server
		return smbServer;
	}

	/**
	 * Create the NetBIOS name server
	 * 
	 * @param config ServerConfiguration
	 * @return NetworkServer
	 * @exception Exception Error creating the NetBIOS name server
	 */
	protected NetworkServer createNetBIOSServer(ServerConfiguration config)
		throws Exception {

		// Create a NetBIOS name server
		return new NetBIOSNameServer(config);
	}

	/**
	 * Create the FTP server
	 * 
	 * @param config ServerConfiguration
	 * @return NetworkServer
	 * @exception Exception Error creating the FTP server
	 */
	protected NetworkServer createFTPServer(ServerConfiguration config)
		throws Exception {

		// Create the FTP server instance
		return createServer("org.filesys.ftp.FTPServer", config);
	}

	/**
	 * Create the NFS server
	 * 
	 * @param config ServerConfiguration
	 * @return NetworkServer
	 * @exception Exception Error creating the NFS server
	 */
	protected NetworkServer createNFSServer(ServerConfiguration config)
		throws Exception {

		// Create the NFS server instance
		return createServer("org.filesys.oncrpc.nfs.NFSServer", config);
	}

	/**
	 * Create the NFS mount server
	 * 
	 * @param config ServerConfiguration
	 * @return NetworkServer
	 * @exception Exception Error creating the mount server
	 */
	protected NetworkServer createNFSMountServer(ServerConfiguration config)
		throws Exception {

		// Create the mount server instance
		return createServer("org.filesys.oncrpc.mount.MountServer", config);
	}

	/**
	 * Create the NFS port mapper server
	 * 
	 * @param config ServerConfiguration
	 * @return NetworkServer
	 * @exception Exception Error creating the port mapper
	 */
	protected NetworkServer createNFSPortMapper(ServerConfiguration config)
		throws Exception {

		// Create the port mapper server instance
		return createServer("org.filesys.oncrpc.portmap.PortMapperServer", config);
	}

	/**
	 * Create a network server using reflection
	 * 
	 * @param className String
	 * @param config ServerConfiguration
	 * @return NetworkServer
	 * @exception Exception Error creating the network server instance
	 */
	protected final NetworkServer createServer(String className, ServerConfiguration config)
		throws Exception {

		// Create the server instance using reflection
		NetworkServer srv = null;

		// Find the server constructor
		Class<?>[] classes = new Class[1];
		classes[0] = ServerConfiguration.class;
		Constructor<?> srvConstructor = Class.forName(className).getConstructor(classes);

		// Create the network server
		Object[] args = new Object[1];
		args[0] = config;
		srv = (NetworkServer) srvConstructor.newInstance(args);

		// Return the network server instance
		return srv;
	}

	/**
	 * Load the server configuration, default is to load using an XML configuration file.
	 * 
	 * @param out PrintStream
	 * @param cmdLineArgs String[]
	 * @return ServerConfiguration
	 * @exception Exception Error loading the server configuration
	 */
	protected ServerConfiguration loadConfiguration(PrintStream out, String[] cmdLineArgs)
		throws Exception {

		String fileName = null;

		if ( cmdLineArgs.length < 1) {

			// Search for a default configuration file in the users home directory
			fileName = System.getProperty("user.home") + File.separator + DEFAULT_CONFIGFILENAME;
		}
		else
			fileName = cmdLineArgs[0];

		// Load the configuration
		ServerConfiguration srvConfig = null;

		// Create an XML configuration
		srvConfig = new XMLServerConfiguration(clientLogger);
		srvConfig.loadConfiguration(fileName);

		// Return the server configuration
		return srvConfig;
	}

	/**
	 * Create the output stream for logging
	 * 
	 * @return PrintStream
	 */
	protected PrintStream createOutputStream() {
		return System.out;
	}

	/**
	 * Checkpoint method, called at various points of the server startup and shutdown
	 * 
	 * @param out PrintStream
	 * @param check CheckPoint
	 */
	protected void checkPoint(PrintStream out, CheckPoint check) {
	}

	/**
	 * Checkpoint error method, called if an error occurs during server startup/shutdown
	 * 
	 * @param out PrintStream
	 * @param check CheckPoint
	 * @param ex Exception
	 */
	protected void checkPointError(PrintStream out, CheckPoint check, Exception ex) {

		// Default error output goes to the console
		String msg = "%% Error occurred";

		switch (check) {

			// Configuration load error
			case ConfigLoading:
				msg = "%% Failed to load server configuration";
				break;

			// Checking local network address error
			case CheckIPAddress:
				msg = "%% Failed to get local IP address details";
				break;

			//
			case ServerStarted:
				msg = "%% Server error";
				break;
		}

		// Output the error message and a stack trace
		out.println(msg);
		if ( m_dumpStackOnError)
			ex.printStackTrace(out);
	}

	/**
	 * Handle server startup/shutdown events
	 * 
	 * @param server NetworkServer
	 * @param event int
	 */
	public void serverStatusEvent(NetworkServer server, int event) {

		// Check for an SMB server event
		if ( server instanceof SMBServer) {

			// Get the drive mappings configuration
			DriveMappingsConfigSection mapConfig = (DriveMappingsConfigSection) m_srvConfig
					.getConfigSection(DriveMappingsConfigSection.SectionName);
			if ( mapConfig == null)
				return;

			// Check for a server startup event, add drive mappings now that the server is running
			if ( event == ServerListener.ServerStartup) {

				// Get the mapped drives list
				DriveMappingList mapList = mapConfig.getMappedDrives();

				// Add the mapped drives
				for (int i = 0; i < mapList.numberOfMappings(); i++) {

					// Get the current drive mapping
					DriveMapping driveMap = mapList.getMappingAt(i);

					// DEBUG
					if ( Debug.EnableInfo && mapConfig.hasDebug())
						Debug.println("Mapping drive " + driveMap.getLocalDrive() + " to " + driveMap.getRemotePath() + " ...");

					// Create a local mapped drive to the server
					int sts = Win32Utils.MapNetworkDrive(driveMap.getRemotePath(), driveMap.getLocalDrive(), driveMap
							.getUserName(), driveMap.getPassword(), driveMap.hasInteractive(), driveMap.hasPrompt());

					// Check if the drive was mapped successfully
					if ( sts != 0)
						Debug.println("Failed to map drive " + driveMap.getLocalDrive() + " to " + driveMap.getRemotePath()
								+ ", status = " + SMBErrorText.ErrorString(SMBStatus.Win32Err, sts));
				}
			}
			else if ( event == ServerListener.ServerShutdown) {

				// Get the mapped drives list
				DriveMappingList mapList = mapConfig.getMappedDrives();

				// Remove the mapped drives
				for (int i = 0; i < mapList.numberOfMappings(); i++) {

					// Get the current drive mapping
					DriveMapping driveMap = mapList.getMappingAt(i);

					// DEBUG
					if ( Debug.EnableInfo && mapConfig.hasDebug())
						Debug.println("Removing mapped drive " + driveMap.getLocalDrive() + " to " + driveMap.getRemotePath()
								+ " ...");

					// Remove a mapped drive
					int sts = Win32Utils.DeleteNetworkDrive(driveMap.getLocalDrive(), false, true);

					// Check if the drive was unmapped successfully
					if ( sts != 0)
						Debug.println("Failed to delete mapped drive " + driveMap.getLocalDrive() + " from "
								+ driveMap.getRemotePath() + ", status = " + SMBErrorText.ErrorString(SMBStatus.Win32Err, sts));
				}
			}
		}
	}
}
