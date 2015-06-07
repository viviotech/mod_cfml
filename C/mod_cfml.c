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
	LoadModule modcfml_module	 [your path to]/mod_cfml.so
	CFMLHandlers ".cfm .cfc .cfml"
	ModCFML_SharedKey "secret key also set in the Tomcat valve config"
	# optional: (enable to change the default setting)
	# LogHeaders  true
	# LogHandlers true
	# LogAliases  true
	# VDirHeader  false
#
############################################################################## */
/* Include the required headers from httpd */
#include "httpd.h"
#include "http_core.h"
#include "http_protocol.h"
#include "http_request.h"
#include "http_log.h"
#include "apr_strings.h"
#include <stdbool.h>
#include <string.h>
#include <assert.h>

/* ###################### basename function, for windows ##################### */
#if defined _WIN32 || defined __WIN32__ || defined __EMX__ || defined __DJGPP__
	/* Win32, OS/2, DOS */
	# define HAS_DEVICE(P) \
	((((P)[0] >= 'A' && (P)[0] <= 'Z') || ((P)[0] >= 'a' && (P)[0] <= 'z')) \
	&& (P)[1] == ':')
	# define FILESYSTEM_PREFIX_LEN(P) (HAS_DEVICE (P) ? 2 : 0)
	# define ISSLASH(C) ((C) == '/' || (C) == '\\')
#endif

#ifndef FILESYSTEM_PREFIX_LEN
	# define FILESYSTEM_PREFIX_LEN(Filename) 0
#endif

#ifndef ISSLASH
	# define ISSLASH(C) ((C) == '/')
#endif

char *basename(char const *name)
{
	char const *base = name += FILESYSTEM_PREFIX_LEN (name);
	int all_slashes = 1;
	char const *p;
	for (p = name; *p; p++)
	{
		if (ISSLASH (*p))
			base = p + 1;
		else
			all_slashes = 0;
	}
	/* If NAME is all slashes, arrange to return `/'. */
	if (*base == '\0' && ISSLASH (*name) && all_slashes)
		--base;
	/* Make sure the last byte is not a slash. */
	assert(all_slashes || !ISSLASH (*(p - 1)));

	return (char *) base;
}
/* ###################### END basename function, for windows ################# */

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
	bool LogAliases;
	bool VDirHeader;
	const char *SharedKey;
} modcfml_config;

/* copied from mod_alias.c, Apache httpd 2.4.12 source code */
typedef struct {
	apr_array_header_t *aliases;
	apr_array_header_t *redirects;
} alias_server_conf;

/* copied from mod_alias.c, Apache httpd 2.4.12 source code */
typedef struct {
	const char *real;
	const char *fake;
	char *handler;
	ap_regex_t *regexp;
	int redir_status;				/* 301, 302, 303, 410, etc */
} alias_entry;

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

/* Handler for the "LogAliases" directive */
const char *modcfml_set_logaliases(cmd_parms *cmd, void *cfg, const char *arg)
{
	if(strcasecmp(arg, "true") == 0) {
		config.LogAliases = true;
	}
	else config.LogAliases = false;
	return NULL;
}

/* Handler for the "VDirHeader" directive */
const char *modcfml_set_vdirheader(cmd_parms *cmd, void *cfg, const char *arg)
{
	if(strcasecmp(arg, "true") == 0) {
		config.VDirHeader = true;
	}
	else config.VDirHeader = false;
	return NULL;
}


/* Handler for the "CFMLHandlers" directive */
const char *modcfml_set_cfmlhandlers(cmd_parms *cmd, void *cfg, const char *arg)
{
	config.CFMLHandlers = arg;
	return NULL;
}

/* Handler for the "ModCFML_SharedKey" directive */
const char *modcfml_set_sharedkey(cmd_parms *cmd, void *cfg, const char *arg)
{
	config.SharedKey = arg;
	return NULL;
}

/*
 ==============================================================================
 The directive structure for our name tag:
 ==============================================================================
 */
