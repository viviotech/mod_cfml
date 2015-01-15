##############################################################################
# package:		mod_cfml.pm
# version:		1.0.14
# author:		Jordan Michaels (jordan@viviotech.net)
# website:		http://www.modcfml.org/
# license:		LGPL 3.0
#			http://www.opensource.org/licenses/lgpl-3.0.html
#
# usage:		Add the following lines to Apache (the PerlSetVar's
#			are all optional)
#
#			PerlRequire /path/to/mod_cfml.pm
#			PerlHeaderParserHandler mod_cfml
#			PerlSetVar LogHeaders [true|false]
#			PerlSetVar LogHandlers [true|false]
#			PerlSetVar CFMLHandlers .cfm .cfc .cfml
##############################################################################
package mod_cfml;
use strict;

# add the various API's that we need
use Apache2::ServerUtil  ();
use Apache2::RequestUtil ();
use Apache2::RequestRec  ();
use Apache2::RequestIO   ();
use Apache2::Filter      ();
use Apache2::Log         ();
use APR::Table           ();

# enable strict syntax
use warnings FATAL => qw(all);

# create apache responses
use Apache2::Const -compile => qw(OK DECLINED :log);

sub handler {
	my $r               = shift;
	my $headers_in      = $r->headers_in();
	my $document_root   = $r->document_root();
	my $filename        = $r->filename();
	my $LogHeaders      = $r->dir_config('LogHeaders');
	my $LogHandlers     = $r->dir_config('LogHandlers');
	my $RawCFMLHandlers = $r->dir_config('CFMLHandlers');
	my $myURI           = $r->uri();
	my @CFMLHandlers    = ();

	# verify LogHeaders
	if ( !defined $LogHeaders || !length $LogHeaders ) {

		# no value was defined, set to default
		$LogHeaders = "false";
	}

	# verify LogHandlers
	if ( !defined $LogHandlers || !length $LogHandlers ) {

		# no value was defined, set to default
		$LogHandlers = "false";
	}

	# verify CFMLHandlers
	if ( !defined $RawCFMLHandlers || !length $RawCFMLHandlers ) {
		
		# no value was defined, set to default
		@CFMLHandlers = qw(.cfm .cfc .cfml);
	} else {
		@CFMLHandlers  = split( / /, $RawCFMLHandlers )
	}

	if ( $LogHandlers eq 'true' ) {

		# loop over our handler patterns
		foreach (@CFMLHandlers) {

			# send each pattern to the log
			$r->log->notice("[mod_cfml] Handler Pattern Found => $_");
		}
	}

	# initial value set to false
	my $FoundHandlerPattern = 0;

	# see if our URI matches a handler pattern
	foreach (@CFMLHandlers) {
		my $CurrentHandlerPattern = $_;

		# check if this current URI pattern is part of the request URI
		if ( $myURI =~ m/$CurrentHandlerPattern/i ) {
			$FoundHandlerPattern = 1;

			# log it if we need to
			if ( $LogHandlers eq 'true' ) {
				$r->log->notice(
"[mod_cfml] Pattern Match TRUE: $CurrentHandlerPattern => $myURI"
				);
			}
		}
		else {

			# log the non-matching pattern if logging is on
			if ( $LogHandlers eq 'true' ) {
				$r->log->notice(
"[mod_cfml] Pattern Match FALSE: $CurrentHandlerPattern => $myURI"
				);
			}
		}    # close pattern check
	}    # close pattern loop

	# check for a default document request
	if ( !$FoundHandlerPattern && chop($myURI) eq "/" ) {

		if ( $LogHandlers eq 'true' ) {		
			$r->log->notice(
			"[mod_cfml] Pattern Match TRUE: Default Document Request Found.");
		}
		$FoundHandlerPattern = 1;

	}

	# if we didn't find a matching pattern, this module doesn't need to run
	if ( !$FoundHandlerPattern ) {
		return Apache2::Const::DECLINED;

		# if logging is on, log that we declined the request
		if ( $LogHandlers eq 'true' ) {
			$r->log->notice(
"[mod_cfml] No CFML handled patterns found. Sending DECLINED response."
			);
		}
	}

	# add a custom header element
	$r->headers_in->add( 'X-Tomcat-DocRoot', $document_root );

	# log if logHeaders is set
	if ( $LogHeaders eq 'true' ) {

		# if logHeaders was specified and set to true,
		# log the headers to the Apache log as a warn
		# so that default apache configs will write it.

		$r->log->notice("[mod_cfml] URI => $myURI");

		while ( my ( $key, $value ) = each(%$headers_in) ) {
			$r->log->notice("[mod_cfml] $key => $value");
		}

	}    # end if logging

	return Apache2::Const::OK;
}

1;
