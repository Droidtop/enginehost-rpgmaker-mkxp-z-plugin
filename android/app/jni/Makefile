NDK       := ${ANDROID_NDK_HOME}
TOOLCHAIN := $(NDK)/toolchains/llvm/prebuilt/${ARCH}
NPROC     := $(shell nproc)

# Android platform API version to build:
# (Default: 21 - Android 5.0 Lollipop)
# See https://apilevels.com/ for Android API versions.
# Note: If you building for 64-bit, you should target 21 and higher.
API ?= 21

# Android CPU specific variables to build:
# (Default: ARMv7-A 32-bit)
# For ARMv7-A 32-bit:
# - HOST:   armv7a-linux-androideabi
# - TARGET: arm-linux-androideabi
# - ABI:    armeabi-v7a
# For ARMv8-A 64-bit:
# - HOST:   aarch64-linux-android
# - TARGET: aarch64-linux-android
# - ABI:    arm64-v8a
# For x86 32-bit:
# - HOST:   i686-linux-android
# - TARGET: i686-linux-android
# - ABI:    x86
# For x86_64 64-bit:
# - HOST:   x86_64-linux-android
# - TARGET: x86_64-linux-android
# - ABI:    x86_64
HOST   ?= armv7a-linux-androideabi
TARGET ?= arm-linux-androideabi
ABI    ?= armeabi-v7a

# Define directories
PREFIX            := $(HOST)$(API)
BUILD_PREFIX      := ${PWD}/build-$(ABI)
INCDIR            := $(BUILD_PREFIX)/include
LIBDIR            := $(BUILD_PREFIX)/lib
PKG_CONFIG_LIBDIR := $(BUILD_PREFIX)/lib/pkgconfig

ifneq (, ${MSYSTEM})
	RUBY_PREFIX := $(shell cygpath -m ${BUILD_PREFIX})
else ifneq (, $(findstring /cygdrive/,$(PATH)))
	RUBY_PREFIX := $(shell cygpath -m ${BUILD_PREFIX})
else
	RUBY_PREFIX := $(BUILD_PREFIX)
endif
RUBY19_PREFIX := $(BUILD_PREFIX)/ruby19
RUBY19_LIBDIR := $(RUBY19_PREFIX)/lib

# Toolchain tools
CC      := $(TOOLCHAIN)/bin/$(PREFIX)-clang
CXX     := $(TOOLCHAIN)/bin/$(PREFIX)-clang++
AS      := $(CC)
LD      := $(TOOLCHAIN)/bin/ld
AR      := $(TOOLCHAIN)/bin/llvm-ar
NM      := $(TOOLCHAIN)/bin/llvm-nm
OBJCOPY := $(TOOLCHAIN)/bin/llvm-objcopy
OBJDUMP := $(TOOLCHAIN)/bin/llvm-objdump
RANLIB  := $(TOOLCHAIN)/bin/llvm-ranlib
STRIP   := $(TOOLCHAIN)/bin/llvm-strip

# OpenSSL configure has own targets for Android building
ifeq ($(ABI), armeabi-v7a)
	OPENSSL_TARGET := android-arm
else ifeq ($(ABI), arm64-v8a)
	OPENSSL_TARGET := android-arm64
else ifeq ($(ABI), x86)
	OPENSSL_TARGET := android-x86
else ifeq ($(ABI), x86_64)
	OPENSSL_TARGET := android-x86_64
endif

# Some flags for ARM arch
ifeq ($(ABI), armeabi-v7a)
	CROSSFLAGS := -D__ARM_ARCH_7A__ -D__arm__ -march=armv7-a -mtune=cortex-a7 -mfpu=neon -mfloat-abi=softfp
else ifeq ($(ABI), arm64-v8a)
	CROSSFLAGS := -D__ARM_ARCH_8A__ -D__aarch64__ -march=armv8-a -mtune=cortex-a53
endif

