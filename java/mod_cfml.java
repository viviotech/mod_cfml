import java.io.*;
import javax.servlet.*;
import javax.servlet.http.*;

public final class LoggingFilterExample implements Filter 
{
  private FilterConfig filterConfigObj = null;

  public void init(FilterConfig filterConfigObj) {
 	 this.filterConfigObj = filterConfigObj;
  }

  public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
  throws IOException, ServletException 
  {
  String remoteAddress =  request.getRemoteAddr();
  String uri = ((HttpServletRequest) request).getRequestURI();
  String protocol = request.getProtocol();

  chain.doFilter(request, response);
  filterConfigObj.getServletContext().log("Logging Filter Servlet called");
  filterConfigObj.getServletContext().log("**************************");
  filterConfigObj.getServletContext().log("User Logged ! " + User IP: " + remoteAddress + " Resource File: " + uri + " Protocol: " + protocol);
  }

  public void destroy() { }
}
