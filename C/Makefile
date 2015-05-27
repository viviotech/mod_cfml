-include local-options.mk

ifndef APXS
  APXS := $(shell which apxs2 || which apxs)
endif

ifndef APXS
  $(error No apxs found in path, set APXS = /path/to/apxs in local-options.mk)
endif

all: local-shared-build

local-shared-build: module

module: .libs/mod_cfml.o

.libs/mod_cfml.o: mod_cfml.c
	$(APXS) -c $(CFLAGS) mod_cfml.c

#   install the shared object file into Apache 
install: local-shared-build
	$(APXS) -i $(CFLAGS) mod_cfml.la

#   cleanup
.PHONY : clean
clean :
	rm -f mod_cfml.o \
	      mod_cfml.lo \
	      mod_cfml.slo \
	      mod_cfml.la \
          ./.libs/mod_cfml.o \
          ./.libs/mod_cfml.a \
          ./.libs/mod_cfml.la \
          ./.libs/mod_cfml.lai