# Compiler/linker flags
CFLAGS      := -O0 $(CROSSFLAGS) --sysroot=$(TOOLCHAIN)/sysroot -I$(TOOLCHAIN)/sysroot/usr/include -I$(INCDIR)
LDFLAGS     := -L$(LIBDIR) -lm -lz
CLANG_FLAGS := -target $(TARGET) --gcc-toolchain $(TOOLCHAIN)

# CMake arguments
CMAKE_ARGS := \
	-DCMAKE_TOOLCHAIN_FILE="$(NDK)/build/cmake/android.toolchain.cmake" \
	-DCMAKE_SYSTEM_NAME=Android \
	-DCMAKE_SYSTEM_VERSION=$(API) \
	-DANDROID_PLATFORM=$(API) \
	-DANDROID_ABI=$(ABI) \
	-DCMAKE_ANDROID_ARCH_ABI=$(ABI) \
	-DCMAKE_ANDROID_STL_TYPE=c++_shared \
	-DCMAKE_INSTALL_PREFIX="$(BUILD_PREFIX)" \
	-DCMAKE_PREFIX_PATH="$(BUILD_PREFIX)" \
	-DCMAKE_BUILD_TYPE=RelWithDebInfo \
	-DCMAKE_C_FLAGS="$(CFLAGS)" \
	-G "Unix Makefiles"

# Autoconf environment variables
CONFIGURE_ENV := \
	CC="$(CC)" CXX="$(CXX)" AS="$(AS)" LD="$(LD)" AR="$(AR)" NM="$(NM)" \
	RANLIB="$(RANLIB)" STRIP="$(STRIP)" OBJCOPY="$(OBJCOPY)" OBJDUMP="$(OBJDUMP)" \
	CFLAGS="$(CFLAGS)" LDFLAGS="$(LDFLAGS)" CLANG_FLAGS="$(CLANG_FLAGS)" \
	PKG_CONFIG_LIBDIR="$(PKG_CONFIG_LIBDIR)"

# Autoconf arguments
CONFIGURE_ARGS := --host=$(HOST) --prefix="$(BUILD_PREFIX)"

# Only Android 8.0 Oreo and higher can have some POSIX functions like:
# getpwent(), setpwent(), endpwent(), nl_langinfo(), and nl_langinfo_l()
ifeq ($(shell test $(API) -ge 26; echo $$?), 1)
	CONFIGURE_ARGS += ac_cv_header_pwd_h=no ac_cv_header_langinfo_h=no
endif

# OpenAL CMake arguments to build
OPENAL_CONFIGURE_ARGS := \
	-DALSOFT_REQUIRE_OPENSL=ON \
	-DALSOFT_EXAMPLES=OFF \
	-DALSOFT_UTILS=OFF

# Ruby configure arguments to build
RUBY31_CONFIGURE_ARGS := \
	--prefix="$(RUBY_PREFIX)" \
	--enable-shared \
	--enable-static \
	--enable-install-static-library \
	--disable-install-doc \
	--disable-rubygems \
	--with-rubygemsdir="$(RUBY_PREFIX)/lib/ruby/gems/3.1.0" \
	--with-out-ext=readline,dbm,gdbm,win32,win32ole,fiddle \
	--without-gmp

# Ruby 1.9 embeds this host command in its cross-compile preload. Load the host
# file utilities/config first, then disable RubyGems before the preload changes
# RUBY_VERSION to the target value.
RUBY19_CONFIGURE_ARGS := \
	--prefix="$(RUBY19_PREFIX)" \
	--with-baseruby="$$(command -v ruby) --disable-gems -rfileutils" \
	--with-soname=ruby19 \
	--enable-shared \
	--disable-install-doc \
	--with-out-ext=readline,dbm,gdbm,win32,win32ole,fiddle,tk,curses,openssl

CONFIGURE := $(CONFIGURE_ENV) ./configure $(CONFIGURE_ARGS)
CMAKE     := $(CONFIGURE_ENV) cmake .. $(CMAKE_ARGS)


.PHONY: all init clean iconv openal openssl ruby31 ruby19 clean-iconv clean-openal clean-openssl clean-ruby

