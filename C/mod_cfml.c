/* ##############################################################################
# package:		mod_cfml.c
# version:		1.0.0
# author:		Paul Klinkenberg (paul@lucee.nl)
# website:		http://www.modcfml.org/  ||  http://www.lucee.nl/
# license:		LGPL 3.0; see http://www.opensource.org/licenses/lgpl-3.0.html
# notes:		Based on http://httpd.apache.org/docs/2.4/developer/modguide.html
#
# usage:		Add the following lines to Apache's httpd.conf
#
	LoadModule modcfml_module     [your path to]/mod_cfml.so
	CFMLHandlers ".cfm .cfc .cfml"
	# optional:
	LogHeaders [true|false]
	LogHandlers [true|false]
#
############################################################################## */
/* Include the required headers from httpd */
#include "httpd.h"
#include "http_core.h"
#include "http_protocol.h"
#include "http_request.h"
#include "http_log.h"

#ifdef APLOG_USE_MODULE
	APLOG_USE_MODULE(modcfml);
#endif

/* Define prototypes of our functions in this module */
static void register_hooks(apr_pool_t *pool);
static int modcfml_handler(request_rec *r);


/*
 ==============================================================================
 Our configuration prototype and declaration:
 ==============================================================================
 */
typedef struct {
    const char *CFMLHandlers;
    bool LogHeaders;
    bool LogHandlers;
} modcfml_config;

static modcfml_config config;


/*
 ==============================================================================
 Our directive handlers:
 ==============================================================================
 */
/* Handler for the "LogHeaders" directive */
const char *modcfml_set_logheaders(cmd_parms *cmd, void *cfg, const char *arg)
{
    if(strcasecmp(arg, "true") == 0){
    	config.LogHeaders = true;
    }
    else config.LogHeaders = false;
    return NULL;
}

/* Handler for the "LogHandlers" directive */
const char *modcfml_set_loghandlers(cmd_parms *cmd, void *cfg, const char *arg)
{
    if(strcasecmp(arg, "true") == 0) {
    	config.LogHandlers = true;
    }
    else config.LogHandlers = false;
    return NULL;
}

/* Handler for the "CFMLHandlers" directive */
const char *modcfml_set_cfmlhandlers(cmd_parms *cmd, void *cfg, const char *arg)
{
	config.CFMLHandlers = arg;
    return NULL;
}

/*
 ==============================================================================
 The directive structure for our name tag:
 ==============================================================================
 */
static const command_rec modcfml_directives[] =
{
    AP_INIT_TAKE1("CFMLHandlers", modcfml_set_cfmlhandlers, NULL, RSRC_CONF, "Which file types to work with"),
    AP_INIT_TAKE1("LogHandlers", modcfml_set_loghandlers, NULL, RSRC_CONF, "Logging of the CFMLHandlers true/false"),
    AP_INIT_TAKE1("LogHeaders", modcfml_set_logheaders, NULL, RSRC_CONF, "Logging of the incoming headers true/false"),
    { NULL }
};


/* Define our module as an entity and assign a function for registering hooks  */

module AP_MODULE_DECLARE_DATA   modcfml_module =
{
    STANDARD20_MODULE_STUFF,
    NULL,            // Per-directory configuration handler
    NULL,            // Merge handler for per-directory configurations
    NULL,            // Per-server configuration handler
    NULL,            // Merge handler for per-server configurations
    modcfml_directives,            // Any directives we may have for httpd
    register_hooks   // Our hook registering function
};


/* register_hooks: Adds a hook to the httpd process */
static void register_hooks(apr_pool_t *pool) 
{
    config.CFMLHandlers = ".cfm .cfc .cfml";
    
    /* Hook the request handler */
    ap_hook_handler(modcfml_handler, NULL, NULL, APR_HOOK_FIRST);
}

static int print_header(void* rec, const char* key, const char* value)
{
    request_rec* r = (request_rec *)rec;
	ap_log_error(APLOG_MARK, APLOG_NOTICE, 0, r->server,
		"Incoming header [%s] => %s", key, value);
    return 1;
}

static int modcfml_handler(request_rec *r)
{
    char *ext;
	// get the file extension
	ext = strrchr(r->filename, '.');

	// if no file extension, get outta here
	if (!ext) {
		return DECLINED;
	}

	// simple check to see if extension might match, as a pre-filter
	// ( ".cf" will be matched in ".cfm .cfc")
	if (! strstr(config.CFMLHandlers, ext)) {
		return DECLINED;
	}

	// we will do another substring check, but now while looping through the actual extensions
	char handlers[ strlen(config.CFMLHandlers) + 1 ];
	memset(handlers, '\0', strlen(handlers));
	strcpy(handlers, config.CFMLHandlers);
	
	char *handler;
    const char delim[2] = " ";
	bool found = false;
	handler = strtok(handlers, delim);
	while (handler != NULL)
	{
		if (config.LogHandlers == true) {
			ap_log_error(APLOG_MARK, APLOG_NOTICE, 0, r->server,
				"Handler Pattern Found => %s", handler);
		}
		if (strcmp(handler,ext) == 0) {
			found=true;
			if (config.LogHandlers == true) {
				ap_log_error(APLOG_MARK, APLOG_NOTICE, 0, r->server,
						"Pattern Match TRUE: extension [%s] matches handler [%s]", ext, handler);
			}
		}
		handler = strtok(NULL, delim);
	}

	// if file extension is not to be handled by us
	if (! found) {
		return DECLINED;
	}

	// all good: add Tomcat headers
	apr_table_set(r->headers_in, "X-Tomcat-DocRoot", ap_document_root(r));
	apr_table_set(r->headers_in, "X-Tomcat-ServerName", r->server->server_hostname);


	if (config.LogHeaders == true) {
		ap_log_error(APLOG_MARK, APLOG_NOTICE, 0, r->server,
				"URI => %s, filename => %s", r->uri, r->filename);
		/* log all headers */
		apr_table_do(print_header, r, r->headers_in, NULL);
	}

    // Let Apache know it should continue to do what it wants to
    return DECLINED;
}