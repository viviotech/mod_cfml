/* ##############################################################################
# package:		mod_cfml.c
# version:		see revisions underneath
# author:		Paul Klinkenberg (paul@lucee.nl)
# website:		http://www.modcfml.org/  ||  http://www.lucee.nl/
# license:		LGPL 3.0; see http://www.opensource.org/licenses/lgpl-3.0.html
# notes:		Based on http://httpd.apache.org/docs/2.4/developer/modguide.html
# Rev. 1.1.02:	Changed load order of this module to "APR_HOOK_FIRST - 1" (thanks to https://github.com/Wyox)
# Rev. 1.1.03:	Fixed NPE error when request was not handled by a virtualhost
#				Updated code to be more compatible with old Windows C89 (boolean to int etc)
#				Improved the way config info of the alias_module is found (now dependant on external function instead of external variable)
#				Removed unused includes
# Rev. 1.1.04:	Bugfix: wrong x-ajp-path-info header was sent, resulting in wrong path_info on the tomcat side
#				Thanks a lot to Tim Bugler, for helping debugging the 1.1 version!
# Rev. 1.1.06:	Changed native C to APR functions, for more reliable memory management:
#				apr_palloc instead of malloc / apr_pstrcat / apr_pstrdup / apr_psprintf;
#				Updated some optional debug output ("Incoming header" => "Request header", Alias output list starts with 1 instead of 0)
# Rev. 1.1.07:	(Nov. 14, 2015) Added a version notice when this module starts up.
#				note: Love to all French citizens who suffered in the terrorist attack yesterday. Unreal.
# Rev. 1.1.08:	never released, but referenced in some git branch names, so skipping this version nr
# Rev. 1.1.09:	(May 1, 2019) Only changed the Tomcat component
# Rev. 1.1.10:	(June 5, 2019) Only changed the Tomcat component
#
#				*** Don't forget to update the version nr. at modcfml_init_handler() when the Rev. nr. is updated! ***
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
	# AJPPathInfoHeader  false
#
############################################################################## */
/* Include the required headers from httpd */
#include "httpd.h"
#include "http_core.h"
#include "http_log.h"
#include "apr_strings.h"
#include <string.h>
#include <assert.h>
#include <http_config.h>
#include <ctype.h>
#include <stdlib.h>

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

char *getbasename(char const *name)
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
	/* If NAME is all slashes, arrange to return '/'. */
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
static int modcfml_init_handler(apr_pool_t *p, apr_pool_t *plog, apr_pool_t *ptemp, server_rec *s);


/*
 ==============================================================================
 Our configuration prototype and declaration:
 ==============================================================================
 */
