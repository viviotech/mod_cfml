#Compiling mod_cfml.so

There are quite a few compiled versions of mod_cfml.so:

- Windows: see the [builds directory](builds/)
- Ubuntu: see the [mod_cfml installers](https://github.com/utdream/CFML-Installers/tree/master/lucee/linux/sys/mod_cfml)
- CentOS: see the [mod_cfml installers](https://github.com/utdream/CFML-Installers/tree/master/lucee/linux/sys/mod_cfml)
- Mac OSX 10.10 (maybe others as well): see the [builds directory](builds/)

If you can't find a pre-compiled version at those locations, please follow the instructions underneath.

##Compile for Mac and Linux

1. Get the source code from github: https://github.com/utdream/mod_cfml/archive/master.zip
    and extract into eg. `~/Documents/` (_a sub-folder "mod_cfml-master" will be created_)
    
2. This depends on the apache development tools, i.e. apsx
    sudo apt-get install apache2-dev (debian/ubuntu)
    yum install httpd24-devel (redhat etc)

3. Open a Terminal window, and enter:

    ```
    cd ~/Documents/mod_cfml-master/C
    sudo make
    sudo make install
    ```

4. The mod_cfml.so file can now be found in your Apache modules directory, by default at `/usr/libexec/apache2/mod_cfml.so`

##Compile for Windows

###Prerequisites

1.  Install Visual Studio.
    *Make sure the Visual Studio version matches the version with which your Apache version is compiled.*
     You can check the Visual Studio version by opening a Command prompt (cmd.exe), CD to the Apache bin directory, and then type `httpd -v`.
     The output should contain "VC14", "VC11", or another version. VC14=Visual Studio 2015, VC11=Visual Studio 2012, etc.
2. Download Apache 2.4
 - for Win32 into C:/Apache_x86 : https://www.apachelounge.com/download/
 - for Win64 into C:/Apache_x64 : https://www.apachelounge.com/download/
3. Download and install cMake: http://www.cmake.org/download/
 - Make sure `C:\Program Files (x86)\cMake\bin\` is in your PATH after install
4. Get the source code from github: https://github.com/utdream/mod_cfml/archive/master.zip
    and extract into `C:\modcfml\` (_a sub-folder "mod_cfml-master" will be created_)

### Compile for Win Apache httpd 32-bit

1. Open a Visual Studio **x86** command Prompt, "_VS2013 **x86** Native Tools Command Prompt_". Mine was in `C:\Program Files (x86)\Microsoft Visual Studio 12.0\Common7\Tools\Shortcuts\`
2. In the command window, enter:

    ```
    mkdir build
    cd build
    cmake -DCMAKE_INSTALL_PREFIX=c:/Apache_x86 -G "NMake Makefiles" -DCMAKE_BUILD_TYPE=RelWithDebInfo C:/modcfml/mod_cfml-master/C
    nmake && nmake install
    ```

3. The newly created mod_cfml.so can be found in `C:/Apache_x86/modules/`

### Compile for Win Apache httpd 64-bit

1. Open a Visual Studio **x64** command Prompt, "_VS2013 **x64** Native Tools Command Prompt_".
   Mine was in `C:\Program Files (x86)\Microsoft Visual Studio 12.0\Common7\Tools\Shortcuts\`
2. In the prompt, enter:

    ```
    mkdir build
    cd build
    cmake -DCMAKE_INSTALL_PREFIX=c:/Apache_x64 -G "NMake Makefiles" -DCMAKE_BUILD_TYPE=RelWithDebInfo C:/modcfml/mod_cfml-master/C
    nmake && nmake install
    ```

3. The newly created mod_cfml.so can be found in `C:/Apache_x64/modules/`

---------
> Thanks to Jeff Trawick at the modules-dev@httpd.apache.org mailing list, who explained it all :)