static const command_rec modcfml_directives[] =
{
	AP_INIT_TAKE1("ModCFML_SharedKey", modcfml_set_sharedkey, NULL, RSRC_CONF, "Optional secret key to validate at the Tomcat side"),
	AP_INIT_TAKE1("CFMLHandlers", modcfml_set_cfmlhandlers, NULL, RSRC_CONF, "Which file types to work with"),
	AP_INIT_TAKE1("LogHandlers", modcfml_set_loghandlers, NULL, RSRC_CONF, "Logging of the CFMLHandlers true/false"),
	AP_INIT_TAKE1("LogAliases", modcfml_set_logaliases, NULL, RSRC_CONF, "Logging of the available Aliases true/false"),
	AP_INIT_TAKE1("VDirHeader", modcfml_set_vdirheader, NULL, RSRC_CONF, "Add request header x-vdirs with aliases info true/false"),
	AP_INIT_TAKE1("LogHeaders", modcfml_set_logheaders, NULL, RSRC_CONF, "Logging of the incoming headers true/false"),
	{ NULL }
};


/* Define our module as an entity and assign a function for registering hooks */

module AP_MODULE_DECLARE_DATA modcfml_module =
{
	STANDARD20_MODULE_STUFF,
	NULL,					// Per-directory configuration handler
	NULL,					// Merge handler for per-directory configurations
	NULL,					// Per-server configuration handler
	NULL,					// Merge handler for per-server configurations
	modcfml_directives,		// Any directives we may have for httpd
	register_hooks			// Our hook registering function
};

static module *find_module(char *name, request_rec* r)
{
	int n;
	for (n = 0; ap_loaded_modules[n]; ++n) {
		if (strcmp(name, ap_loaded_modules[n]->name) == 0)
		{
			return ap_loaded_modules[n];
		}
	}
	return NULL;
}

/* register_hooks: Adds a hook to the httpd process */
static void register_hooks(apr_pool_t *pool)
{
    config.CFMLHandlers = ".cfm .cfc .cfml";
    config.VDirHeader = true;
    
    // Sometimes the order of module execution is different in Apache2.4.
    // This prevents mod_proxy from passing on the request to Tomcat/Jetty before we are done with it.
    static const char * const aszSucc[] = {"mod_proxy.c", NULL};
    
    /* Hook the request handler */
    ap_hook_fixups(modcfml_handler, NULL, aszSucc, APR_HOOK_FIRST);
    
    /*ap_hook_handler(modcfml_handler, NULL, NULL, APR_HOOK_FIRST);*/
    
    
}

static int print_header(void* rec, const char* key, const char* value)
{
	request_rec* r = (request_rec *)rec;
	ap_log_error(APLOG_MARK, APLOG_NOTICE, 0, r->server,
		"Incoming header [%s] => %s", key, value);
	return 1;
}

static char* copy_without_trailing_slash(const char* source)
{
	int pathlen = strlen(source);
	if (pathlen > 1 && (strcmp(&source[ pathlen - 1 ], "/") == 0 || strcmp(&source[ pathlen - 1 ], "\\") == 0))
	{
		pathlen = pathlen - 1;
	}
	char* dest = (char*)malloc( pathlen + 1 );
	if (dest != NULL)
	{
		memcpy(dest, source, pathlen);
		dest[ pathlen ] = '\0';
	}
	return dest;
}


static int add_alias_header(request_rec* r)
{
	module *aliasmodule = find_module("mod_alias.c", r);
	
	if (! aliasmodule)
	{
		if (config.LogAliases == true) {
			ap_log_error(APLOG_MARK, APLOG_NOTICE, 0, r->server,
				"Printing aliases: [alias_module] could not be found!");
		}
		return 1;
	}
	
	ap_conf_vector_t *sconf = r->server->module_config;
	alias_server_conf *serverconf = ap_get_module_config(sconf, aliasmodule);
	apr_array_header_t *aliases = serverconf->aliases;
	
	alias_entry *entries = (alias_entry *) aliases->elts;

	if (config.LogAliases == true) {
		ap_log_error(APLOG_MARK, APLOG_NOTICE, 0, r->server,
			"Printing aliases: [%d] found", aliases->nelts);
	}
	
	char * header_string = "";
	char * temp_str;
	char * fake = 0;
	char * real = 0;
	int i;
	for (i = 0; i < aliases->nelts; ++i) {
		alias_entry *alias = &entries[i];

		if (alias->regexp) {
			if (config.LogAliases == true) {
				ap_log_error(APLOG_MARK, APLOG_NOTICE, 0, r->server,
					"%d. RegEx alias, not usable by mod_cfml: [%s] -> [%s]", i, alias->fake, alias->real);
			}
		}
		else {
			if (config.LogAliases == true) {
				ap_log_error(APLOG_MARK, APLOG_NOTICE, 0, r->server,
					"%d. Alias: [%s] -> [%s]", i, alias->fake, alias->real);
			}
			
			/* remove trailing slash, if any */
			fake = copy_without_trailing_slash(alias->fake);
			real = copy_without_trailing_slash(alias->real);
			temp_str = header_string;
			
			if(fake != NULL && real != NULL && (header_string = malloc(strlen(temp_str)+strlen(fake)+strlen(real)+3)) != NULL)
			{
				header_string[0] = '\0';// ensures the memory is an empty string
				strcat(header_string, temp_str);
				strcat(header_string, fake);
				strcat(header_string, ",");
				strcat(header_string, real);
				strcat(header_string, ";");
			} else {
				// fprintf(STDERR,"malloc failed!\n");
				return 1;
			}
		}
	}

	apr_table_set(r->headers_in, "x-vdirs", header_string);
	return 1;
}


