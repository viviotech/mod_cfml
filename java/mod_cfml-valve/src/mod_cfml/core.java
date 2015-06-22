package mod_cfml;

/**
 * @author Jordan Michaels (jordan@viviotech.net)
 * @modified Paul Klinkenberg (paul@lucee.nl)
 *
 * Licensed Under the LGPL 3.0
 * http://www.opensource.org/licenses/lgpl-3.0.html
 *
 * Home Page:
 * http://www.modcfml.org/
 * 
 * Version:
 * 1.1.01
 * 1.1.03: June 22, 2015, Paul Klinkenberg
 */

// java
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Serializable;
import java.io.FileOutputStream;
import java.io.ObjectOutputStream;
import java.io.FileInputStream;
import java.io.ObjectInputStream;
import java.lang.String;
import java.util.Date;
// import java.text.SimpleDateFormat;

// servlet
import javax.servlet.ServletException;

// apache tomcat
import org.apache.catalina.Host;
import org.apache.catalina.Engine;
import org.apache.catalina.core.StandardHost;
import org.apache.catalina.Globals;
import org.apache.catalina.Container;
import org.apache.catalina.valves.ValveBase;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.startup.HostConfig;
import org.apache.catalina.LifecycleException;

public class core extends ValveBase implements Serializable {

	private String[] contextRecord;
	// declare standard vars

	// declare configurable param defaults
	private boolean loggingEnabled = false;
	private int waitForContext = 3; // 3 seconds
	private int timeBetweenContexts = 2000; // 2 seconds
	private int maxContexts = 200;
	private boolean scanClassPaths = false;
	private String sharedKey = "";

	// methods for configurable params
	public boolean getLoggingEnabled() {
		return (loggingEnabled);
	}

	public void setLoggingEnabled(boolean loggingEnabled) {
		this.loggingEnabled = loggingEnabled;
	}

	public int getWaitForContext() {
		return (waitForContext);
	}

	public void setWaitForContext(int waitForContext) {
		this.waitForContext = waitForContext;
	}

	public int getTimeBetweenContexts() {
		return (timeBetweenContexts);
	}

	public void setTimeBetweenContexts(int timeBetweenContexts) {
		this.timeBetweenContexts = timeBetweenContexts;
	}

	public int getMaxContexts() {
		return (maxContexts);
	}

	public void setMaxContexts(int maxContexts) {
		this.maxContexts = maxContexts;
	}

	public boolean getScanClassPaths() {
		return (scanClassPaths);
	}

	public void setScanClassPaths(boolean scanClassPaths) {
		this.scanClassPaths = scanClassPaths;
	}

	public String getSharedKey() {
		return (sharedKey);
	}

	public void setSharedKey(String sharedKey) {
		this.sharedKey = sharedKey;
	}

	public boolean initInternalCalled = false;

	protected void initInternal() throws LifecycleException {
		super.initInternal();
		initInternalCalled = true;

		/* reset the counters; we've rebooted */
		File fi = new File("mod_cfml.dat");
		fi.delete();
	}

