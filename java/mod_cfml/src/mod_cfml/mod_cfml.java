package mod_cfml;

/**
 * @author jordan
 */

// Import required java libraries
import java.io.*;
import javax.servlet.*;
import javax.servlet.http.*;
import org.apache.tomcat.util.res.StringManager;
import org.apache.catalina.ContainerServlet;
import org.apache.catalina.Context;
import org.apache.catalina.Host;
import org.apache.catalina.Engine;
import org.apache.catalina.core.*;
import org.apache.catalina.startup.HostConfig;
import org.apache.catalina.manager.host.HostManagerServlet;

import org.apache.catalina.Globals;

public class mod_cfml extends HostManagerServlet implements Filter, ContainerServlet {

  // passed variables
  protected FilterConfig config = null;
  protected int cfdebug = 0;

  // declare variables for this filter
  private String tcDocRoot;
  private String tcHost;

  public void  init(FilterConfig config)
                    throws ServletException{
     this.config = config;
     // System.out.println("Tomcat MOD_CFML filter initialized.");

     if (debug > 0)
        config.getServletContext().log(
            "mod_cfml.init() mod_cfml invoker started with 'debug'=" + debug);
  }

  public void  doFilter(ServletRequest request,
              ServletResponse response,
              FilterChain chain)
              throws java.io.IOException, ServletException {

      // Get the DocRoot value from the HTTP header
      tcDocRoot = ((HttpServletRequest)request).getHeader("X-Tomcat-DocRoot");
      // Get the Host name value from the HTTP header
      tcHost = ((HttpServletRequest)request).getHeader("Host");
      
      // logging for debugging purposes
      System.out.println("DocRoot Value => "+ tcDocRoot );
      System.out.println("Host Value => "+ tcHost );

      // formulate Host Manager Call
      // PrintWriter writer = response.getWriter();
      StringManager smClient = getStringManager((HttpServletRequest)request);
      
//      StringWriter stringWriter = new StringWriter();
//      PrintWriter printWriter = new PrintWriter(stringWriter);

      String name = tcHost;
      String appBase = tcDocRoot;


      System.out.println("Context => "+ context.toString() );

      System.out.println("just before context...");
      Context context = (Context) wrapper.getParent();
      System.out.println("just before host...");
      Host currentHost = (Host) context.getParent();
      System.out.println("just after host...");
      Engine engine = (Engine) currentHost.getParent();
      System.out.println("just after engine...");

      StandardHost host = new StandardHost();
      System.out.println("just after new host...");
      host.setAppBase(tcDocRoot);
      host.setName(tcHost);

      System.out.println("just before new engine call...");
      engine.addChild(host);
      System.out.println("just after new engine call...");

//      String aliases = "";
//      boolean manager = false;
//      boolean autoDeploy = false;
//      boolean deployOnStartup = true;
//      boolean deployXML = true;
//      boolean unpackWARs = false;
//
//      // log new servlet values for debugging
//      // System.out.println("Writer Value => "+ writer );
//      System.out.println("smClient Value => "+ smClient );
//      System.out.println("name Value => "+ name );
//      System.out.println("appBase Value => "+ appBase );
//      System.out.println("aliases Value => "+ aliases );
//      System.out.println("manager Value => "+ manager );
//      System.out.println("autoDeploy Value => "+ autoDeploy );
//      System.out.println("deployOnStartup Value => "+ deployOnStartup );
//      System.out.println("deployXML Value => "+ deployXML );
//      System.out.println("unpackWARs Value => "+ unpackWARs );
//
//      super.add(CFwrapper,printWriter,name,true, smClient);

//      add(writer,
//        name,
//        aliases,
//        appBase,
//        manager,
//        autoDeploy,
//        deployOnStartup,
//        deployXML,
//        unpackWARs,
//        smClient);

      // Pass request back down the filter chain
      chain.doFilter(request,response);
  }



  public void destroy() {
      //add code to release any resource
  }
}