static int modcfml_handler(request_rec *r)
{
	// security: check if there already is a DocRoot header coming in,
	// and if so, remove the header
	if (apr_table_get(r->headers_in, "X-Tomcat-DocRoot") != NULL) {
		ap_log_error(APLOG_MARK, APLOG_ERR, 0, r->server,
			"Incoming X-Tomcat-DocRoot header found, while we have not set it yet! Header value => %s",
			apr_table_get(r->headers_in, "X-Tomcat-DocRoot"));
		apr_table_unset(r->headers_in, "X-Tomcat-DocRoot");
	}

	char *ext;
	// get the file extension
	ext = strrchr(r->uri, '.');

	// if no file extension, get outta here
	if (!ext) {
		return DECLINED;
	}

	// does the extension contain path_info at the end?
	const char *slash = "/";
	if (strstr(ext, slash))
	{
		// set the path_info header for Lucee/Railo/OBD
		apr_table_set(r->headers_in, "xajp-path-info", strrchr(ext, '/') );

		// Awesome awesome stuff (for C n00bs like me at least):
		// ext is a pointer to a part of the char array of r->uri
		// strtok sets a \0 at the position where it finds a slash (start of path_info)
		// By doing this, we:
		// a) stripped off the path_info from ext
		// b) stripped off the path_info from r->uri
		strtok(ext, slash);
	}

	// simple check to see if extension might match, as a pre-filter
	// ( ".cf" will be matched in ".cfm .cfc")
	if (! strstr(config.CFMLHandlers, ext)) {
		return DECLINED;
	}

	// we will do another substring check, but now while looping through the actual extensions
	char *handlers = malloc( (strlen(config.CFMLHandlers) + 1) * sizeof(char) );
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
	free(handlers);

	// if file extension is not to be handled by us
	if (! found) {
		return DECLINED;
	}

	// create unique name for this VirtualHost context
	char *server_hostname = strdup( r->server->server_hostname );
	char *config_filepath = strdup( r->server->defn_name );
	char *config_filename = basename(config_filepath);
	if (config_filename == NULL) {
		config_filename = "unknown-conf";
	}
	char line_number[10];
	sprintf(line_number, "l%d", r->server->defn_line_number);
	char *unique_vhost_id = apr_pstrcat(r->pool, server_hostname, "-", config_filename, line_number, NULL);

	// all good: add Tomcat headers
	apr_table_set(r->headers_in, "X-Tomcat-DocRoot", ap_document_root(r));
	apr_table_set(r->headers_in, "X-Webserver-Context", unique_vhost_id);

	free(server_hostname);
	free(config_filepath);

	if (config.SharedKey != NULL) {
		apr_table_set(r->headers_in, "X-ModCFML-SharedKey", config.SharedKey);
	}

	// new: add a header X-VDirs, which contains all known aliases for the VHost
	if (config.VDirHeader) {
		add_alias_header(r);
	}

	if (config.LogHeaders == true) {
		ap_log_error(APLOG_MARK, APLOG_NOTICE, 0, r->server,
				"URI => %s, filename => %s", r->uri, r->filename);
		/* log all headers */
		apr_table_do(print_header, r, r->headers_in, NULL);
	}

	// Let Apache know it should continue to do what it wants to
	return DECLINED;
}