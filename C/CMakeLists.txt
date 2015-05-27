PROJECT(MODS C)

CMAKE_MINIMUM_REQUIRED(VERSION 2.8)

SET(modnames cfml)

INCLUDE_DIRECTORIES(${CMAKE_INSTALL_PREFIX}/include)

FOREACH(shortname ${modnames})
   SET(modbase "mod_${shortname}")
   SET(modbasename "${modbase}.c")
   ADD_LIBRARY(${modbase} SHARED ${modbasename})
   SET_TARGET_PROPERTIES(${modbase} PROPERTIES SUFFIX .so)
   TARGET_LINK_LIBRARIES(${modbase}
"${CMAKE_INSTALL_PREFIX}/lib/libhttpd.lib"
"${CMAKE_INSTALL_PREFIX}/lib/libapr-1.lib"
"${CMAKE_INSTALL_PREFIX}/lib/libaprutil-1.lib")
   INSTALL(TARGETS ${modbase} RUNTIME DESTINATION modules)
ENDFOREACH()