	@Override
	public void invoke(Request request, Response response) throws IOException, ServletException {
		String tcDocRoot;
		String tcHost;
		String tcMainHost;

		if (initInternalCalled) {
			if (loggingEnabled) {
				System.out.println("[mod_cfml] initInternal was called: system rebooted? Counters have been reset.");
			}
			initInternalCalled = false;
		}

		// Get the DocRoot value from the HTTP header
		tcDocRoot = request.getHeader("X-Tomcat-DocRoot");

		// verify the tcDocRoot value exists and isn't blank
		if ((tcDocRoot == null) || tcDocRoot.isEmpty()) {
			// bad DocRoot? Skip this Valve.
			if (loggingEnabled) {
				System.out.println("[mod_cfml] FATAL: X-Tomcat-DocRoot not given or empty.");
			}
			getNext().invoke(request, response);
			return;
		}

		// check if secret key is given in the valve config. if so, make sure the client sent it as well
		// (request should come proxied from the frontend webserver)
		if (sharedKey != null && !sharedKey.isEmpty()) {
			String incomingKey = request.getHeader("X-ModCFML-SharedKey");
			if (incomingKey == null || !incomingKey.equals(sharedKey)) {
				handleError(503, "mod_cfml request authentication failed!", response);
				return;
			}
		}

		// Get the Host name value from the HTTP header
		tcHost = request.getHeader("Host");
		// Optional: get the webserver-hostContext-ID
		tcMainHost = request.getHeader("X-Webserver-Context");
		// the X-Webserver-Context header might not be available
		if (tcMainHost == null || tcMainHost.isEmpty()) {
			tcMainHost = tcHost;
		}
		// host names should be lowercase, but Tomcat will not complain, just convert it to lowercase after we use it as a hostname/alias name
		if (tcMainHost != null)
		{
			tcMainHost = tcMainHost.toLowerCase();
			// remove the port number from the host value if it's present
			tcMainHost = removePortFromHost(tcMainHost, loggingEnabled);
		}
		if (tcHost != null) {
			tcHost = tcHost.toLowerCase();
			// remove the port number from the host value if it's present
			tcHost = removePortFromHost(tcHost, loggingEnabled);
		}

		// verify the host value exists and isn't blank
		if (tcMainHost == null || tcMainHost.isEmpty() || tcHost == null || tcHost.isEmpty()) {
			// bad host? Skip this Valve.
			if (loggingEnabled) {
				System.out.println("[mod_cfml] FATAL: Invalid host: Null or zero-length.");
			}
			getNext().invoke(request, response);
			return;
		}

		// Gete the URI so we can pass it to ourselves again if needed
		String tcURI = request.getDecodedRequestURI();
		String tcURIParams = request.getQueryString();

		// logging for debugging purposes
		if (loggingEnabled) {
			System.out.println("[mod_cfml] Decoded Request URI => " + tcURI);
			System.out.println("[mod_cfml] QueryString => " + tcURIParams);
			System.out.println("[mod_cfml] DocRoot Value => " + tcDocRoot);
			if (!tcMainHost.equals(tcHost)) {
				System.out.println("[mod_cfml] Webserver main Host => " + tcMainHost);
				System.out.println("[mod_cfml] Alias Value => " + tcHost);
			} else {
				System.out.println("[mod_cfml] Host Value => " + tcHost);
			}
		}

		// get system vars
		Host currentHost = (Host) getContainer();
		Engine engine = (Engine) currentHost.getParent();

		// see if the host already exists
		if (engine.findChild(tcHost) != null) {
			// host already exist? Skip this Valve.
			if (loggingEnabled) {
				System.out.println("[mod_cfml] FATAL: Host [" + tcHost + "] already exists.");
			}
			getNext().invoke(request, response);
			return;
		}

		// set base variables for tcDocRoot file system check
		File file = new File(tcDocRoot);
		File tcDocRootFile = file.getCanonicalFile();

		if (!tcDocRootFile.isDirectory()) {
			// log the invalid directory if we have logging on
			if (loggingEnabled) {
				System.out.println("[mod_cfml] FATAL: DocRoot value [" + tcDocRoot + "] failed isDirectory() check. Directory may not exist, or Tomcat may not have permission to check it.");
			}
			getNext().invoke(request, response);
			return;
		}


// Check if we need to add a Host, or just an Alias
// Aliases do not need to be throttled
		boolean addAsAlias = (!tcMainHost.equals(tcHost)) && (engine.findChild(tcMainHost) != null);

// BEGIN Throttling
		Date newNow = new Date();

		// try pulling up the contextRecord (if it exists yet)
		try {
			File fi = new File("mod_cfml.dat");
			if (fi.exists()) {
				FileInputStream fis = new FileInputStream(fi);
				ObjectInputStream is = new ObjectInputStream(fis);

				// load the array into active memory
				contextRecord = (String[]) is.readObject();
				fis.close();
				is.close();
				// log it
				if (loggingEnabled) {
					System.out.println("[mod_cfml] lastContext = " + contextRecord[0]);
					System.out.println("[mod_cfml] throttleValue = " + contextRecord[2]);
				}
			}
		} catch (Exception ex) {
			if (loggingEnabled) {
				System.out.println("[mod_cfml] Serialization Read Exception: " + ex.toString());
			}
		}

		if ((contextRecord == null) || contextRecord.length == 0) {
			// first run, create new array with default values
			contextRecord = new String[3];

			// contextRecord array key:
			// 0 = lastContext - stores time last context was made (millisecond value)
			// not in use anymore: 1 = throttleDate - stores day this record is for (days in year value)
			// 2 = throttleValue - stores number of contexts created so far during this day

			contextRecord[0] = null;

			// Set the value of throttleDate to today
			// SimpleDateFormat dayInYear = new SimpleDateFormat ("D");
			contextRecord[1] = "0";// not in use anymore: dayInYear.format(newNow);

			// Set the value of throttleValue to 0
			contextRecord[2] = "0";

			if (loggingEnabled) {
				System.out.println("[mod_cfml] New contextRecord Array initialized...");
				System.out.println("[mod_cfml] lastContext = " + contextRecord[0]);
				System.out.println("[mod_cfml] throttleValue = " + contextRecord[2]);
			}
		}

		// verify timeBetweenContexts
		if (!addAsAlias && contextRecord[0] != null) {
			// if it's not null, ensure we've waited our timeBetweenContexts
			if ((Long.parseLong(contextRecord[0]) + timeBetweenContexts) > newNow.getTime()) {
				// if enough time hasn't passed yet, send a "wait" response
				handleError(503, "Time Between Contexts has not been fulfilled. Please wait a few moments and try again.", response);
				return;
			}
		}

		// verify maxContexts
		if (!addAsAlias && Integer.parseInt(contextRecord[2]) >= maxContexts) {
			// if maxContexts reached, refuse the request for today
			handleError(503, "MaxContexts limit reached. No more contexts can be created!", response);
			return;
		}

// save the current time to file, so a next request can do a check for the timeBetweenContexts 
		// set the lastContext value to now
		if (!addAsAlias || contextRecord[0] == null) {
			contextRecord[0] = String.valueOf(newNow.getTime());
		}
		// serialize and save values
		try {
			ObjectOutputStream os = new ObjectOutputStream(new FileOutputStream("mod_cfml.dat"));
			os.writeObject(contextRecord);
			os.close();
		} catch (Exception ex) {
			if (loggingEnabled) {
				System.out.println("[mod_cfml] NOTICE: Serialization Write Exception: " + ex.toString());
			}
		}

// END Throttling

// everything checks out, create the new host / add the alias

		StandardHost host;

// STEP 1: check if the mainHost exists, and we need to add the current Host as alias
		if (!tcMainHost.equals(tcHost)) {
			if (engine.findChild(tcMainHost) != null) {
				Container child = engine.findChild(tcMainHost);
				if (!(child instanceof StandardHost)) {
					System.out.println("[mod_cfml] FATAL: The Tomcat Host [" + tcMainHost + "], parent for host-alias [" + tcHost + "], is not an instance of StandardHost! (type: " + child.getClass().getName() + ")");
					getNext().invoke(request, response);
					return;
				}
				if (loggingEnabled) {
					System.out.println("[mod_cfml] Adding host alias [" + tcHost + "] to existing Host [" + tcMainHost + "]");
				}
				host = (StandardHost) child;
				host.addAlias(tcHost);
				addAsAlias = true;
			}
		}

// STEP 2 - Check/Create the XML config and work directories
		// set the config directory value
		String newHostConfDir = System.getProperty(Globals.CATALINA_BASE_PROP) + "/conf/Catalina/" + tcMainHost;
		File newHostConfDirFile = null;
		file = new File(newHostConfDir);
		newHostConfDirFile = file.getCanonicalFile();

		// see if the directory exists already
		if (!newHostConfDirFile.isDirectory()) {
			// if it doesn't exist, create it
			if (loggingEnabled) {
				System.out.println("[mod_cfml] Creating new config directory: " + newHostConfDirFile);
			}
			file.mkdir();
		} else if (addAsAlias == false) {
			// if it does exist, remove it (and everything under it)
			// because it's from an old config
			if (loggingEnabled) {
				System.out.println("[mod_cfml] Removing old config directory: " + newHostConfDirFile);
			}
			deleteDir(file);
			// now make the directory again so we start with a clean slate
			if (loggingEnabled) {
				System.out.println("[mod_cfml] Creating new config directory: " + newHostConfDirFile);
			}
			file.mkdir();
		}

		// set the work directory value
		String newHostWorkDir = System.getProperty(Globals.CATALINA_BASE_PROP) + "/work/Catalina/" + tcMainHost;

		File newHostWorkDirFile = null;
		file = new File(newHostWorkDir);
		newHostWorkDirFile = file.getCanonicalFile();

		// see if the directory exists already
		if (!newHostWorkDirFile.isDirectory()) {
			// if it doesn't exist, ignore it
			if (loggingEnabled) {
				System.out.println("[mod_cfml] Work directory doesn't exist: " + newHostWorkDirFile);
			}
		} else if (addAsAlias == false) {
			// if it does exist, remove it (and everything under it)
			// because it's from an old config
			if (loggingEnabled) {
				System.out.println("[mod_cfml] Removing old work directory: " + newHostWorkDirFile);
			}
			deleteDir(file);
		}

// STEP 3 - Write the context XML config
		String newHostConfFile = newHostConfDirFile + "/ROOT.xml";
		file = new File(newHostConfFile);
		if (!file.exists()) {
			if (loggingEnabled) {
				System.out.println("[mod_cfml] Creating context file: " + newHostConfFile);
			}
			PrintWriter out = new PrintWriter(new FileOutputStream(file));
			out.println("<?xml version='1.0' encoding='utf-8'?>");
			out.println("<Context docBase=\"" + tcDocRoot + "\">");
			out.println("  <WatchedResource>WEB-INF/web.xml</WatchedResource>");
			// the following line is THE difference between slow and fast Context loading. (eg. 3,000 ms vs 150 ms.)
			if (!scanClassPaths) {
				out.println("  <JarScanner scanClassPath=\"false\" />");
			}
			out.println("</Context>");
			out.flush(); // write to the file
			out.close(); // close out the file
		}

// STEP 4 - Create the context
		if (addAsAlias == false) {
			host = new StandardHost();
			// log it
			if (loggingEnabled) {
				System.out.println("[mod_cfml] Creating New Host... ");
				// System.out.println("setAppBase Value => " + tcDocRootFile.toString());
				System.out.println("[mod_cfml] setName Value => " + tcMainHost);
			}
			host.addLifecycleListener(new HostConfig());
			host.setAppBase("webapps");
			// host.setAppBase(tcDocRootFile.toString());
			host.setName(tcMainHost);
			host.setAutoDeploy(true);
			host.setDeployOnStartup(true);
			host.setDeployXML(true);
			if (!tcHost.equals(tcMainHost)) {
				host.addAlias(tcHost);
			}
			// make it
			try {
				engine.addChild(host);
			} catch (Exception e) {
				System.out.println("[mod_cfml] ERROR: " + e.toString());
				return;
			}
		}

// STEP 5 - ensure the context config files and work directory exist
		if (loggingEnabled) {
			System.out.println("[mod_cfml] Verifying context files...");
		}
		boolean _found = false;
		File tcContextXMLFile = null;
		File tcContextXMLFilePointer = null;

		tcContextXMLFilePointer = new File(newHostConfFile);
		tcContextXMLFile = tcContextXMLFilePointer.getCanonicalFile();
		// wait for the specified number of seconds
		for (int i = 0; i <= waitForContext; i++) {
			// check to see if the directory exists
			if (newHostConfDirFile.isDirectory()) {
				// if it exists, check the file too
				if (tcContextXMLFile.isFile()) {
					// if the dir and file exist, check for the work directory
					if (newHostWorkDirFile.isDirectory()) {
						// if the work directory exists, end this loop
						_found = true;
						break;
					}
				}
			}
			if (waitForContext > i) {
				if (loggingEnabled) {
					System.out.println("[mod_cfml] Context files do not yet exist! Will check again after 1 second... (" + (i + 1) + ")");
				}
				try {
					Thread.sleep(1000);
				} catch (InterruptedException e) {
				}
			} else {
				if (loggingEnabled) {
					System.out.println("[mod_cfml] ERROR: Context files do not exist! Will continue, but likely to result in error.");
				}
			}
		}

// STEP 6 - record the times and serialize our data

		// contextRecord array key:
		// 0 = lastContext - stores time last context was made (millisecond value)
		// 1 = throttleDate - stores day this record is for (days in year value)
		// 2 = throttleValue - stores number of contexts created so far during this day

		// add 1 to our throttleValue, if we added a new Host
		if (addAsAlias == false) {
			int numContexts = Integer.parseInt(contextRecord[2]);
			numContexts++;
			contextRecord[2] = Integer.toString(numContexts);
		}

		// serialize and save values
		try {
			ObjectOutputStream os = new ObjectOutputStream(new FileOutputStream("mod_cfml.dat"));
			os.writeObject(contextRecord);
			os.close();
		} catch (Exception ex) {
			if (loggingEnabled) {
				System.out.println("[mod_cfml] NOTICE: Serialization Write Exception: " + ex.toString());
			}
		}

// STEP 7 - call ourselves again so we bypass localhost
		String tcRedirectURL;
		if (!(tcURIParams == null) && tcURIParams.length() > 0) {
			// if there are URI params, pass them
			tcRedirectURL = tcURI + "?" + tcURIParams;
		} else if (tcURI.equals("index.cfm") || tcURI.equals("/index.cfm")) {
			// if we're hitting an index file with no uri params, we're probably
			// getting a request for a TLD, so just redirect to a TLD
			tcRedirectURL = "";
		} else {
			tcRedirectURL = tcURI;
		}

		if (loggingEnabled) {
			System.out.println("[mod_cfml] Redirect URL => '" + tcRedirectURL + "'");
		}

		response.sendRedirect(tcRedirectURL);
	}


