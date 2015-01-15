package mod_cfml;

/**
 * @author Jordan Michaels (jordan@viviotech.net)
 *
 * Licensed Under the LGPL 3.0
 * http://www.opensource.org/licenses/lgpl-3.0.html
 *
 * Home Page:
 * http://www.modcfml.org/
 * 
 * Version:
 * 1.0.15
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
import java.util.Date;
import java.text.SimpleDateFormat;

// servlet
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;

// apache tomcat
import org.apache.catalina.Host;
import org.apache.catalina.Engine;
import org.apache.catalina.core.*;
import org.apache.catalina.Globals;
import org.apache.catalina.valves.ValveBase;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.startup.HostConfig;

public class core extends ValveBase implements Serializable {

    // declare standard vars
    private String tcDocRoot;
    private String tcHost;
    private String[] tcHostPortFilter;
    private String[] contextRecord;
    private String tcURI;
    private String tcURIParams;
    private String tcRedirectURL;
    private String newHostConfDir;
    private String newHostWorkDir;
    
    // declare configurable param defaults
    private boolean loggingEnabled = false;
    private int waitForContext = 3;
    private int timeBetweenContexts = 30000; // 30 seconds
    private int maxContexts = 10;

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

    @Override
    public void invoke (Request request, Response response) throws IOException, ServletException {
        // 
        
        // make sure waitforcontext is a positive number
        if ( waitForContext < 0 ) {
            // if it's not valid, set to default
            waitForContext = 3;
        }
        // Get the DocRoot value from the HTTP header
        this.tcDocRoot = ((HttpServletRequest) request).getHeader("X-Tomcat-DocRoot");
        // Get the Host name value from the HTTP header
        this.tcHost = ((HttpServletRequest) request).getHeader("Host");
        // Gete the URI so we can pass it to ourselves again if needed
        this.tcURI = request.getDecodedRequestURI();
        this.tcURIParams = request.getQueryString();

        // verify the host value exists and isn't blank
        if ((this.tcHost == null) || this.tcHost.length() == 0) {
            // bad host? Skip this Valve.
            if (loggingEnabled) {
                System.out.println("[mod_cfml] FATAL: Invalid host: Null or zero-length.");
            }
            getNext().invoke(request, response);
            return;
        }

        // logging for debugging purposes
        if (loggingEnabled) {
            System.out.println("[mod_cfml] Decoded Requst URI => " + this.tcURI);
            System.out.println("[mod_cfml] QueryString => " + this.tcURIParams);
            System.out.println("[mod_cfml] DocRoot Value => " + this.tcDocRoot);
            System.out.println("[mod_cfml] Host Value => " + this.tcHost);
        }

        // get system vars
        Host currentHost = (Host) getContainer();
        Engine engine = (Engine) currentHost.getParent();

        // verify the tcDocRoot value exists and isn't blank
        if ((this.tcDocRoot == null) || this.tcDocRoot.length() == 0) {
            // bad DocRoot? Skip this Valve.
            if (loggingEnabled) {
                System.out.println("[mod_cfml] FATAL: Invalid DocRoot: Null or zero-length.");
            }
            getNext().invoke(request, response);
            return;
        }
        
        if (loggingEnabled) {
            System.out.println("[mod_cfml] tcHost contains ':' ? => " + this.tcHost.contains(":"));
        }

        // remove the port number from the host value if it's present
        if (this.tcHost.contains(":")) {
            // if a colon is present, a port was passed
            // split the host value into two parts, the host and the port value
            tcHostPortFilter = this.tcHost.split(":");
            if (loggingEnabled) {
                for (int i = 0; i < tcHostPortFilter.length; i++) {
                    System.out.println("[mod_cfml] Host split value [" + i + "]: " + tcHostPortFilter[i]);
                }
            }
            // set tcHost to contain ONLY the host value
            this.tcHost = this.tcHostPortFilter[0];
        }

        // see if the host already exists
        if (engine.findChild(this.tcHost) != null) {
            // host already exist? Skip this Valve.
            if (loggingEnabled) {
                System.out.println("[mod_cfml] FATAL: Host already exists.");
            }
            getNext().invoke(request, response);
            return;
        }
        
        // BEGIN Throttling
        Date newNow = new Date();
        
        // try pulling up the contextRecord (if it exists yet)
        try {
            ObjectInputStream is = new ObjectInputStream(new FileInputStream("mod_cfml.dat"));
            // load the array into active memory
            contextRecord =  (String[]) is.readObject();
            // log it
            if (loggingEnabled) {
                System.out.println("[mod_cfml] lastContext = " + contextRecord[0]);
                System.out.println("[mod_cfml] throttleDate = " + contextRecord[1]);
                System.out.println("[mod_cfml] throttleValue = " + contextRecord[2]);
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
            // 1 = throttleDate - stores day this record is for (days in year value)
            // 2 = throttleValue - stores number of contexts created so far during this day
            
            contextRecord[0] = null;
            
            // Set the value of throttleDate to today
            SimpleDateFormat dayInYear = new SimpleDateFormat ("D");
            contextRecord[1] = dayInYear.format(newNow);
            
            // Set the value of throttleValue to 0
            contextRecord[2] = "0";
            
            if (loggingEnabled) {
                System.out.println("[mod_cfml] New contextRecord Array initialized...");
                System.out.println("[mod_cfml] lastContext = " + contextRecord[0]);
                System.out.println("[mod_cfml] throttleDate = " + contextRecord[1]);
                System.out.println("[mod_cfml] throttleValue = " + contextRecord[2]);
            }
        }
        
        // verify throttleDate value
        SimpleDateFormat dayInYear = new SimpleDateFormat ("D");
        if ( Integer.parseInt(contextRecord[1]) != Integer.parseInt(dayInYear.format(newNow)) ) {
            if (loggingEnabled) {
                System.out.println("[mod_cfml] New day detected. Reinitializing throttleDate and throttleValue.");
            }
            // if throttleDate is not today, reset it and throttleValue as well
            contextRecord[1] = dayInYear.format(newNow);
            contextRecord[2] = "0";
        }
        
        // verify timeBetweenContexts
        if ( contextRecord[0] != null ) {
            // if it's not null, ensure we've waited our timeBetweenContexts
            if ((Long.parseLong(contextRecord[0]) + timeBetweenContexts) >  newNow.getTime()) {
                // if enough time hasn't passed yet, send a "wait" response
                System.out.println("[mod_cfml] Time Between Contexts has not been fulfilled. Please wait a few moments and try again.");
                response.sendError(503, "[mod_cfml] Time Between Contexts has not been fulfilled. Please wait a few moments and try again.");
                return;
            }
        }
        
        // verify maxContexts
        if (Integer.parseInt(contextRecord[2]) >= maxContexts) {
            // if maxContexts reached, refuse the request for today
            System.out.println("[mod_cfml] MaxContexts limit reached. Try again tomorrow.");
            response.sendError(503, "[mod_cfml] MaxContexts limit reached. Try again tomorrow.");
            return;
        }
        
        // END Throttling

        // set base variables for tcDocRoot file system check
        File tcDocRootFile = null;
        File file = null;

        file = new File(this.tcDocRoot);
        tcDocRootFile = file.getCanonicalFile();

        if (tcDocRootFile.isDirectory()) {
            // everything checks out, create the new host
            // STEP 1 - Check/Create the XML config and work directories

            // set the config directory value
            this.newHostConfDir = System.getProperty(Globals.CATALINA_BASE_PROP) + "/conf/Catalina/" + this.tcHost;

            File newHostConfDirFile = null;
            file = new File(this.newHostConfDir);
            newHostConfDirFile = file.getCanonicalFile();

            // see if the directory exists already
            if (!newHostConfDirFile.isDirectory()) {
                // if it doesn't exist, create it
                if (loggingEnabled) {
                    System.out.println("[mod_cfml] Creating new config directory: " + newHostConfDirFile);
                }
                file.mkdir();
            } else {
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
            this.newHostWorkDir = System.getProperty(Globals.CATALINA_BASE_PROP) + "/work/Catalina/" + this.tcHost;

            File newHostWorkDirFile = null;
            file = new File(this.newHostWorkDir);
            newHostWorkDirFile = file.getCanonicalFile();

            // see if the directory exists already
            if (!newHostConfDirFile.isDirectory()) {
                // if it doesn't exist, ignore it
                if (loggingEnabled) {
                    System.out.println("[mod_cfml] Work directory doesn't exist: " + newHostWorkDirFile);
                }
            } else {
                // if it does exist, remove it (and everything under it)
                // because it's from an old config
                if (loggingEnabled) {
                    System.out.println("[mod_cfml] Removing old work directory: " + newHostWorkDirFile);
                }
                deleteDir(file);
            }

            // STEP 2 - Write the context XML config
            String newHostConfFile = newHostConfDirFile + "/ROOT.xml";
            if (loggingEnabled) {
                System.out.println("[mod_cfml] Creating context file: " + newHostConfFile);
            }
            PrintWriter out = new PrintWriter(new FileOutputStream(newHostConfFile));
            out.println("<?xml version='1.0' encoding='utf-8'?>");
            out.println("<Context docBase=\"" + this.tcDocRoot + "\">");
            out.println("  <WatchedResource>WEB-INF/web.xml</WatchedResource>");
            out.println("</Context>");
            out.flush(); // write to the file
            out.close(); // close out the file

            // STEP 3 -  ensure the context config files and work directory exist
            if (loggingEnabled) {
                System.out.println("[mod_cfml] Wait for context? => " + (waitForContext > 0) );
            }
            if ( waitForContext > 0 ) {
                if (loggingEnabled) {
                    System.out.println("[mod_cfml] Verifying context files...");
                }
                File tcContextXMLFile = null;
                File tcContextXMLFilePointer = null;

                tcContextXMLFilePointer = new File(newHostConfFile);
                tcContextXMLFile = tcContextXMLFilePointer.getCanonicalFile();
                // wait for the specified number of seconds
                for (int i = 0; i < waitForContext; i++) {
                    // check to see if the directory exists
                    if (newHostConfDirFile.isDirectory()) {
                        // if it exists, check the file too
                        if ( tcContextXMLFile.isFile() ) {
                            // if the dir and file exist, check for the work directory
                            if ( newHostWorkDirFile.isDirectory() ) {
                                // if the work directory exists, end this loop
                                break;
                            }
                        }
                    }
                    if (loggingEnabled) {
                        System.out.println("[mod_cfml] Waiting for context files: ["+i+"]");
                    }
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                    }
                }

                if (loggingEnabled) {
                    System.out.println("[mod_cfml] Wait loop ended. Context files found? ["+ tcContextXMLFile.isFile() + "]");
                }
            }

            // STEP 4 - Create the context
            StandardHost host = new StandardHost();
            // log it
            if (loggingEnabled) {
                System.out.println("[mod_cfml] Creating New Host... ");
                // System.out.println("setAppBase Value => " + tcDocRootFile.toString());
                System.out.println("[mod_cfml] setName Value => " + this.tcHost);
            }
            host.addLifecycleListener(new HostConfig());
            host.setAppBase("webapps");
            // host.setAppBase(tcDocRootFile.toString());
            host.setName(this.tcHost);
            host.setAutoDeploy(true);
            host.setDeployOnStartup(true);
            host.setDeployXML(true);
            // make it
            try {
                engine.addChild(host);
            } catch (Exception e) {
                System.out.println(e.toString());
                return;
            }

            // STEP 5 - record the times and serialize our data
            
            // contextRecord array key:
            // 0 = lastContext - stores time last context was made (millisecond value)
            // 1 = throttleDate - stores day this record is for (days in year value)
            // 2 = throttleValue - stores number of contexts created so far during this day
            
            // set the lastContext value to now
            contextRecord[0] = String.valueOf(newNow.getTime());
            
            // add one to our throttleValue
            int numContexts = Integer.parseInt(contextRecord[2]);
            numContexts++;
            contextRecord[2] = Integer.toString(numContexts);
            
            // serialize and save values
            try {
                ObjectOutputStream os = new ObjectOutputStream(new FileOutputStream("mod_cfml.dat"));
                os.writeObject(contextRecord);
            } catch (Exception ex) {
                if (loggingEnabled) {
                    System.out.println("[mod_cfml] NOTICE: Serialization Write Exception: " + ex.toString());
                }
            }
            
            // STEP 6 - call ourselves again so we bypass localhost
            if (!(this.tcURIParams == null) && this.tcURIParams.length() > 0) {
                // if there are URI params, pass them
                this.tcRedirectURL = this.tcURI + "?" + this.tcURIParams;
            } else if (this.tcURI.equals("index.cfm") || this.tcURI.equals("/index.cfm")) {
                // if we're hitting an index file with no uri params, we're probably
                // getting a request for a TLD, so just redirect to a TLD
                this.tcRedirectURL = "";
            } else {
                this.tcRedirectURL = this.tcURI;
            }

            if (loggingEnabled) {
                System.out.println("[mod_cfml] Rredirect URL => '" + this.tcRedirectURL + "'");
            }

            response.sendRedirect(this.tcRedirectURL);
            return;
        } else {
            // log the invalid directory if we have logging on
            if (loggingEnabled) {
                System.out.println("[mod_cfml] FATAL: DocRoot value failed isDirectory() check. Directory may not exist, or Tomcat may not have permission to check it.");
            }
        }

        // otherwise send the request/response on it's way
        getNext().invoke(request, response);
        return;

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
}