default: all


# -------------------- iconv --------------------
iconv: init $(LIBDIR)/libiconv.a

$(LIBDIR)/libiconv.a: libiconv/Makefile
	cd libiconv; make -j$(NPROC); make install

libiconv/Makefile: libiconv/configure
	cd libiconv; $(CONFIGURE) --enable-static=true --enable-shared=false

# -------------------- OpenAL --------------------
openal: init $(LIBDIR)/libopenal.so

$(LIBDIR)/libopenal.so: openal/cmakebuild-$(ABI)/Makefile
	cd openal/cmakebuild-$(ABI); make -j$(NPROC); make install

openal/cmakebuild-$(ABI)/Makefile: openal/CMakeLists.txt
	cd openal; mkdir -p cmakebuild-$(ABI); cd cmakebuild-$(ABI); \
	$(CMAKE) -DLIBTYPE=SHARED $(OPENAL_CONFIGURE_ARGS)

# -------------------- OpenSSL --------------------
openssl: init $(LIBDIR)/libssl.a

$(LIBDIR)/libssl.a: openssl/Makefile
	cd openssl; make -j$(NPROC); make install_sw

openssl/Makefile: openssl/Configure
	cd openssl; CFLAGS="-Wno-macro-redefined" ./Configure $(OPENSSL_TARGET) \
	no-shared -D__ANDROID_API__=$(API) \
	--prefix="$(BUILD_PREFIX)" --openssldir="$(BUILD_PREFIX)"

# -------------------- Ruby runtimes --------------------
ruby31: init openssl $(LIBDIR)/libruby31.so

$(LIBDIR)/libruby31.so: ruby31/Makefile
	# Ruby's cross-build extension graph is not parallel-safe: libruby can be
	# linked before a static extension archive (for example bigdecimal.a)
	# exists. Keep this one dependency serialized and preserve parallelism for
	# the other native libraries.
	cd ruby31; make -j1; make install-all \
		'INSTALL_DOC_OPTS=--exclude=doc --exclude=default-gems --exclude=default-gems-comm --exclude=default-gems-arch --exclude=bundled-gems'
	cp $(LIBDIR)/libruby.so.3.1.0 $@

ruby31/Makefile: ruby31/configure
	cd ruby31; DLDFLAGS="-Wl,-soname,libruby31.so" \
	$(CONFIGURE) $(RUBY31_CONFIGURE_ARGS)

ruby31/configure: ruby31/configure.ac
	cd ruby31; autoreconf -i

ruby19: init $(RUBY19_LIBDIR)/libruby19.so

$(RUBY19_LIBDIR)/libruby19.so: ruby19/Makefile
	cd ruby19; make -j1 && make install-nodoc

ruby19/Makefile: ruby19/configure
	cd ruby19; ac_cv_func_setpgrp_void=yes \
		$(CONFIGURE_ENV) ./configure $(CONFIGURE_ARGS) $(RUBY19_CONFIGURE_ARGS)

ruby19/configure: ruby19/configure.in
	cd ruby19; autoreconf -i
	# Ruby 1.9.2 predates both Android's androideabi OS name and AArch64.
	# Refresh only the GNU host canonicalizers; the Ruby source itself remains
	# the pinned official tag and modern Autoconf already ships these files.
	cp "$$(automake --print-libdir)/config.sub" ruby19/tool/config.sub
	cp "$$(automake --print-libdir)/config.guess" ruby19/tool/config.guess


init:
	@mkdir -p $(LIBDIR) $(INCDIR)

clean-iconv:
	-cd libiconv; make clean; rm Makefile

clean-openal:
	-cd openal; rm -rf cmakebuild-$(ABI)

clean-openssl:
	-cd openssl; make clean; rm Makefile

clean-ruby:
	-cd ruby31; make clean; rm Makefile; rm configure
	-cd ruby19; make clean; rm Makefile; rm configure

clean: clean-iconv clean-openal clean-openssl clean-ruby

all: iconv openal openssl ruby31 ruby19