typedef struct {
	const char *CFMLHandlers;
	int LogHeaders;
	int LogHandlers;
	int LogAliases;
	int VDirHeader;
	int AJPPathInfoHeader;
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
const char *modcfml_set_logheaders (cmd_parms *cmd, void *cfg, const char *arg)
{
    config.LogHeaders = strcasecmp(arg, "true") == 0 ? 1:0;
	return NULL;
}
/* Handler for the "LogHandlers" directive */
const char *modcfml_set_loghandlers(cmd_parms *cmd, void *cfg, const char *arg)
{
    config.LogHandlers = strcasecmp(arg, "true") == 0 ? 1:0;
	return NULL;
}

/* Handler for the "LogAliases" directive */
const char *modcfml_set_logaliases(cmd_parms *cmd, void *cfg, const char *arg)
{
    config.LogAliases = strcasecmp(arg, "true") == 0 ? 1:0;
	return NULL;
}

/* Handler for the "VDirHeader" directive */
const char *modcfml_set_vdirheader(cmd_parms *cmd, void *cfg, const char *arg)
{
    config.VDirHeader = strcasecmp(arg, "true") == 0 ? 1:0;
	return NULL;
}

/* Handler for the "AJPPathInfoHeader" directive */
const char *modcfml_set_AJPPathInfoHeader(cmd_parms *cmd, void *cfg, const char *arg)
{
    config.AJPPathInfoHeader = strcasecmp(arg, "true") == 0 ? 1:0;
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
    AP_INIT_TAKE1("ModCFML_SharedKey", modcfml_set_sharedkey, NULL, RSRC_CONF,
                  "Optional secret key to validate at the Tomcat side"),
	AP_INIT_TAKE1("CFMLHandlers", modcfml_set_cfmlhandlers, NULL, RSRC_CONF,
                  "Which file types to work with"),
	AP_INIT_TAKE1("LogHandlers", modcfml_set_loghandlers, NULL, RSRC_CONF,
                  "Logging of the CFMLHandlers true/false"),
	AP_INIT_TAKE1("LogAliases", modcfml_set_logaliases, NULL, RSRC_CONF,
                  "Logging of the available Aliases true/false"),
	AP_INIT_TAKE1("VDirHeader", modcfml_set_vdirheader, NULL, RSRC_CONF,
                  "Add request header x-vdirs with aliases info true/false"),
	AP_INIT_TAKE1("AJPPathInfoHeader", modcfml_set_AJPPathInfoHeader, NULL, RSRC_CONF,
                  "Add request header x-ajp-path-info if path_info is available true/false"),
	AP_INIT_TAKE1("LogHeaders", modcfml_set_logheaders, NULL, RSRC_CONF,
                  "Logging of the incoming headers true/false"),
    {NULL}
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

/* register_hooks: Adds a hook to the httpd process */
static void register_hooks(apr_pool_t *pool)
{
	config.CFMLHandlers = ".cfm .cfc .cfml";
	config.VDirHeader = 1;
	config.AJPPathInfoHeader = 1;
	/* Make sure this handler is called before mod_proxy / mod_jk is called,
	   by setting hook order to APR_HOOK_FIRST - 1 */
	ap_hook_handler(modcfml_handler, NULL, NULL, APR_HOOK_FIRST - 1);
	ap_hook_post_config(modcfml_init_handler, NULL, NULL, APR_HOOK_MIDDLE);
}

/**
 * Module initialization
 */
static int modcfml_init_handler(apr_pool_t *p, apr_pool_t *plog, apr_pool_t *ptemp, server_rec *s) {
	void *data = NULL;
	const char *key = "modcfml_thanks_config";

	// This code is used to prevent double initialization of the module during Apache startup
	apr_pool_userdata_get(&data, key, s->process->pool);
	if ( data == NULL ) {
		apr_pool_userdata_set((const void *)1, key, apr_pool_cleanup_null, s->process->pool);
		return OK;
	}

	ap_log_error(APLOG_MARK, APLOG_STARTUP, 0, s,
		"Thanks for using ModCFML, version 1.1.10");
    return OK;
}

static int print_header(void* rec, const char* key, const char* value)
{
	request_rec* r = (request_rec *)rec;
	ap_log_error(APLOG_MARK, APLOG_NOTICE, 0, r->server,
		"Request header [%s] => %s", key, value);
	return 1;
}

static char *copy_without_trailing_slash(const char* source, apr_pool_t *pool)
{
	void* tmpptr;
	char* dest;
	size_t pathlen = strlen(source);
	if (pathlen > 1 && (strcmp(&source[ pathlen - 1 ], "/") == 0 || strcmp(&source[ pathlen - 1 ], "\\") == 0))
	{
		pathlen = pathlen - 1;
	}

	tmpptr = apr_palloc(pool, (size_t)(pathlen + 1));

	dest = (char*) tmpptr;
	if (dest != NULL)
	{
		memcpy(dest, source, pathlen);
		dest[ pathlen ] = '\0';
	}
	return dest;
}


static int add_alias_header(request_rec* r, apr_pool_t *pool)
{
	ap_conf_vector_t *sconf;
	void *tmpConf;
	alias_server_conf *serverconf;
	apr_array_header_t *aliases;
	alias_entry *entries;
	char * header_string = "";
	char * fake = 0;
	char * real = 0;
	int i;
	alias_entry *alias;

	module *aliasmodule = ap_find_linked_module("mod_alias.c");
	
	if (! aliasmodule)
	{
		if (config.LogAliases==1) {
			ap_log_error(APLOG_MARK, APLOG_NOTICE, 0, r->server,
				"Printing aliases: [alias_module] could not be found!");
		}
		return 1;
	}
	
	sconf = r->server->module_config;
	tmpConf = ap_get_module_config(sconf, aliasmodule);
	if (tmpConf == NULL) {
		if (config.LogAliases==1) {
			ap_log_error(APLOG_MARK, APLOG_NOTICE, 0, r->server,
				"Printing aliases: [alias_module] was found, but it's config could not be retrieved!");
		}
		return 1;
	}
	serverconf = (alias_server_conf *)tmpConf;
	aliases = serverconf->aliases;
	
	entries = (alias_entry *) aliases->elts;

	if (config.LogAliases==1) {
		ap_log_error(APLOG_MARK, APLOG_NOTICE, 0, r->server,
			"Printing aliases: [%d] found", aliases->nelts);
	}
	
	for (i = 0; i < aliases->nelts; ++i) {
		alias = &entries[i];

		if (alias->regexp) {
			if (config.LogAliases==1) {
				ap_log_error(APLOG_MARK, APLOG_NOTICE, 0, r->server,
					"%d. RegEx alias, not usable by mod_cfml: [%s] -> [%s]", i+1, alias->fake, alias->real);
			}
		}
		else {
			if (config.LogAliases==1) {
				ap_log_error(APLOG_MARK, APLOG_NOTICE, 0, r->server,
					"%d. Alias: [%s] -> [%s]", i+1, alias->fake, alias->real);
			}
			
			/* remove trailing slash, if any */
			fake = copy_without_trailing_slash(alias->fake, pool);
			real = copy_without_trailing_slash(alias->real, pool);

			if(fake == NULL || real == NULL) {
				return 1;
			}
			header_string = apr_pstrcat(pool, header_string, fake, ",", real, ";", NULL);
		}
	}

	apr_table_set(r->headers_in, "x-vdirs", header_string);
	return 1;
}


static int modcfml_handler(request_rec *r)
{
	char *ext;
	const char *slash = "/";
	char *handlers;
	char *handler;
	const char delim[2] = " ";
	int found = 0;
	char *server_hostname;
	char *config_filename;
	char *line_number;
	char *unique_vhost_id;
    size_t i = 0;
    apr_pool_t *pool = r->pool;

	// security: check if there already is a DocRoot header coming in,
	// and if so, remove the header
	if (apr_table_get(r->headers_in, "X-Tomcat-DocRoot") != NULL) {
		ap_log_error(APLOG_MARK, APLOG_ERR, 0, r->server,
			"Incoming X-Tomcat-DocRoot header found, while we have not set it yet! Header value => %s",
			apr_table_get(r->headers_in, "X-Tomcat-DocRoot"));
		apr_table_unset(r->headers_in, "X-Tomcat-DocRoot");
	}

	// get the file extension
	ext = strrchr(r->uri, '.');

	// if no file extension, get outta here
	if (!ext) {
		return DECLINED;
	}

	// does the extension contain path_info at the end?
	if (strstr(ext, slash))
	{
		if (config.AJPPathInfoHeader == 1) {
			// set the path_info header for Lucee/Railo/OBD
			apr_table_set(r->headers_in, "xajp-path-info", strstr(ext, slash) );
		}

		// Awesome awesome stuff:
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
	handlers = (char *)apr_palloc(pool, (size_t)(strlen(config.CFMLHandlers) + 1) );
	memset(handlers, '\0', strlen(handlers));
	strcpy(handlers, config.CFMLHandlers);
	
	handler = strtok(handlers, delim);
	while (handler != NULL)
	{
		if (config.LogHandlers==1) {
			ap_log_error(APLOG_MARK, APLOG_NOTICE, 0, r->server,
				"Handler Pattern Found => %s", handler);
		}
		if (strcmp(handler,ext) == 0) {
			found=1;
			if (config.LogHandlers==1) {
				ap_log_error(APLOG_MARK, APLOG_NOTICE, 0, r->server,
						"Pattern Match TRUE: extension [%s] matches handler [%s]", ext, handler);
			}
		}
		handler = strtok(NULL, delim);
	}

	// if file extension is not to be handled by us
	if (found==0) {
		return DECLINED;
	}


	// create unique name for this VirtualHost context
	server_hostname = apr_pstrdup(pool, r->server->server_hostname );
	if (server_hostname == NULL) {
		server_hostname = "nohostname";
	}
	if (r->server->defn_name != NULL) {
		config_filename = getbasename( apr_pstrdup(pool, r->server->defn_name ) );
		if (config_filename == NULL || strlen(config_filename) == 0) {
			config_filename = "server-conf";
		}
	} else {
		config_filename = "server-conf";
	}

	line_number = apr_psprintf(pool, "l%d", r->server->defn_line_number);
	unique_vhost_id = apr_pstrcat(pool, server_hostname, "-", config_filename, line_number, NULL);
    // remove any special characters from the unique_vhost_id, especially colons (:) which are used in IPV6
    for(; i < strlen(unique_vhost_id); i++)
    {
        if (!isalnum(unique_vhost_id[i]))
        {
            if (i==0) {
                /* make sure the unique_vhost_id does not start with a dash (-), to be a bit more compliant with
                 * host-name expectations on the tomcat side (not tested if necessary though) */
                unique_vhost_id[i] = 'x';
            } else {
                unique_vhost_id[i] = '-';
            }
        }
    }

	// all good: add Tomcat headers
	apr_table_set(r->headers_in, "X-Tomcat-DocRoot", ap_document_root(r));
	apr_table_set(r->headers_in, "X-Webserver-Context", unique_vhost_id);

	if (config.SharedKey != NULL) {
		apr_table_set(r->headers_in, "X-ModCFML-SharedKey", config.SharedKey);
	}

	// new: add a header X-VDirs, which contains all known aliases for the VHost
	if (config.VDirHeader == 1) {
		add_alias_header(r, pool);
	}

	if (config.LogHeaders == 1) {
		ap_log_error(APLOG_MARK, APLOG_NOTICE, 0, r->server,
				"URI => %s, filename => %s", r->uri, r->filename);
		/* log all headers */
		apr_table_do(print_header, r, r->headers_in, NULL);
	}

	// Let Apache know it should continue to do what it wants to
	return DECLINED;
}