	// create a seperate method for removing directories so we can call it
	// as many times as we may need it (including looping over itself).
	public static boolean deleteDir(File dir) {
		if (dir.isDirectory()) {
			String[] children = dir.list();
			for (int i = 0; i < children.length; i++) {
				boolean success = deleteDir(new File(dir, children[i]));
				if (!success) {
					return false;
				}
			}
		}

		// The directory is now empty so delete it
		return dir.delete();
	}

	public static void handleError(int statuscode, String msg, Response response) throws ServletException, IOException {
		System.out.println("[mod_cfml] ERROR (sent to client): " + statuscode + ": " + msg);
		response.setContentType("text/html");
		response.getWriter().write("<h3>Tomcat Mod_CFML error</h3><p>" + msg + "</p>");
		response.setStatus(statuscode);
	}


	public static String removePortFromHost(String host, boolean loggingEnabled) {
		if(host == null || ! host.contains(":")) {
			return host;
		}

		// using lastindexOf, since the host can also be an ipv6 request
		// like ﻿http://[fe80::2129:2625:31b5:6e02]/index.cfm
		int colonPos = host.lastIndexOf(':');
		String tcHostPort = host.substring(colonPos + 1);

		if (! tcHostPort.matches("^[1-9][0-9]*$")) {
			if (loggingEnabled) {
				System.out.println("[mod_cfml] incoming host [" + host + "] seemed to contain a port definition (eg. ':8080'), but port was not numeric => " + tcHostPort);
			}
			return host;
		}

		String newHost = host.substring(0, colonPos);

		if (loggingEnabled) {
			System.out.println("[mod_cfml] host ["+host+"] contains ':'. New value => " + newHost);
		}

		return newHost;
	}

}